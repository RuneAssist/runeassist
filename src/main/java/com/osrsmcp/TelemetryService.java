package com.osrsmcp;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * OPT-IN, anonymised, LOCAL-ONLY telemetry logger.
 *
 * Writes versioned JSONL to a per-day file under ~/.runelite/runeassist/telemetry/
 * so account data (real XP/hr, GE performance, account trajectories) can be analysed
 * later. It is a no-op unless the user explicitly enables {@code shareTelemetry}
 * (default OFF).
 *
 * <p>Local files are always the primary sink. Records are ALSO uploaded to a hosted
 * ingest endpoint only when the user both enables telemetry AND sets a
 * {@code telemetryEndpoint} — batched, retried, off-thread. Uploads are limited to
 * {@link #UPLOAD_TYPES} (GE/XP/account); the {@code advice} record, which holds the
 * player's raw chat question, is NEVER uploaded.
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

    private final File baseDir;

    @Inject private OsrsMcpConfig config;
    @Inject private Gson gson;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "runeassist-telemetry");
        t.setDaemon(true);
        return t;
    });

    // ── optional upload (the hosted ingest flywheel; opt-in + endpoint required) ──
    // Only these record types are ever uploaded. Notably "advice" is excluded — it
    // contains the player's raw chat question, which never leaves the PC.
    private static final Set<String> UPLOAD_TYPES =
        new HashSet<>(Arrays.asList("ge_offer", "account_snapshot", "xp_gain"));
    private static final int  BATCH_MAX     = 50;   // flush when this many queued
    private static final long FLUSH_EVERY_S = 60;   // or at least this often
    private static final int  QUEUE_CAP     = 2000; // drop oldest beyond this (bounded memory)

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

    public TelemetryService()
    {
        baseDir = new File(RuneLite.RUNELITE_DIR, "runeassist/telemetry");
    }

    private boolean enabled()
    {
        return config != null && config.shareTelemetry();
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
        write("ge_offer", r);
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
        uploader.shutdown();
        executor.shutdown();
    }

    // ── UPLOAD (optional, opt-in, endpoint-gated) ──────────────────────────────

    private boolean uploadEnabled()
    {
        return enabled() && config != null
            && config.telemetryEndpoint() != null && !config.telemetryEndpoint().trim().isEmpty();
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
        String endpoint = config.telemetryEndpoint().trim();
        String token    = config.telemetryToken();
        try
        {
            byte[] body = gson.toJson(batch).getBytes(StandardCharsets.UTF_8);
            HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-Schema-Version", String.valueOf(SCHEMA_VERSION));
            if (token != null && !token.trim().isEmpty())
                c.setRequestProperty("Authorization", "Bearer " + token.trim());
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
            int code = c.getResponseCode();
            c.disconnect();
            if (code < 200 || code >= 300)
            {
                log.warn("Telemetry upload failed HTTP {}; re-queueing {} records", code, batch.size());
                requeue(batch);
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

    // ── IO ────────────────────────────────────────────────────────────────────

    private void write(String type, Map<String, Object> record)
    {
        enqueueUpload(type, record);
        executor.submit(() -> {
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
                // Telemetry must NEVER throw into a caller.
                log.warn("Telemetry: failed to write {} record: {}", type, e.getMessage());
            }
        });
    }
}
