package com.runeassist.flip;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.runeassist.flip.model.RiskLevel;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Self-contained market flip scorer for the RuneAssist flipping plugin. Prefers ranked
 * candidates from Ares {@code POST /v1/flips} and falls back to fetching OSRS-wiki
 * prices/volumes/mapping itself if the server is unreachable. Ranks flips by
 * margin-after-tax × liquidity, penalised for risk — the same scoring RuneAssist uses. Called
 * off the client thread (blocks on HTTP); results feed {@link LocalSuggestionEngine}.
 *
 * <p>Quantity is sized to the user's offer-adjust timeframe and the 4h GE buy limit, not
 * "buy the entire limit". Prices prefer 1h (or 5m) averages over last-trade outliers so
 * stale/odd items don't surface as fake high-margin flips.
 */
@Slf4j
@Singleton
public class FlipScorer
{
    private static final String UA = "RuneAssist-flip/1.0 (github.com/nickbeddows-ctrl/osrs-mcp-plugin)";
    private static final String ARES_FLIPS = "https://runeassist.ares-server.co.uk/v1/flips";
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final Type CANDIDATE_LIST = new TypeToken<List<Map<String, Object>>>(){}.getType();
    private static final String LATEST = "https://prices.runescape.wiki/api/v1/osrs/latest";
    private static final String HOUR   = "https://prices.runescape.wiki/api/v1/osrs/1h";
    private static final String FIVE   = "https://prices.runescape.wiki/api/v1/osrs/5m";
    private static final String MAP    = "https://prices.runescape.wiki/api/v1/osrs/mapping";
    private static final long PRICE_TTL = 60_000;
    private static final long STALE_TRADE_SEC = 90 * 60; // last-trade older than this is ignored

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

    private static final class Meta
    {
        final String name;
        final int limit;
        final boolean members;
        Meta(String name, int limit, boolean members)
        {
            this.name = name;
            this.limit = limit;
            this.members = members;
        }
    }

    private final Map<Integer, Meta> meta = new ConcurrentHashMap<>();
    private volatile long lastPriceFetch = 0;
    // id -> {high, low, highTime, lowTime}
    private volatile Map<Integer, long[]> latest = new ConcurrentHashMap<>();
    // id -> {highVol, lowVol, avgHigh, avgLow}
    private volatile Map<Integer, int[]> volume1h = new ConcurrentHashMap<>();
    private volatile Map<Integer, int[]> volume5m = new ConcurrentHashMap<>();
    /** True when the last {@link #topFlips} pick came from Ares rather than the local wiki fallback. */
    private volatile boolean lastFromAres = false;

    @Inject
    public FlipScorer(OkHttpClient httpClient, Gson gson)
    {
        this.httpClient = httpClient;
        this.gson = gson;
    }

    /** Whether the last ranked candidate list came from Ares ({@code false} = local wiki fallback). */
    public boolean lastFromAres()
    {
        return lastFromAres;
    }

    /** Up to ~12 flip candidates best-first, sized as if the user has a 5-minute timeframe. */
    public List<Map<String, Object>> topFlips(long capital)
    {
        return topFlips(capital, 5, RiskLevel.MEDIUM, true, 4);
    }

    /**
     * Rank market-wide flip candidates.
     *
     * @param capital             coins available to deploy
     * @param timeframeMinutes    how often the user adjusts offers (qty ≈ volume in this window)
     * @param riskLevel           tighter filters for lower risk
     * @param membersItemsAllowed false on F2P worlds / F2P-only mode
     * @param remainingSlots      free GE slots to split capital across
     */
    public List<Map<String, Object>> topFlips(long capital, int timeframeMinutes, RiskLevel riskLevel,
                                              boolean membersItemsAllowed, int remainingSlots)
    {
        return topFlips(capital, timeframeMinutes, riskLevel, membersItemsAllowed, remainingSlots,
            null, null, null, null, 0L);
    }

