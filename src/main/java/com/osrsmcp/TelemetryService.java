package com.osrsmcp;

import com.google.gson.Gson;
import com.runeassist.flip.config.FlippingCopilotConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * OPT-IN, anonymised telemetry logger.
 *
 * Writes versioned JSONL to a per-day file under ~/.runelite/runeassist/telemetry/
 * so account data (real XP/hr, GE performance, account trajectories) can be analysed
 * later. Live XP/GE/account capture is a no-op unless contribution is on
 * (opt-in, default OFF) in <b>RuneAssist Flipping → Privacy</b> and/or the MCP plugin. GE history
 * dumps still persist locally so a later opt-in can upload them.
 *
 * <p>Flipping's toggle is sufficient on its own (the hosted ingest URL and plugin
 * contribute key are used when the box is ticked — no token to paste). MCP
 * {@code shareTelemetry} / endpoint / token remain as a fallback when Flipping is
 * not used. Local files are always the primary sink. Uploads are batched, retried,
     * off-thread, and limited to {@link #UPLOAD_TYPES}; the {@code advice} record
     * (raw chat question) is NEVER uploaded. {@code suggestion_decision} is uploaded:
     * compact skip/abort/acted/ignored picks, no bank, no XP, no raw RSN.
 *
 * Callers (client thread / EDT) gather live values and pass primitives in; this class
 * never touches the RuneLite Client and never throws. All disk IO runs on a single
 * daemon executor so callers never block.
 */
@Slf4j
@Singleton
public class TelemetryService
{
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int SCHEMA_VERSION = 1;
    static final String DEFAULT_ENDPOINT = "https://runeassist.ares-server.co.uk/v1/ingest";
    /** Public plugin contribute key — not the Ares admin INGEST_TOKEN. */
    static final String PLUGIN_CONTRIBUTE_TOKEN = "ra-plugin-contribute-v1";
    private static final String UPLOAD_UA = "RuneAssist-flip/1.0 (github.com/RuneAssist/runeassist)";
    private static final MediaType JSON = MediaType.parse("application/json");

    private final File baseDir;
    private final OkHttpClient httpClient;

    @Inject private ConfigManager configManager;
    private final Gson gson;
    @Inject private WikiPriceService wikiPriceService;

    private FlippingCopilotConfig flipConfig;
    private OsrsMcpConfig mcpConfig;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "runeassist-telemetry");
        t.setDaemon(true);
        return t;
    });

    // ── optional upload (the hosted ingest flywheel; opt-in + endpoint required) ──
    // Only these record types are ever uploaded. Notably "advice" is excluded — it
    // contains the player's raw chat question, which never leaves the PC.
    private static final Set<String> UPLOAD_TYPES =
        new HashSet<>(Arrays.asList("ge_offer", "account_snapshot", "xp_gain", "ge_history",
            "suggestion_decision"));
    private static final int  BATCH_MAX     = 50;   // flush when this many queued
    private static final long FLUSH_EVERY_S = 60;   // or at least this often
    private static final int  QUEUE_CAP     = 2000; // drop oldest beyond this (bounded memory)
    private static final int  CURSOR_CAP    = 10000;
    private static final String HISTORY_TYPE = "ge_history";
    private static final String CURSOR_FILE  = "ge_history-cursor.json";

    private final List<Map<String, Object>> uploadQueue = new ArrayList<>();
    private final ScheduledExecutorService uploader = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "runeassist-telemetry-upload");
        t.setDaemon(true);
        return t;
    });
    { uploader.scheduleWithFixedDelay(this::flushUploads, FLUSH_EVERY_S, FLUSH_EVERY_S, TimeUnit.SECONDS); }

    // Cache the last (rsn -> hash) pair to avoid rehashing every record.
    private String lastRsn;
    private String lastHash;

    // GE history dump cursor (dedup + upload bookkeeping). Touched only on executor/uploader threads.
    private HistoryCursor historyCursor;
    private boolean historyLocalScanned = false;

    /** One completed GE history row, newest-first as the widget lists them. */
    public static final class GeHistoryFill
    {
        public final int itemId;
        public final int qty;
        public final long price;
        public final boolean buy;
        public final String name;
        public final Long fillTs;

        public GeHistoryFill(int itemId, int qty, long price, boolean buy, String name, Long fillTs)
        {
            this.itemId = itemId;
            this.qty = qty;
            this.price = price;
            this.buy = buy;
            this.name = name;
            this.fillTs = fillTs;
        }

        String finger(int occFromOldest)
        {
            return itemId + "|" + qty + "|" + price + "|" + (buy ? "b" : "s") + "|" + occFromOldest;
        }
    }

    @Inject
    public TelemetryService(OkHttpClient httpClient, Gson gson)
    {
        this.httpClient = httpClient;
        this.gson = gson;
        baseDir = new File(RuneLite.RUNELITE_DIR, "runeassist/telemetry");
    }

    private boolean enabled()
    {
        return flipShare() || mcpShare();
    }

    private boolean flipShare()
    {
        FlippingCopilotConfig f = flip();
        return f != null && f.shareTelemetry();
    }

    private boolean mcpShare()
    {
        OsrsMcpConfig m = mcp();
        return m != null && m.shareTelemetry();
    }

    private FlippingCopilotConfig flip()
    {
        if (flipConfig == null && configManager != null)
        {
            try { flipConfig = configManager.getConfig(FlippingCopilotConfig.class); }
            catch (RuntimeException ignored) {}
        }
        return flipConfig;
    }

    private OsrsMcpConfig mcp()
    {
        if (mcpConfig == null && configManager != null)
        {
            try { mcpConfig = configManager.getConfig(OsrsMcpConfig.class); }
            catch (RuntimeException ignored) {}
        }
        return mcpConfig;
    }

    private static String trimmed(String s)
    {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Prefer Flipping's endpoint (hosted default) when that toggle is on; else MCP. */
    private String contributionEndpoint()
    {
        if (flipShare())
        {
            String e = trimmed(flip().telemetryEndpoint());
            return e != null ? e : DEFAULT_ENDPOINT;
        }
        if (mcpShare())
        {
            String e = trimmed(mcp().telemetryEndpoint());
            if (e != null) return e;
        }
        return null;
    }

    private String contributionToken()
    {
        if (flipShare())
        {
            String t = trimmed(flip().telemetryToken());
            if (t != null) return t;
            if (isHostedAres(contributionEndpoint())) return PLUGIN_CONTRIBUTE_TOKEN;
        }
        if (mcpShare())
        {
            String t = trimmed(mcp().telemetryToken());
            if (t != null) return t;
            if (isHostedAres(contributionEndpoint())) return PLUGIN_CONTRIBUTE_TOKEN;
        }
        return null;
    }

    static boolean isHostedAres(String endpoint)
    {
        if (endpoint == null) return false;
        String e = endpoint.trim().toLowerCase(Locale.ROOT);
        return e.startsWith("https://runeassist.ares-server.co.uk/");
    }

    // ── PUBLIC CAPTURE METHODS ────────────────────────────────────────────────

    public void logXpGain(String rsn, String skill, long xp, long delta, int level,
                          int x, int y, int plane)
    {
        if (!enabled()) return;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("v", SCHEMA_VERSION);
        r.put("type", "xp_gain");
        r.put("ts", System.currentTimeMillis());
        r.put("acct", acctHash(rsn));
        r.put("skill", skill);
        r.put("xp", xp);
        r.put("delta", delta);
        r.put("level", level);
        r.put("x", x);
        r.put("y", y);
        r.put("plane", plane);
        write("xp_gain", r);
    }

    public void logAccountSnapshot(String rsn, int combatLevel, int totalLevel, int questPoints,
                                   String accountType, int x, int y, int plane,
                                   Map<String, long[]> skills)
    {
        if (!enabled()) return;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("v", SCHEMA_VERSION);
        r.put("type", "account_snapshot");
        r.put("ts", System.currentTimeMillis());
        r.put("acct", acctHash(rsn));
        r.put("combat_level", combatLevel);
        r.put("total_level", totalLevel);
        r.put("quest_points", questPoints);
        r.put("account_type", accountType);
        r.put("x", x);
        r.put("y", y);
        r.put("plane", plane);

        // skills: skillName -> [level, xp]
        Map<String, Object> skillMap = new LinkedHashMap<>();
        if (skills != null)
        {
            for (Map.Entry<String, long[]> e : skills.entrySet())
            {
                long[] v = e.getValue();
                if (v == null || v.length < 2) continue;
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("level", v[0]);
                s.put("xp", v[1]);
                skillMap.put(e.getKey(), s);
            }
        }
        r.put("skills", skillMap);
        write("account_snapshot", r);
    }

    public void logGeOffer(String rsn, int slot, String state, int itemId, int price,
                           int totalQuantity, int quantitySold, int spent)
    {
        if (!enabled()) return;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("v", SCHEMA_VERSION);
        r.put("type", "ge_offer");
        r.put("ts", System.currentTimeMillis());
        r.put("acct", acctHash(rsn));
        r.put("slot", slot);
        r.put("state", state);
        r.put("item_id", itemId);
        r.put("price", price);
        r.put("total_quantity", totalQuantity);
        r.put("quantity_sold", quantitySold);
        r.put("spent", spent);
        // Cache-only read (see getPriceIfCached doc) -- never blocks, so safe on any thread this
        // is called from. Captures the live market context an offer was placed/repriced against,
        // which can't be reconstructed retroactively (the wiki only exposes current prices).
        WikiPriceService.PriceData market = wikiPriceService.getPriceIfCached(itemId);
        r.put("market_high", market != null ? market.high : null);
        r.put("market_low", market != null ? market.low : null);
        write("ge_offer", r);
    }

    /**
     * Compact flip-panel decision: what we showed and whether it was skipped, acted,
     * aborted (engine), or ignored. Hashed acct like {@code ge_offer}. No bank, XP,
     * chat, or raw RSN. {@code outcome} is shown | skip | abort | acted | ignored.
     * {@code kind} is buy | sell | abort | modify | wait | skip.
     */
    public void logSuggestionDecision(String rsn, com.runeassist.flip.model.Suggestion s,
                                      String outcome, String source)
    {
        if (!enabled() || s == null) return;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("v", SCHEMA_VERSION);
        r.put("type", "suggestion_decision");
        r.put("ts", System.currentTimeMillis());
        r.put("acct", rsn != null && !rsn.isEmpty() ? acctHash(rsn)
            : (lastHash != null ? lastHash : "anon"));
        String kind = "skip".equals(outcome) ? "skip" : suggestionKind(s);
        r.put("kind", kind);
        r.put("outcome", outcome == null || outcome.isEmpty() ? "shown" : outcome);
        int itemId = s.getItemId() > 0 ? s.getItemId() : s.getId();
        if (itemId > 0) r.put("item_id", itemId);
        if (s.getPrice() > 0) r.put("price", s.getPrice());
        if (s.getQuantity() > 0) r.put("qty", s.getQuantity());
        int slot = s.getBoxId();
        if (slot >= 0) r.put("slot", slot);
        String why = s.getWhy();
        if (why == null || why.isEmpty()) why = s.getMessage();
        if (why != null && !why.isEmpty())
        {
            if (why.length() > 200) why = why.substring(0, 200);
            r.put("why", why);
        }
        String src = source != null && !source.isEmpty() ? source : s.getPickSource();
        r.put("source", src != null && !src.isEmpty() ? src : "local");
        write("suggestion_decision", r);
    }

    private static String suggestionKind(com.runeassist.flip.model.Suggestion s)
    {
        if (s == null || s.getType() == null) return "wait";
        switch (s.getType())
        {
            case BUY: return "buy";
            case SELL: return "sell";
            case ABORT: return "abort";
            case MODIFY_BUY:
            case MODIFY_SELL: return "modify";
            case WAIT:
            default: return "wait";
        }
    }

    /**
     * Backfill completed GE history rows for fill-model training. Always persists locally
     * (same JSONL layout as ingest). Uploads only when contribution is on and an
     * endpoint is set (Flipping Privacy keys preferred). Dedupes reopen/login so the same item/qty/price/side/occurrence is
     * not multiplied. Display-only — never places or cancels offers.
     *
     * @param newestFirst widget order (newest completed offer first)
     * @param capturedAtSec epoch seconds when this history panel session opened
     */
    public void logGeHistory(String rsn, List<GeHistoryFill> newestFirst, int capturedAtSec)
    {
        if (newestFirst == null || newestFirst.isEmpty()) return;
        final String acct = acctHash(rsn);
        final List<GeHistoryFill> rows = new ArrayList<>(newestFirst);
        final int captured = capturedAtSec > 0 ? capturedAtSec : (int) (System.currentTimeMillis() / 1000L);
        executor.submit(() -> applyHistoryDump(acct, rows, captured));
    }

    /** Re-scan local ge_history JSONL for upload when the player later opts in. */
    public void onUploadSettingsChanged()
    {
        executor.submit(() -> {
            historyLocalScanned = false;
            if (uploadEnabled()) scanLocalHistoryForUpload();
        });
    }

    /**
     * The RuneAssist advice-&gt;outcome loop: what was asked, which tools fired, and the
     * cost. Joined offline to the surrounding account_snapshot records (by time + acct) to
     * see whether the advice was taken and whether it helped. Uses the last-known account
     * hash (captured on the client thread by xp/snapshot events), so the caller -- the chat
     * panel on the EDT -- needs no Client access. The question is the user's own input and
     * stays local like everything else here.
     */
    public void logAdvice(String question, java.util.List<String> tools, String provider,
                          int inTokens, int outTokens, int answerChars)
    {
        if (!enabled()) return;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("v", SCHEMA_VERSION);
        r.put("type", "advice");
        r.put("ts", System.currentTimeMillis());
        r.put("acct", lastHash != null ? lastHash : "anon");
        r.put("question", question);
        r.put("tools", tools != null ? tools : java.util.Collections.emptyList());
        r.put("provider", provider);
        r.put("in_tokens", inTokens);
        r.put("out_tokens", outTokens);
        r.put("answer_chars", answerChars);
        write("advice", r);
    }

    public void shutdown()
    {
        try { flushUploads(); } catch (Exception ignored) {}
        uploader.shutdownNow();
        executor.shutdownNow();
    }

    // ── UPLOAD (optional, opt-in, endpoint-gated) ──────────────────────────────

    private boolean uploadEnabled()
    {
        return enabled() && contributionEndpoint() != null;
    }

    private void enqueueUpload(String type, Map<String, Object> record)
    {
        if (!uploadEnabled() || !UPLOAD_TYPES.contains(type)) return;
        synchronized (uploadQueue)
        {
            uploadQueue.add(record);
            while (uploadQueue.size() > QUEUE_CAP) uploadQueue.remove(0); // bounded
            if (uploadQueue.size() >= BATCH_MAX)
                uploader.submit(this::flushUploads);
        }
    }

    /** POST the queued batch as a JSON array. Runs on the uploader thread; never throws. */
    private void flushUploads()
    {
        if (!uploadEnabled()) return;
        List<Map<String, Object>> batch;
        synchronized (uploadQueue)
        {
            if (uploadQueue.isEmpty()) return;
            batch = new ArrayList<>(uploadQueue);
            uploadQueue.clear();
        }
        String endpoint = contributionEndpoint();
        if (endpoint == null)
        {
            requeue(batch);
            return;
        }
        String token    = contributionToken();
        try
        {
            Request.Builder req = new Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json")
                .header("X-Schema-Version", String.valueOf(SCHEMA_VERSION))
                .header("User-Agent", UPLOAD_UA)
                .post(RequestBody.create(JSON, gson.toJson(batch)));
            if (token != null && !token.trim().isEmpty())
            {
                req.header("Authorization", "Bearer " + token.trim());
            }
            try (Response r = httpClient.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
                .newCall(req.build())
                .execute())
            {
                int code = r.code();
                if (code < 200 || code >= 300)
                {
                    if (code == 401)
                    {
                        log.warn("Telemetry upload HTTP 401 — hosted ingest rejected the contribute key");
                    }
                    else
                    {
                        log.warn("Telemetry upload failed HTTP {}; re-queueing {} records", code, batch.size());
                    }
                    requeue(batch);
                }
                else
                {
                    markHistoryUploaded(batch);
                }
            }
        }
        catch (Exception e)
        {
            // Network hiccup: put the batch back so it retries on the next flush.
            log.warn("Telemetry upload error ({}); re-queueing {} records", e.getMessage(), batch.size());
            requeue(batch);
        }
    }

    private void requeue(List<Map<String, Object>> batch)
    {
        synchronized (uploadQueue)
        {
            uploadQueue.addAll(0, batch);
            while (uploadQueue.size() > QUEUE_CAP) uploadQueue.remove(0);
        }
    }

    // ── ANONYMISATION ─────────────────────────────────────────────────────────

    private synchronized String acctHash(String rsn)
    {
        if (rsn == null || rsn.trim().isEmpty()) return "anon";
        String norm = rsn.toLowerCase(Locale.ROOT).trim();
        if (norm.equals(lastRsn) && lastHash != null) return lastHash;
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(norm.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest)
                sb.append(Character.forDigit((b >> 4) & 0xf, 16))
                  .append(Character.forDigit(b & 0xf, 16));
            lastRsn = norm;
            lastHash = sb.toString();
            return lastHash;
        }
        catch (NoSuchAlgorithmException e)
        {
            log.warn("Telemetry: SHA-256 unavailable; logging as 'anon'", e);
            return "anon";
        }
    }

    // ── GE HISTORY DUMP ───────────────────────────────────────────────────────

    private static final class HistoryCursor
    {
        Map<String, List<String>> snapshots = new LinkedHashMap<>();
        Set<String> written = new LinkedHashSet<>();
        Set<String> uploaded = new LinkedHashSet<>();
    }

    private synchronized void applyHistoryDump(String acct, List<GeHistoryFill> newestFirst, int capturedAtSec)
    {
        try
        {
            HistoryCursor cursor = loadHistoryCursor();
            int n = newestFirst.size();
            int[] occ = new int[n];
            Map<String, Integer> seenBase = new HashMap<>();
            for (int i = n - 1; i >= 0; i--)
            {
                GeHistoryFill row = newestFirst.get(i);
                String base = row.itemId + "|" + row.qty + "|" + row.price + "|" + (row.buy ? "b" : "s");
                int k = seenBase.getOrDefault(base, 0);
                occ[i] = k;
                seenBase.put(base, k + 1);
            }
            List<String> nowFps = new ArrayList<>(n);
            List<Map<String, Object>> records = new ArrayList<>(n);
            long dumpTs = System.currentTimeMillis();
            for (int i = 0; i < n; i++)
            {
                GeHistoryFill row = newestFirst.get(i);
                nowFps.add(row.finger(occ[i]));
                records.add(historyRecord(acct, row, occ[i], dumpTs, capturedAtSec));
            }
            List<String> last = cursor.snapshots.getOrDefault(acct, Collections.emptyList());
            int newPrefix = newPrefixLen(nowFps, last);
            int wrote = 0;
            for (int i = 0; i < newPrefix; i++)
            {
                Map<String, Object> rec = records.get(i);
                String wkey = historyKey(rec);
                if (cursor.written.contains(wkey)) continue;
                persist(HISTORY_TYPE, rec);
                remember(cursor.written, wkey);
                wrote++;
                if (uploadEnabled()) enqueueUpload(HISTORY_TYPE, rec);
            }
            cursor.snapshots.put(acct, nowFps);
            saveHistoryCursor(cursor);
            if (wrote > 0)
            {
                log.debug("GE history dump: wrote {} new local row(s) (window {})", wrote, n);
            }
        }
        catch (Exception e)
        {
            log.warn("Telemetry: GE history dump failed: {}", e.getMessage());
        }
    }

    private Map<String, Object> historyRecord(String acct, GeHistoryFill row, int seq,
                                              long dumpTs, int capturedAtSec)
    {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("v", SCHEMA_VERSION);
        r.put("type", HISTORY_TYPE);
        r.put("ts", row.fillTs != null ? row.fillTs : dumpTs);
        r.put("acct", acct);
        r.put("item_id", row.itemId);
        r.put("qty", row.qty);
        r.put("price", row.price);
        r.put("side", row.buy ? "buy" : "sell");
        r.put("source", HISTORY_TYPE);
        r.put("seq", seq);
        r.put("captured_at", capturedAtSec);
        r.put("spent", row.price * (long) row.qty);
        if (row.name != null && !row.name.isEmpty()) r.put("name", row.name);
        if (row.fillTs != null) r.put("fill_ts", row.fillTs);
        return r;
    }

    private static String historyKey(Map<String, Object> rec)
    {
        return String.valueOf(rec.get("acct")) + "|"
            + num(rec.get("item_id")) + "|"
            + num(rec.get("qty")) + "|"
            + num(rec.get("price")) + "|"
            + rec.get("side") + "|"
            + num(rec.get("seq"));
    }

    /** Gson Map.class turns numbers into Doubles; keep keys stable vs live Long/Integer. */
    private static String num(Object o)
    {
        if (o instanceof Number) return Long.toString(((Number) o).longValue());
        if (o == null) return "0";
        String s = String.valueOf(o);
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        return s;
    }

    /** Longest new prefix of {@code now} that is not a continuation of {@code last} (newest-first). */
    static int newPrefixLen(List<String> now, List<String> last)
    {
        if (now == null || now.isEmpty()) return 0;
        if (last == null || last.isEmpty()) return now.size();
        for (int i = 0; i < now.size(); i++)
        {
            if (isPrefix(now.subList(i, now.size()), last)) return i;
        }
        return now.size();
    }

    private static boolean isPrefix(List<String> a, List<String> last)
    {
        int n = Math.min(a.size(), last.size());
        if (n == 0) return false;
        for (int i = 0; i < n; i++)
        {
            if (!a.get(i).equals(last.get(i))) return false;
        }
        return true;
    }

    private synchronized void scanLocalHistoryForUpload()
    {
        if (historyLocalScanned || !uploadEnabled()) return;
        historyLocalScanned = true;
        HistoryCursor cursor = loadHistoryCursor();
        File[] files = baseDir.listFiles((d, name) ->
            name != null && name.startsWith(HISTORY_TYPE + "-") && name.endsWith(".jsonl"));
        if (files == null) return;
        int filesScanned = 0;
        for (File file : files)
        {
            try (java.io.BufferedReader br = java.nio.file.Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
            {
                String line;
                while ((line = br.readLine()) != null)
                {
                    if (line.trim().isEmpty()) continue;
                    try
                    {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> rec = gson.fromJson(line, Map.class);
                        if (rec == null || !HISTORY_TYPE.equals(rec.get("type"))) continue;
                        String ukey = historyKey(rec);
                        if (cursor.uploaded.contains(ukey)) continue;
                        enqueueUpload(HISTORY_TYPE, rec);
                    }
                    catch (Exception ignored) {}
                }
                filesScanned++;
            }
            catch (IOException e)
            {
                log.warn("Telemetry: could not scan {}: {}", file.getName(), e.getMessage());
            }
        }
        if (filesScanned > 0)
        {
            log.debug("GE history dump: scanned {} local file(s) for upload", filesScanned);
        }
    }

    private synchronized void markHistoryUploaded(List<Map<String, Object>> batch)
    {
        boolean dirty = false;
        HistoryCursor cursor = loadHistoryCursor();
        for (Map<String, Object> rec : batch)
        {
            if (rec == null || !HISTORY_TYPE.equals(rec.get("type"))) continue;
            if (remember(cursor.uploaded, historyKey(rec))) dirty = true;
        }
        if (dirty) saveHistoryCursor(cursor);
    }

    private static boolean remember(Set<String> set, String key)
    {
        boolean added = set.add(key);
        while (set.size() > CURSOR_CAP)
        {
            Iterator<String> it = set.iterator();
            if (!it.hasNext()) break;
            it.next();
            it.remove();
        }
        return added;
    }

    private synchronized HistoryCursor loadHistoryCursor()
    {
        if (historyCursor != null) return historyCursor;
        historyCursor = new HistoryCursor();
        File file = new File(baseDir, CURSOR_FILE);
        if (!file.exists()) return historyCursor;
        try
        {
            String txt = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = gson.fromJson(txt, Map.class);
            if (raw == null) return historyCursor;
            Object snaps = raw.get("snapshots");
            if (snaps instanceof Map)
            {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) snaps).entrySet())
                {
                    if (e.getKey() == null || !(e.getValue() instanceof List)) continue;
                    List<String> fps = new ArrayList<>();
                    for (Object o : (List<?>) e.getValue())
                    {
                        if (o != null) fps.add(String.valueOf(o));
                    }
                    historyCursor.snapshots.put(String.valueOf(e.getKey()), fps);
                }
            }
            loadKeySet(raw.get("written"), historyCursor.written);
            loadKeySet(raw.get("uploaded"), historyCursor.uploaded);
        }
        catch (Exception e)
        {
            log.warn("Telemetry: could not load GE history cursor: {}", e.getMessage());
        }
        return historyCursor;
    }

    private static void loadKeySet(Object raw, Set<String> dest)
    {
        if (!(raw instanceof List)) return;
        for (Object o : (List<?>) raw)
        {
            if (o != null) dest.add(String.valueOf(o));
        }
    }

    private synchronized void saveHistoryCursor(HistoryCursor cursor)
    {
        try
        {
            if (!baseDir.exists() && !baseDir.mkdirs())
            {
                log.warn("Telemetry: could not create directory: {}", baseDir);
                return;
            }
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("snapshots", cursor.snapshots);
            raw.put("written", new ArrayList<>(cursor.written));
            raw.put("uploaded", new ArrayList<>(cursor.uploaded));
            File file = new File(baseDir, CURSOR_FILE);
            java.nio.file.Files.write(file.toPath(), gson.toJson(raw).getBytes(StandardCharsets.UTF_8));
        }
        catch (Exception e)
        {
            log.warn("Telemetry: could not save GE history cursor: {}", e.getMessage());
        }
    }

    // ── IO ────────────────────────────────────────────────────────────────────

    private void persist(String type, Map<String, Object> record)
    {
        try
        {
            if (!baseDir.exists() && !baseDir.mkdirs())
            {
                log.warn("Telemetry: could not create directory: {}", baseDir);
                return;
            }
            String day = LocalDate.now(ZoneOffset.UTC).format(DAY_FMT);
            File file = new File(baseDir, type + "-" + day + ".jsonl");
            try (BufferedWriter w = new BufferedWriter(
                     new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)))
            {
                w.write(gson.toJson(record));
                w.write("\n");
            }
        }
        catch (IOException | RuntimeException e)
        {
            log.warn("Telemetry: failed to write {} record: {}", type, e.getMessage());
        }
    }

    private void write(String type, Map<String, Object> record)
    {
        enqueueUpload(type, record);
        executor.submit(() -> persist(type, record));
    }
}
