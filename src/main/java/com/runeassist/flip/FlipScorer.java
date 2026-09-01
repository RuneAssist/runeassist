package com.runeassist.flip;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Self-contained market flip scorer for the RuneAssist flipping plugin. Fetches OSRS-wiki
 * prices/volumes/mapping itself (no dependency on any other plugin) and ranks flips by
 * margin-after-tax × liquidity, penalised for risk — the same scoring RuneAssist uses. Called
 * off the client thread (blocks on HTTP); results feed {@link LocalSuggestionEngine}.
 */
@Slf4j
@Singleton
public class FlipScorer
{
    private static final String UA = "RuneAssist-flip/1.0 (github.com/nickbeddows-ctrl/osrs-mcp-plugin)";
    private static final String LATEST = "https://prices.runescape.wiki/api/v1/osrs/latest";
    private static final String HOUR   = "https://prices.runescape.wiki/api/v1/osrs/1h";
    private static final String MAP    = "https://prices.runescape.wiki/api/v1/osrs/mapping";
    private static final long PRICE_TTL = 60_000;

    private static final int MIN_VOLUME = 500;
    private static final int MIN_MARGIN = 20;
    private static final double TAX_RATE = 0.02;
    private static final long   TAX_CAP  = 5_000_000L;
    private static final long   TAX_MAX_PRICE = 250_000_000L;
    // Items exempt from the 2% GE sell tax.
    private static final Set<Integer> TAX_EXEMPT = new HashSet<>(Arrays.asList(
        8011, 365, 2309, 882, 806, 1891, 8010, 1755, 28824, 2140, 2142, 8009, 5325, 1785, 2347,
        347, 884, 807, 28790, 379, 8008, 355, 2327, 558, 1733, 13190, 233, 351, 5341, 2552, 329,
        8794, 5329, 5343, 1735, 315, 952, 886, 808, 8013, 361, 8007, 5331));

    private final OkHttpClient httpClient;
    private final Gson gson;

    // id -> {name, limit}
    private final Map<Integer, Object[]> meta = new ConcurrentHashMap<>();
    private volatile long lastPriceFetch = 0;
    private volatile Map<Integer, long[]> latest = new ConcurrentHashMap<>();   // id -> {high, low}
    private volatile Map<Integer, int[]>  volume = new ConcurrentHashMap<>();   // id -> {highVol, lowVol}

    @Inject
    public FlipScorer(OkHttpClient httpClient, Gson gson)
    {
        this.httpClient = httpClient;
        this.gson = gson;
    }