    /**
     * Rank market-wide flip candidates. Prefers Ares {@code POST /v1/flips}; falls back to the
     * local wiki scorer if the server is unreachable so the panel does not go blank.
     */
    public List<Map<String, Object>> topFlips(long capital, int timeframeMinutes, RiskLevel riskLevel,
                                              boolean membersItemsAllowed, int remainingSlots,
                                              Map<Integer, Integer> remainingBuyLimit,
                                              Map<Integer, Integer> usedBuyLimit,
                                              Set<Integer> blockedIds, Set<Integer> skippedIds,
                                              long minPredictedProfit)
    {
        List<Map<String, Object>> remote = fetchFromAres(capital, timeframeMinutes, riskLevel,
            membersItemsAllowed, remainingSlots, remainingBuyLimit, usedBuyLimit,
            blockedIds, skippedIds, minPredictedProfit);
        if (remote != null)
        {
            lastFromAres = true;
            return remote;
        }
        lastFromAres = false;

        try { ensureLoaded(); }
        catch (Exception e) { log.warn("flip scorer load failed: {}", e.getMessage()); return new ArrayList<>(); }

        RiskProfile risk = RiskProfile.of(riskLevel);
        int tfMin = Math.max(1, Math.min(24 * 60, timeframeMinutes));
        int slots = Math.max(1, remainingSlots);
        long nowSec = System.currentTimeMillis() / 1000L;
        Set<Integer> exclude = new HashSet<>();
        if (blockedIds != null) exclude.addAll(blockedIds);
        if (skippedIds != null) exclude.addAll(skippedIds);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Integer, Meta> me : meta.entrySet())
        {
            int id = me.getKey();
            Meta m = me.getValue();
            if (m.limit <= 0) continue; // unknown/zero GE buy limit — never invent a quantity
            if (!membersItemsAllowed && m.members) continue;
            if (exclude.contains(id)) continue;
            if (isOddName(m.name)) continue;

            long[] p = latest.get(id);
            int[]  v1 = volume1h.get(id);
            int[]  v5 = volume5m.get(id);
            if (v1 == null) continue;

            int highVol = v1[0], lowVol = v1[1], avgHigh = v1[2], avgLow = v1[3];
            int vol = highVol + lowVol;
            if (vol < risk.minVolume) continue;
            if (Math.min(highVol, lowVol) < risk.minSideVolume) continue;

            long[] prices = pickPrices(p, v1, v5, tfMin, nowSec);
            if (prices == null) continue;
            long buy = prices[0], sell = prices[1];
            if (buy <= 0 || sell <= 0 || sell <= buy) continue;

            long tax = taxAmount(id, sell);
            long margin = sell - buy - tax;
            if (margin < risk.minMargin) continue;
            double marginPct = margin * 100.0 / buy;
            if (marginPct > risk.maxMarginPct) continue; // wide last-trade / 1h gap = odd / trap

            double imbalance = vol > 0 ? Math.abs(highVol - lowVol) / (double) vol : 1;
            if (imbalance > risk.maxImbalance) continue;

            int perHour = Math.max(1, Math.min(highVol, lowVol));
            int liqQty = liquidityQty(tfMin, perHour, v5);
            long qtyCap = Math.min(m.limit, liqQty);
            boolean remainingKnown = remainingBuyLimit != null && remainingBuyLimit.containsKey(id);
            int remaining = m.limit;
            if (remainingKnown)
                remaining = remainingBuyLimit.get(id);
            else if (usedBuyLimit != null && usedBuyLimit.containsKey(id))
            {
                remaining = m.limit - usedBuyLimit.get(id);
                remainingKnown = true;
            }
            if (remaining <= 0) continue;
            qtyCap = Math.min(qtyCap, remaining);
            long budget = capital > 0 ? Math.min(capital, capital / slots) : 0;
            if (budget > 0) qtyCap = Math.min(qtyCap, budget / buy);
            if (qtyCap < 1) continue;
            long projected = margin * qtyCap;
            if (minPredictedProfit > 0 && projected < minPredictedProfit) continue;
            double fillHrs = (double) qtyCap / perHour;

            double spreadRisk = marginPct > 15 ? 0.35 : 0;
            double liqRisk    = vol < risk.minVolume * 4 ? 0.4 : 0;
            double riskScore  = Math.min(0.9, imbalance * 0.6 + spreadRisk + liqRisk);
            double turnover   = 1.0 / Math.max(0.15, fillHrs);
            long score        = Math.round(margin * qtyCap * turnover * (1 - riskScore));

            List<String> flags = new ArrayList<>();
            if (imbalance > 0.5) flags.add("one-sided");
            if (spreadRisk > 0)  flags.add("wide-spread");
            if (liqRisk > 0)     flags.add("thin");

            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", id);
            s.put("name", m.name);
            s.put("buy_at", buy);
            s.put("sell_at", sell);
            s.put("margin_post_tax", margin);
            s.put("margin_pct", Math.round(marginPct * 10) / 10.0);
            s.put("suggested_qty", qtyCap);
            s.put("ge_limit", m.limit);
            s.put("members", m.members);
            s.put("est_fill_hours", Math.round(fillHrs * 100) / 100.0);
            s.put("projected_profit", projected);
            s.put("flags", flags);
            s.put("score", score);
            if (remainingKnown) s.put("limit_remaining", remaining);
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
        long sell = quotedSell(itemId);
        if (sell <= 0) return null;
        Meta m = meta.get(itemId);
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("name", m != null ? m.name : ("item " + itemId));
        q.put("sell_at", sell);
        q.put("ge_limit", m != null ? m.limit : 0);
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
        int[] v1 = volume1h.get(itemId);
        int[] v5 = volume5m.get(itemId);
        long[] prices = pickPrices(p, v1, v5, 30, System.currentTimeMillis() / 1000L);
        if (prices == null)
        {
            if (p == null || (p[0] <= 0 && p[1] <= 0)) return null;
            prices = new long[]{ p[1], p[0] };
        }
        Meta m = meta.get(itemId);
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("name", m != null ? m.name : ("item " + itemId));
        q.put("buy_at", prices[0]);
        q.put("sell_at", prices[1]);
        q.put("ge_limit", m != null ? m.limit : 0);
        return q;
    }

