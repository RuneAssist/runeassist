package com.osrsmcp;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Local flip/profit tracker — the Flipping-Copilot-style bookkeeping, done offline for the
 * logged-in account. Watches Grand Exchange offer changes, detects newly-filled units by
 * the change in {@code quantitySold}/{@code spent} per slot, matches sells against buys FIFO
 * and books realized profit after the 2% GE tax (5M cap). Also tracks buy-limit usage in a
 * rolling 4-hour window so suggestions can show what's left.
 *
 * <p>Everything is display-only advice: it records what the player actually did on the GE and
 * reports it back. It never places, cancels or edits an offer. Persisted in RuneLite config
 * (key {@code flips}) so it survives restarts; per-slot cumulative state is persisted too, so
 * a relog re-emitting existing offers does not double-count (deltas come out as zero).
 *
 * <p>Called from {@code onGrandExchangeOfferChanged} on the client thread; reads for the UI go
 * through {@link #snapshot()} which copies under the lock and touches no client state.
 */
@Slf4j
@Singleton
public class FlipTrackerService
{
    private static final long   LIMIT_WINDOW_MS = 4L * 60 * 60 * 1000; // GE buy limit resets every 4h
    private static final int    MAX_FLIPS = 200;

    @Inject private ConfigManager configManager;
    @Inject private Gson gson;
    @Inject private WikiPriceService wikiPriceService;
    @Inject private OsrsMcpConfig config;

    // Cross-device sync (phase 2): the account's hashed rsn, and a debounce clock.
    private volatile String accountHash = null;
    private volatile long lastSyncMs = 0;
    private static final long SYNC_MIN_GAP_MS = 15_000;

    /** An open buy lot awaiting a matching sell (FIFO cost basis). */
    private static final class Lot { int qty; long unit; long time;
        Lot(int q, long u, long t){ qty=q; unit=u; time=t; } }

    /** Per-slot cumulative counters, to turn absolute offer state into per-event deltas. */
    private static final class Slot { int itemId; boolean buy; int qty; long spent;
        Slot(int i, boolean b, int q, long s){ itemId=i; buy=b; qty=q; spent=s; } }

    private final Map<Integer, Deque<Lot>> positions = new LinkedHashMap<>(); // itemId -> buy lots
    private final Map<Integer, List<long[]>> limitBuys = new LinkedHashMap<>(); // itemId -> [qty,time]
    private final Map<Integer, Slot> slots = new LinkedHashMap<>();             // slot -> cumulative
    private final List<Map<String, Object>> flips = new ArrayList<>();          // completed, newest last
    private long allTimeProfit = 0;

    private final long sessionStart = System.currentTimeMillis();
    private boolean loaded = false;

    // ── persistence ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private synchronized void ensureLoaded()
    {
        if (loaded) return;
        loaded = true;
        try
        {
            String json = configManager.getConfiguration("osrsmcp", "flips");
            if (json == null || json.isEmpty()) return;
            Map<String, Object> saved = gson.fromJson(json,
                new TypeToken<Map<String, Object>>(){}.getType());
            if (saved == null) return;

            if (saved.get("allTimeProfit") instanceof Number)
                allTimeProfit = ((Number) saved.get("allTimeProfit")).longValue();

            List<Map<String, Object>> f = (List<Map<String, Object>>) saved.get("flips");
            if (f != null) flips.addAll(f);

            Map<String, Object> pos = (Map<String, Object>) saved.get("positions");
            if (pos != null) for (Map.Entry<String, Object> e : pos.entrySet())
            {
                Deque<Lot> q = new ArrayDeque<>();
                for (Object o : (List<Object>) e.getValue())
                {
                    List<Object> a = (List<Object>) o;
                    q.add(new Lot(((Number) a.get(0)).intValue(),
                        ((Number) a.get(1)).longValue(), ((Number) a.get(2)).longValue()));
                }
                positions.put(Integer.parseInt(e.getKey()), q);
            }

            Map<String, Object> lim = (Map<String, Object>) saved.get("limitBuys");
            if (lim != null) for (Map.Entry<String, Object> e : lim.entrySet())
            {
                List<long[]> list = new ArrayList<>();
                for (Object o : (List<Object>) e.getValue())
                {
                    List<Object> a = (List<Object>) o;
                    list.add(new long[]{ ((Number) a.get(0)).longValue(), ((Number) a.get(1)).longValue() });
                }
                limitBuys.put(Integer.parseInt(e.getKey()), list);
            }

            Map<String, Object> sl = (Map<String, Object>) saved.get("slots");
            if (sl != null) for (Map.Entry<String, Object> e : sl.entrySet())
            {
                List<Object> a = (List<Object>) e.getValue();
                slots.put(Integer.parseInt(e.getKey()), new Slot(
                    ((Number) a.get(0)).intValue(), Boolean.TRUE.equals(a.get(1)),
                    ((Number) a.get(2)).intValue(), ((Number) a.get(3)).longValue()));
            }
        }
        catch (Exception e) { log.warn("Flip load failed: {}", e.getMessage()); }
    }

    private void save()
    {
        try
        {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("allTimeProfit", allTimeProfit);
            out.put("flips", flips);

            Map<String, Object> pos = new LinkedHashMap<>();
            for (Map.Entry<Integer, Deque<Lot>> e : positions.entrySet())
            {
                List<long[]> list = new ArrayList<>();
                for (Lot l : e.getValue()) list.add(new long[]{ l.qty, l.unit, l.time });
                if (!list.isEmpty()) pos.put(String.valueOf(e.getKey()), list);
            }
            out.put("positions", pos);

            Map<String, Object> lim = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<long[]>> e : limitBuys.entrySet())
                if (!e.getValue().isEmpty()) lim.put(String.valueOf(e.getKey()), e.getValue());
            out.put("limitBuys", lim);

            Map<String, Object> sl = new LinkedHashMap<>();
            for (Map.Entry<Integer, Slot> e : slots.entrySet())
            {
                Slot s = e.getValue();
                sl.put(String.valueOf(e.getKey()), new Object[]{ s.itemId, s.buy, s.qty, s.spent });
            }
            out.put("slots", sl);

            configManager.setConfiguration("osrsmcp", "flips", gson.toJson(out));
        }
        catch (Exception e) { log.warn("Flip save failed: {}", e.getMessage()); }
    }

    // ── ingest ──────────────────────────────────────────────────────────────────

    /** Feed a GE offer change. Call on the client thread (from onGrandExchangeOfferChanged). */
    public synchronized void onOffer(int slot, GrandExchangeOfferState state, int itemId,
                                     int price, int totalQty, int qtySold, int spent)
    {
        ensureLoaded();
        if (state == null || state == GrandExchangeOfferState.EMPTY || itemId <= 0) return;
        boolean buy = isBuy(state);

        Slot prev = slots.get(slot);
        int  baseQty = 0; long baseSpent = 0;
        // Same offer still running in this slot? Continue from where we were. A new offer
        // (different item, or counters reset lower) starts a fresh delta from zero.
        if (prev != null && prev.itemId == itemId && prev.buy == buy
            && qtySold >= prev.qty && spent >= prev.spent)
        {
            baseQty = prev.qty; baseSpent = prev.spent;
        }

        int  dQty   = qtySold - baseQty;
        long dSpent = spent - baseSpent;
        slots.put(slot, new Slot(itemId, buy, qtySold, spent));

        if (dQty > 0)
        {
            long unit = dSpent > 0 ? Math.max(1, dSpent / dQty) : price;
            if (buy) recordBuy(itemId, dQty, unit);
            else     recordSell(itemId, dQty, unit);
        }
        save();
    }

    private void recordBuy(int itemId, int qty, long unit)
    {
        positions.computeIfAbsent(itemId, k -> new ArrayDeque<>()).add(new Lot(qty, unit, now()));
        limitBuys.computeIfAbsent(itemId, k -> new ArrayList<>()).add(new long[]{ qty, now() });
    }

    private void recordSell(int itemId, int qty, long sellUnit)
    {
        long taxUnit = GeTax.taxAmount(itemId, sellUnit);
        Deque<Lot> lots = positions.get(itemId);
        int remaining = qty;
        long matchedQty = 0, costSum = 0;

        while (remaining > 0 && lots != null && !lots.isEmpty())
        {
            Lot lot = lots.peekFirst();
            int take = Math.min(remaining, lot.qty);
            matchedQty += take;
            costSum    += take * lot.unit;
            lot.qty    -= take;
            remaining  -= take;
            if (lot.qty == 0) lots.pollFirst();
        }

        if (matchedQty > 0)
        {
            long avgBuy = costSum / matchedQty;
            long profit = matchedQty * (sellUnit - avgBuy - taxUnit);
            allTimeProfit += profit;

            Map<String, Object> f = new LinkedHashMap<>();
            f.put("item_id", itemId);
            f.put("name", nameOf(itemId));
            f.put("qty", matchedQty);
            f.put("buy_at", avgBuy);
            f.put("sell_at", sellUnit);
            f.put("tax", matchedQty * taxUnit);
            f.put("profit", profit);
            f.put("time", now());
            flips.add(f);
            while (flips.size() > MAX_FLIPS) flips.remove(0);
            syncAsync(); // push the new flip to the cross-device store (no-op if disabled)
        }
        // Unmatched sell units (sold something bought outside the tracker): no cost basis,
        // so we don't invent a profit for them — they're simply not booked.
    }

    // ── reads (UI / MCP) ────────────────────────────────────────────────────────

    /** Client-free snapshot for the panel/EDT and MCP: totals + recent flips + open positions. */
    public synchronized Map<String, Object> snapshot()
    {
        ensureLoaded();
        long session = 0;
        for (Map<String, Object> f : flips)
            if (((Number) f.get("time")).longValue() >= sessionStart)
                session += ((Number) f.get("profit")).longValue();

        List<Map<String, Object>> recent = new ArrayList<>();
        for (int i = flips.size() - 1; i >= 0 && recent.size() < 25; i--)
            recent.add(new LinkedHashMap<>(flips.get(i)));

        List<Map<String, Object>> open = new ArrayList<>();
        for (Map.Entry<Integer, Deque<Lot>> e : positions.entrySet())
        {
            int qty = 0; long cost = 0, since = Long.MAX_VALUE;
            for (Lot l : e.getValue()) { qty += l.qty; cost += (long) l.qty * l.unit; since = Math.min(since, l.time); }
            if (qty <= 0) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("item_id", e.getKey());
            p.put("name", nameOf(e.getKey()));
            p.put("qty", qty);
            p.put("avg_buy", cost / qty);
            p.put("since", since == Long.MAX_VALUE ? now() : since); // earliest lot = time held
            open.add(p);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session_profit", session);
        out.put("all_time_profit", allTimeProfit);
        out.put("flips_count", flips.size());
        out.put("recent", recent);
        out.put("open_positions", open);
        return out;
    }

    /** Units of {@code itemId} bought in the last 4h (for buy-limit remaining). Client-free. */
    public synchronized int boughtInWindow(int itemId)
    {
        ensureLoaded();
        List<long[]> list = limitBuys.get(itemId);
        if (list == null) return 0;
        long cutoff = now() - LIMIT_WINDOW_MS;
        int sum = 0;
        for (long[] a : list) if (a[1] >= cutoff) sum += a[0];
        return sum;
    }

    /** Remaining buy-limit for an item given its 4h GE limit, or -1 if the limit is unknown. */
    public int limitRemaining(int itemId, int geLimit)
    {
        if (geLimit <= 0) return -1;
        return Math.max(0, geLimit - boughtInWindow(itemId));
    }

    /** Clear all recorded flips/positions/limits (a "reset session" action). */
    public synchronized void reset()
    {
        positions.clear(); limitBuys.clear(); slots.clear(); flips.clear();
        allTimeProfit = 0;
        save();
    }

    // ── cross-device sync (phase 2) ───────────────────────────────────────────────

    /** Set the current account (raw rsn); hashed for the sync key and to trigger a login pull. */
    public void setAccount(String rsn)
    {
        String h = hashRsn(rsn);
        boolean changed = h != null && !h.equals(accountHash);
        accountHash = h;
        if (changed) syncAsync(); // pull this account's history on login / account switch
    }

    private boolean syncEnabled()
    {
        return config != null && config.syncFlips()
            && accountHash != null
            && config.telemetryEndpoint() != null && !config.telemetryEndpoint().trim().isEmpty();
    }

    /** Fire a background sync if enabled and not rate-limited. Safe to call from any thread. */
    public void syncAsync()
    {
        if (!syncEnabled()) return;
        long now = System.currentTimeMillis();
        synchronized (this)
        {
            if (now - lastSyncMs < SYNC_MIN_GAP_MS) return;
            lastSyncMs = now;
        }
        new Thread(this::syncNow, "runeassist-flip-sync").start();
    }

    /** POST local flips, adopt the merged history the server returns. Never throws. */
    @SuppressWarnings("unchecked")
    private void syncNow()
    {
        if (!syncEnabled()) return;
        String url = syncUrl(config.telemetryEndpoint().trim());
        if (url == null) return;

        List<Map<String, Object>> localFlips;
        synchronized (this) { ensureLoaded(); localFlips = new ArrayList<>(flips); }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("account", accountHash);
        payload.put("flips", localFlips);

        try
        {
            byte[] body = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            String token = config.telemetryToken();
            if (token != null && !token.trim().isEmpty())
                c.setRequestProperty("Authorization", "Bearer " + token.trim());
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) { log.warn("Flip sync HTTP {}", code); c.disconnect(); return; }

            Map<String, Object> resp = gson.fromJson(
                new java.io.InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8),
                new TypeToken<Map<String, Object>>(){}.getType());
            c.disconnect();
            if (resp != null && resp.get("flips") instanceof List)
                adoptMerged((List<Map<String, Object>>) resp.get("flips"));
        }
        catch (Exception e) { log.warn("Flip sync error: {}", e.getMessage()); }
    }

    /** Replace the local completed-flip log with the merged server copy; recompute all-time. */
    private synchronized void adoptMerged(List<Map<String, Object>> merged)
    {
        if (merged == null) return;
        flips.clear();
        flips.addAll(merged);
        while (flips.size() > MAX_FLIPS) flips.remove(0);
        long total = 0;
        for (Map<String, Object> f : flips)
        {
            Object p = f.get("profit");
            if (p instanceof Number) total += ((Number) p).longValue();
        }
        allTimeProfit = total;
        save();
    }

    /** Derive the sync URL from the ingest endpoint's origin (…/v1/ingest -> …/v1/flips/sync). */
    private static String syncUrl(String endpoint)
    {
        try
        {
            URL u = new URL(endpoint);
            String base = u.getProtocol() + "://" + u.getHost()
                + (u.getPort() > 0 ? ":" + u.getPort() : "");
            return base + "/v1/flips/sync";
        }
        catch (Exception e) { return null; }
    }

    private static String hashRsn(String rsn)
    {
        if (rsn == null || rsn.trim().isEmpty()) return null;
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(rsn.toLowerCase(Locale.ROOT).trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d)
                sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return sb.toString();
        }
        catch (Exception e) { return null; }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static boolean isBuy(GrandExchangeOfferState st)
    {
        return st == GrandExchangeOfferState.BUYING
            || st == GrandExchangeOfferState.BOUGHT
            || st == GrandExchangeOfferState.CANCELLED_BUY;
    }

    private String nameOf(int itemId)
    {
        try { WikiPriceService.ItemMeta m = wikiPriceService.getMeta(itemId);
              if (m != null && m.name != null) return m.name; }
        catch (Exception ignored) {}
        return "item " + itemId;
    }

    private static long now() { return System.currentTimeMillis(); }
}