    /** Up to ~{@code top} flip candidates best-first. Blocks on HTTP; call off the client thread. */
    public List<Map<String, Object>> topFlips(long capital)
    {
        try { ensureLoaded(); }
        catch (Exception e) { log.warn("flip scorer load failed: {}", e.getMessage()); return new ArrayList<>(); }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Integer, Object[]> me : meta.entrySet())
        {
            int id = me.getKey();
            long[] p = latest.get(id);
            int[]  v = volume.get(id);
            if (p == null || v == null) continue;
            long buy = p[1], sell = p[0];               // low = buy, high = sell
            if (buy <= 0 || sell <= 0 || sell <= buy) continue;
            int vol = v[0] + v[1];
            if (vol < MIN_VOLUME) continue;

            long tax = taxAmount(id, sell);
            long margin = sell - buy - tax;
            if (margin < MIN_MARGIN) continue;
            double marginPct = margin * 100.0 / buy;

            int limit = (int) me.getValue()[1];
            int perHour = Math.max(1, Math.min(v[0], v[1]));
            long qtyCap = limit > 0 ? limit : Math.max(1, perHour);
            if (capital > 0) qtyCap = Math.min(qtyCap, capital / buy);
            if (qtyCap < 1) continue;
            double fillHrs = (double) qtyCap / perHour;

            double imbalance  = Math.abs(v[0] - v[1]) / (double) vol;
            double spreadRisk = marginPct > 25 ? 0.5 : 0;
            double liqRisk    = vol < MIN_VOLUME * 4 ? 0.4 : 0;
            double risk       = Math.min(0.9, imbalance * 0.6 + spreadRisk + liqRisk);
            double turnover   = 1.0 / Math.max(0.25, fillHrs);
            long score        = Math.round(margin * qtyCap * turnover * (1 - risk));

            List<String> flags = new ArrayList<>();
            if (imbalance > 0.5) flags.add("one-sided");
            if (spreadRisk > 0)  flags.add("wide-spread");
            if (liqRisk > 0)     flags.add("thin");

            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", id);
            s.put("name", String.valueOf(me.getValue()[0]));
            s.put("buy_at", buy);
            s.put("sell_at", sell);
            s.put("margin_post_tax", margin);
            s.put("margin_pct", Math.round(marginPct * 10) / 10.0);
            s.put("suggested_qty", qtyCap);
            s.put("ge_limit", limit);
            s.put("projected_profit", margin * qtyCap);
            s.put("flags", flags);
            s.put("score", score);
            rows.add(s);
        }
        rows.sort((a, b) -> Long.compare(((Number) b.get("score")).longValue(),
                                         ((Number) a.get("score")).longValue()));
        return rows.size() > 12 ? new ArrayList<>(rows.subList(0, 12)) : rows;
    }

    /** Current sell quote for a held item: {name, sell_at, ge_limit, tax_at_sell}, or null. */
    public Map<String, Object> sellQuote(int itemId)
    {
        try { ensureLoaded(); } catch (Exception e) { return null; }
        long[] p = latest.get(itemId);
        Object[] m = meta.get(itemId);
        if (p == null || p[0] <= 0) return null;   // no sell (high) price
        long sell = p[0];
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("name", m != null ? String.valueOf(m[0]) : ("item " + itemId));
        q.put("sell_at", sell);
        q.put("ge_limit", m != null ? (int) m[1] : 0);
        q.put("tax_at_sell", taxAmount(itemId, sell));
        return q;
    }

    /**
     * Current market quote for ANY item (no margin/volume filtering) — used to price the
     * GE offer-setup screen for an item the suggestion engine didn't propose. Returns
     * {name, buy_at, sell_at, ge_limit} or null if the wiki has no price for this item.
     * Blocks on HTTP (first call / stale cache); call off the client thread.
     */
    public Map<String, Object> quote(int itemId)
    {
        try { ensureLoaded(); } catch (Exception e) { return null; }
        long[] p = latest.get(itemId);
        if (p == null || (p[0] <= 0 && p[1] <= 0)) return null;
        Object[] m = meta.get(itemId);
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("name", m != null ? String.valueOf(m[0]) : ("item " + itemId));
        q.put("buy_at", p[1]);
        q.put("sell_at", p[0]);
        q.put("ge_limit", m != null ? (int) m[1] : 0);
        return q;
    }

    private long taxAmount(int id, long price)
    {
        if (price <= 0 || TAX_EXEMPT.contains(id)) return 0;
        if (price >= TAX_MAX_PRICE) return TAX_CAP;
        return (long) Math.floor(price * TAX_RATE);
    }

    // ── fetch + cache ──────────────────────────────────────────────────────────

    private synchronized void ensureLoaded() throws Exception
    {
        if (meta.isEmpty()) loadMapping();
        if (System.currentTimeMillis() - lastPriceFetch > PRICE_TTL || latest.isEmpty())
        {
            loadLatest();
            loadHour();
            lastPriceFetch = System.currentTimeMillis();
        }
    }

    private void loadMapping() throws Exception
    {
        try (Response r = httpClient.newCall(req(MAP)).execute())
        {
            if (!r.isSuccessful() || r.body() == null) return;
            com.google.gson.JsonArray arr = gson.fromJson(r.body().charStream(), com.google.gson.JsonArray.class);
            for (com.google.gson.JsonElement e : arr)
            {
                JsonObject o = e.getAsJsonObject();
                if (!o.has("id")) continue;
                int id = o.get("id").getAsInt();
                String name = o.has("name") ? o.get("name").getAsString() : ("item " + id);
                int limit = o.has("limit") ? o.get("limit").getAsInt() : 0;
                meta.put(id, new Object[]{ name, limit });
            }
        }
    }

    private void loadLatest() throws Exception
    {
        Map<Integer, long[]> out = new ConcurrentHashMap<>();
        try (Response r = httpClient.newCall(req(LATEST)).execute())
        {
            if (!r.isSuccessful() || r.body() == null) { latest = out; return; }
            JsonObject data = gson.fromJson(r.body().charStream(), JsonObject.class).getAsJsonObject("data");
            for (Map.Entry<String, com.google.gson.JsonElement> e : data.entrySet())
            {
                JsonObject o = e.getValue().getAsJsonObject();
                long high = o.has("high") && !o.get("high").isJsonNull() ? o.get("high").getAsLong() : 0;
                long low  = o.has("low")  && !o.get("low").isJsonNull()  ? o.get("low").getAsLong()  : 0;
                if (high > 0 || low > 0) out.put(Integer.parseInt(e.getKey()), new long[]{ high, low });
            }
        }
        latest = out;
    }

    private void loadHour() throws Exception
    {
        Map<Integer, int[]> out = new ConcurrentHashMap<>();
        try (Response r = httpClient.newCall(req(HOUR)).execute())
        {
            if (!r.isSuccessful() || r.body() == null) { volume = out; return; }
            JsonObject data = gson.fromJson(r.body().charStream(), JsonObject.class).getAsJsonObject("data");
            for (Map.Entry<String, com.google.gson.JsonElement> e : data.entrySet())
            {
                JsonObject o = e.getValue().getAsJsonObject();
                int hv = o.has("highPriceVolume") ? o.get("highPriceVolume").getAsInt() : 0;
                int lv = o.has("lowPriceVolume")  ? o.get("lowPriceVolume").getAsInt()  : 0;
                out.put(Integer.parseInt(e.getKey()), new int[]{ hv, lv });
            }
        }
        volume = out;
    }

    private static Request req(String url)
    {
        return new Request.Builder().url(url).header("User-Agent", UA).build();
    }
}