    /** Wiki GE buy limit for an item, or 0 if unknown. */
    public int geLimit(int itemId)
    {
        Meta m = meta.get(itemId);
        return m != null ? m.limit : 0;
    }

    /**
     * Market health for an item already on the GE, using the same wiki averages and
     * filters as ranking. Used to MODIFY vs ABORT existing offers. Returns null when
     * there is no price (do not abort on missing data). {@code dead} is only set for a
     * clear dead flip: non-positive margin after tax, odd name, or spread vs 1h avg
     * beyond the risk cap — not thin volume or "not in the top 12".
     */
    public Map<String, Object> evaluateItem(int itemId, int timeframeMinutes, RiskLevel riskLevel,
                                            boolean membersItemsAllowed)
    {
        try { ensureLoaded(); } catch (Exception e) { return null; }
        Meta m = meta.get(itemId);
        if (m == null) return null;
        if (!membersItemsAllowed && m.members) return null;
        RiskProfile risk = RiskProfile.of(riskLevel);
        int tfMin = Math.max(1, Math.min(24 * 60, timeframeMinutes));
        long nowSec = System.currentTimeMillis() / 1000L;
        long[] p = latest.get(itemId);
        int[] v1 = volume1h.get(itemId);
        int[] v5 = volume5m.get(itemId);
        long[] prices = pickPrices(p, v1, v5, tfMin, nowSec);
        if (prices == null)
        {
            // Still quote filling offers so MODIFY can fire; ranking already filtered these out.
            if (p == null || (p[0] <= 0 && p[1] <= 0)) return null;
            long high = p[0], low = p[1];
            if (low > 0 && high > low) prices = new long[]{ low, high };
            else if (low > 0) prices = new long[]{ low, low };
            else if (high > 0) prices = new long[]{ high, high };
            else return null;
        }
        long buy = prices[0], sell = prices[1];
        if (buy <= 0 || sell <= 0) return null;

        long tax = taxAmount(itemId, sell);
        long margin = sell - buy - tax;
        double marginPct = buy > 0 ? margin * 100.0 / buy : 0;

        List<String> flags = new ArrayList<>();
        boolean odd = isOddName(m.name);
        if (odd) flags.add("odd");
        boolean spreadBlowout = marginPct > risk.maxMarginPct;
        if (spreadBlowout) flags.add("spread-blowout");
        if (marginPct > 15) flags.add("wide-spread");

        boolean dead = odd || margin <= 0 || spreadBlowout;
        boolean viable = !dead && m.limit > 0;
        if (v1 != null)
        {
            int highVol = v1[0], lowVol = v1[1];
            int vol = highVol + lowVol;
            double imbalance = vol > 0 ? Math.abs(highVol - lowVol) / (double) vol : 1;
            if (vol < risk.minVolume || Math.min(highVol, lowVol) < risk.minSideVolume) viable = false;
            if (imbalance > risk.maxImbalance)
            {
                flags.add("one-sided");
                viable = false;
            }
            if (vol < risk.minVolume * 4) flags.add("thin");
        }
        else viable = false;

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", itemId);
        s.put("name", m.name);
        s.put("buy_at", buy);
        s.put("sell_at", sell);
        s.put("margin_post_tax", margin);
        s.put("margin_pct", Math.round(marginPct * 10) / 10.0);
        s.put("ge_limit", m.limit);
        s.put("flags", flags);
        s.put("viable", viable);
        s.put("dead", dead);
        if (dead)
        {
            String reason = odd ? "Odd / untradeable name."
                : margin <= 0 ? "Margin gone after tax."
                : "Spread blew out vs 1h average.";
            s.put("dead_reason", reason);
        }
        return s;
    }

    /** POST /v1/flips on Ares. Returns the candidate list, or null if the server is unreachable. */
    private List<Map<String, Object>> fetchFromAres(long capital, int timeframeMinutes, RiskLevel riskLevel,
                                                    boolean membersItemsAllowed, int remainingSlots,
                                                    Map<Integer, Integer> remainingBuyLimit,
                                                    Map<Integer, Integer> usedBuyLimit,
                                                    Set<Integer> blockedIds, Set<Integer> skippedIds,
                                                    long minPredictedProfit)
    {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("capital", capital);
        body.put("timeframeMinutes", Math.max(1, timeframeMinutes));
        body.put("risk", riskLevel != null ? riskLevel.toApiValue() : "medium");
        body.put("membersItemsAllowed", membersItemsAllowed);
        body.put("f2pOnly", !membersItemsAllowed);
        body.put("remainingSlots", Math.max(1, remainingSlots));
        if (minPredictedProfit > 0) body.put("minPredictedProfit", minPredictedProfit);
        if (remainingBuyLimit != null && !remainingBuyLimit.isEmpty())
            body.put("remainingBuyLimit", stringifyKeys(remainingBuyLimit));
        if (usedBuyLimit != null && !usedBuyLimit.isEmpty())
            body.put("usedBuyLimit", stringifyKeys(usedBuyLimit));
        if (blockedIds != null && !blockedIds.isEmpty()) body.put("blockedIds", new ArrayList<>(blockedIds));
        if (skippedIds != null && !skippedIds.isEmpty()) body.put("skippedIds", new ArrayList<>(skippedIds));

        Request req = new Request.Builder()
            .url(ARES_FLIPS)
            .header("User-Agent", UA)
            .post(RequestBody.create(JSON, gson.toJson(body)))
            .build();
        try (Response r = httpClient.newBuilder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
            .newCall(req)
            .execute())
        {
            if (!r.isSuccessful() || r.body() == null)
            {
                log.warn("Ares /v1/flips HTTP {}, using local scorer", r.code());
                return null;
            }
            JsonObject root = gson.fromJson(r.body().charStream(), JsonObject.class);
            if (root == null || !root.has("candidates")) return null;
            List<Map<String, Object>> rows = gson.fromJson(root.get("candidates"), CANDIDATE_LIST);
            if (rows == null) return null;
            log.debug("Ares /v1/flips returned {} candidates ({})", rows.size(),
                root.has("source") ? root.get("source").getAsString() : "unknown");
            return excludeIds(rows, blockedIds, skippedIds);
        }
        catch (Exception e)
        {
            log.warn("Ares /v1/flips failed, using local scorer: {}", e.getMessage());
            return null;
        }
    }

    private static Map<String, Integer> stringifyKeys(Map<Integer, Integer> in)
    {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> e : in.entrySet())
        {
            if (e.getKey() == null || e.getValue() == null) continue;
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    /** Drop blocked/skipped ids if Ares ignored those fields. Does not change prices. */
    private static List<Map<String, Object>> excludeIds(List<Map<String, Object>> rows,
                                                        Set<Integer> blockedIds, Set<Integer> skippedIds)
    {
        if (rows == null) return null;
        Set<Integer> exclude = new HashSet<>();
        if (blockedIds != null) exclude.addAll(blockedIds);
        if (skippedIds != null) exclude.addAll(skippedIds);
        if (exclude.isEmpty()) return rows;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows)
        {
            if (row == null) continue;
            Object idObj = row.get("id");
            int id = idObj instanceof Number ? ((Number) idObj).intValue() : 0;
            if (id > 0 && exclude.contains(id)) continue;
            out.add(row);
        }
        return out;
    }

    private long quotedSell(int itemId)
    {
        int[] v1 = volume1h.get(itemId);
        if (v1 != null && v1[2] > 0) return v1[2];
        int[] v5 = volume5m.get(itemId);
        if (v5 != null && v5[2] > 0) return v5[2];
        long[] p = latest.get(itemId);
        return p != null ? p[0] : 0;
    }

    /**
     * Buy/sell prices: prefer 5m averages for short timeframes, else 1h averages, else a
     * fresh last-trade pair. Last-trade-only outliers (stale high or low) are rejected.
     * Returns {buy, sell} or null.
     */
    private static long[] pickPrices(long[] latestPx, int[] v1, int[] v5, int timeframeMinutes, long nowSec)
    {
        if (timeframeMinutes <= 30 && v5 != null && v5[3] > 0 && v5[2] > v5[3])
        {
            return new long[]{ v5[3], v5[2] };
        }
        if (v1 != null && v1[3] > 0 && v1[2] > v1[3])
        {
            return new long[]{ v1[3], v1[2] };
        }
        if (latestPx == null) return null;
        long high = latestPx[0], low = latestPx[1], highTime = latestPx[2], lowTime = latestPx[3];
        if (high <= 0 || low <= 0 || high <= low) return null;
        if (nowSec - highTime > STALE_TRADE_SEC || nowSec - lowTime > STALE_TRADE_SEC) return null;
        return new long[]{ low, high };
    }

    /** Units expected to fill within the user's timeframe, from the bottleneck side's volume. */
    private static int liquidityQty(int timeframeMinutes, int perHour, int[] v5)
    {
        double perMinute = perHour / 60.0;
        if (timeframeMinutes <= 30 && v5 != null)
        {
            int side5 = Math.max(0, Math.min(v5[0], v5[1]));
            if (side5 > 0) perMinute = side5 / 5.0;
        }
        return Math.max(1, (int) Math.floor(perMinute * timeframeMinutes));
    }

    private static boolean isOddName(String name)
    {
        if (name == null || name.isEmpty()) return true;
        String n = name.toLowerCase();
        return n.contains("placeholder") || n.startsWith("broken ") || n.contains("(nz)");
    }

    private long taxAmount(int id, long price)
    {
        if (price <= 0 || TAX_EXEMPT.contains(id)) return 0;
        if (price >= TAX_MAX_PRICE) return TAX_CAP;
        return (long) Math.floor(price * TAX_RATE);
    }

    private static final class RiskProfile
    {
        final int minVolume;
        final int minSideVolume;
        final int minMargin;
        final double maxMarginPct;
        final double maxImbalance;

        RiskProfile(int minVolume, int minSideVolume, int minMargin, double maxMarginPct, double maxImbalance)
        {
            this.minVolume = minVolume;
            this.minSideVolume = minSideVolume;
            this.minMargin = minMargin;
            this.maxMarginPct = maxMarginPct;
            this.maxImbalance = maxImbalance;
        }

        static RiskProfile of(RiskLevel level)
        {
            if (level == RiskLevel.LOW)
            {
                return new RiskProfile(2000, 400, 30, 8, 0.45);
            }
            if (level == RiskLevel.HIGH)
            {
                return new RiskProfile(400, 50, 15, 20, 0.75);
            }
            return new RiskProfile(800, 150, 20, 12, 0.55);
        }
    }

    // ── fetch + cache ──────────────────────────────────────────────────────────

    private synchronized void ensureLoaded() throws Exception
    {
        if (meta.isEmpty()) loadMapping();
        if (System.currentTimeMillis() - lastPriceFetch > PRICE_TTL || latest.isEmpty())
        {
            loadLatest();
            volume1h = loadVolume(HOUR);
            volume5m = loadVolume(FIVE);
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
                int limit = o.has("limit") && !o.get("limit").isJsonNull() ? o.get("limit").getAsInt() : 0;
                boolean members = !o.has("members") || o.get("members").getAsBoolean();
                meta.put(id, new Meta(name, limit, members));
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
                long highTime = o.has("highTime") && !o.get("highTime").isJsonNull() ? o.get("highTime").getAsLong() : 0;
                long lowTime  = o.has("lowTime")  && !o.get("lowTime").isJsonNull()  ? o.get("lowTime").getAsLong()  : 0;
                if (high > 0 || low > 0) out.put(Integer.parseInt(e.getKey()), new long[]{ high, low, highTime, lowTime });
            }
        }
        latest = out;
    }

    private Map<Integer, int[]> loadVolume(String url) throws Exception
    {
        Map<Integer, int[]> out = new ConcurrentHashMap<>();
        try (Response r = httpClient.newCall(req(url)).execute())
        {
            if (!r.isSuccessful() || r.body() == null) return out;
            JsonObject data = gson.fromJson(r.body().charStream(), JsonObject.class).getAsJsonObject("data");
            for (Map.Entry<String, com.google.gson.JsonElement> e : data.entrySet())
            {
                JsonObject o = e.getValue().getAsJsonObject();
                int hv = o.has("highPriceVolume") ? o.get("highPriceVolume").getAsInt() : 0;
                int lv = o.has("lowPriceVolume")  ? o.get("lowPriceVolume").getAsInt()  : 0;
                int ah = o.has("avgHighPrice") && !o.get("avgHighPrice").isJsonNull() ? o.get("avgHighPrice").getAsInt() : 0;
                int al = o.has("avgLowPrice")  && !o.get("avgLowPrice").isJsonNull()  ? o.get("avgLowPrice").getAsInt()  : 0;
                out.put(Integer.parseInt(e.getKey()), new int[]{ hv, lv, ah, al });
            }
        }
        return out;
    }

    private static Request req(String url)
    {
        return new Request.Builder().url(url).header("User-Agent", UA).build();
    }
}
