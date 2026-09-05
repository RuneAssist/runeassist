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
    private static final String UA = "RuneAssist-flip/1.0 (github.com/RuneAssist/runeassist)";
    private static final String ARES_FLIPS = "https://runeassist.ares-server.co.uk/v1/flips";
    private static final String ARES_DECANTS = "https://runeassist.ares-server.co.uk/v1/decants";
    private static final String ARES_HEALTH = "https://runeassist.ares-server.co.uk/v1/market/health";
    private static final String ARES_LIMITS = "https://runeassist.ares-server.co.uk/v1/market/limits";
    private static final String ARES_QUOTE = "https://runeassist.ares-server.co.uk/v1/market/quote";
    private static final String ARES_FAMILIES = "https://runeassist.ares-server.co.uk/v1/decants/families";
    // Buy limits move only when Jagex moves them, so this is held far longer than prices.
    private static final long LIMITS_TTL = 6 * 60 * 60 * 1000L;
    private static final long FAMILIES_TTL = 60_000L;
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final Type CANDIDATE_LIST = new TypeToken<List<Map<String, Object>>>(){}.getType();

    private final OkHttpClient httpClient;
    private final Gson gson;

    private volatile Map<Integer, Integer> geLimits = new ConcurrentHashMap<>();
    private volatile long limitsFetchedAt = 0;
    private volatile List<Map<String, Object>> decantFamilies = new ArrayList<>();
    private volatile long familiesFetchedAt = 0;
    /** True when the last {@link #topFlips} pick came from Ares rather than the local wiki fallback. */
    private volatile boolean lastFromAres = false;
    /** True when the last Ares {@code /v1/flips} call failed (HTTP/timeout/parse), not empty-ok. */
    private volatile boolean lastAresUnreachable = false;

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

    /** True when Ares {@code /v1/flips} failed; empty-but-reachable is {@code false}. */
    public boolean lastAresUnreachable()
    {
        return lastAresUnreachable;
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
        if (remote != null && !remote.isEmpty())
        {
            lastFromAres = true;
            return remote;
        }
        lastFromAres = false;
        if (remote != null)
        {
            log.info("Ares /v1/flips returned 0 candidates");
        }

        // Ranking is server-side only. This repository is public -- the Plugin Hub builds the
        // plugin from it -- so the scoring model is not duplicated here. Keeping a second copy
        // also meant two implementations that had to be held in lock-step and silently drifted
        // apart when only one was changed. When Ares has no candidates the panel falls back to
        // a WAIT (WAIT_ARES_DOWN when it is unreachable), which is honest about why there is
        // nothing to suggest rather than substituting a different, weaker ranking.
        //
        // The wiki price/mapping cache below is still loaded and used locally, for GE limits,
        // offer-screen quotes and decant detection -- those need live inventory and per-tick
        // latency, so they cannot move server-side.
        return new ArrayList<>();
    }

    /** Up to 5 best decant opportunities (buy a cheap dose, decant, sell a different dose). */
    public List<Map<String, Object>> topDecants()
    {
        return topDecants(5);
    }

    /**
     * Rank decant opportunities: for each potion "family" (dose variants of the same base
     * name), find the cheapest-per-dose bottle to buy and the highest-value-per-dose bottle
     * to sell. Decanting itself is free/instant (a bank action) and conserves total doses, so
     * the opportunity is purely the per-dose price gap between the two, sized to GE limit and
     * liquidity like {@link #topFlips}. Never mixes with {@link #topFlips}'s own candidates —
     * decanting needs a manual step in between buy and sell that a normal flip doesn't.
     */
    public List<Map<String, Object>> topDecants(int maxResults)
    {
        Request request = new Request.Builder()
            .url(ARES_DECANTS + "?top=" + Math.max(1, maxResults))
            .header("User-Agent", UA)
            .get()
            .build();
        try (Response r = httpClient.newCall(request).execute())
        {
            if (!r.isSuccessful() || r.body() == null)
            {
                log.warn("Ares /v1/decants HTTP {}", r.code());
                return new ArrayList<>();
            }
            JsonObject root = gson.fromJson(r.body().charStream(), JsonObject.class);
            if (root == null || !root.has("decants")) return new ArrayList<>();
            List<Map<String, Object>> rows = gson.fromJson(root.get("decants"), CANDIDATE_LIST);
            return rows == null ? new ArrayList<>() : rows;
        }
        catch (Exception e)
        {
            log.warn("Ares /v1/decants failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Gain from decanting stock you already hold and selling the result, versus simply selling
     * it as it is. Positive means decanting first is worth more.
     *
     * <p>This is deliberately NOT the same question {@link #topDecants} answers. That one ranks
     * opportunities worth <em>starting</em>, so it nets out the cost of buying in and applies
     * profit/volume floors. Once the bottles are already bought those are sunk and irrelevant:
     * the only live choice is convert-then-sell versus sell-as-is. A family can easily be a bad
     * buy today (thin margin, filtered out of the ranking entirely) while converting stock you
     * are already sitting on is still clearly worth doing.</p>
     *
     * <p>Doses are conserved by decanting, so {@code heldQty} bottles of {@code heldDose} give
     * {@code heldQty * heldDose / targetDose} bottles of {@code targetDose}, floored — any
     * remainder is left behind as an odd-dose bottle and contributes nothing either way.</p>
     */
    static long decantGainOverRawSell(long heldQty, int heldDose, int targetDose,
                                      long heldSellAt, long heldTax,
                                      long targetSellAt, long targetTax)
    {
        if (heldQty <= 0 || heldDose <= 0 || targetDose <= 0) return 0;
        long asIs = heldQty * Math.max(0, heldSellAt - heldTax);
        long converted = (heldQty * heldDose) / targetDose;
        long decanted = converted * Math.max(0, targetSellAt - targetTax);
        return decanted - asIs;
    }

    /**
     * Best decant for stock already held, or null if converting it isn't worth more than
     * selling it as-is (or this item isn't part of a family worth converting).
     *
     * <p>Priced from the server's dose-family table, which lists only families where some
     * conversion currently gains value. Held quantity never leaves the client: the table comes
     * down, this matches it against what is actually held, and the comparison below is done
     * locally. Row shape matches {@link #topDecants}, with {@code buyItemId}/{@code buyQty}
     * describing what is held rather than something to buy, and {@code projectedProfit} being
     * the gain over selling as-is rather than a full buy-to-sell profit.
     */
    public Map<String, Object> decantForHeld(int heldItemId, long heldQty)
    {
        if (heldQty <= 0) return null;
        List<Map<String, Object>> families = ensureFamilies();
        if (families.isEmpty()) return null;

        for (Map<String, Object> fam : families)
        {
            Object dosesObj = fam.get("doses");
            if (!(dosesObj instanceof List)) continue;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> doses = (List<Map<String, Object>>) dosesObj;

            Map<String, Object> heldDose = null;
            for (Map<String, Object> d : doses)
            {
                if (num(d.get("itemId")) == heldItemId) { heldDose = d; break; }
            }
            if (heldDose == null) continue;

            long heldDoseCount = num(heldDose.get("dose"));
            long heldSellAt = num(heldDose.get("sell"));
            long heldTax = num(heldDose.get("tax"));
            if (heldDoseCount <= 0 || heldSellAt <= 0) return null;

            Map<String, Object> best = null;
            long bestGain = 0, bestSellQty = 0;
            for (Map<String, Object> d : doses)
            {
                long dose = num(d.get("dose"));
                if (dose <= 0 || dose == heldDoseCount) continue;
                long gain = decantGainOverRawSell(heldQty, (int) heldDoseCount, (int) dose,
                    heldSellAt, heldTax, num(d.get("sell")), num(d.get("tax")));
                if (gain > bestGain)
                {
                    bestGain = gain;
                    best = d;
                    bestSellQty = (heldQty * heldDoseCount) / dose;
                }
            }
            if (best == null || bestSellQty < 1) return null;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("family", fam.get("family"));
            row.put("buyItemId", heldItemId);
            row.put("buyName", heldDose.get("name"));
            row.put("buyDose", heldDoseCount);
            row.put("buyAt", num(heldDose.get("buy")));
            row.put("buyQty", heldQty);
            row.put("sellItemId", num(best.get("itemId")));
            row.put("sellName", best.get("name"));
            row.put("sellDose", num(best.get("dose")));
            row.put("sellAt", num(best.get("sell")));
            row.put("sellQty", bestSellQty);
            row.put("projectedProfit", bestGain);
            row.put("flags", new ArrayList<>(Arrays.asList("held")));
            return row;
        }
        return null;
    }

    private static long num(Object o)
    {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    /** Dose families worth converting, refreshed on the same cadence as prices. */
    private List<Map<String, Object>> ensureFamilies()
    {
        if (!decantFamilies.isEmpty() && System.currentTimeMillis() - familiesFetchedAt < FAMILIES_TTL)
        {
            return decantFamilies;
        }
        Request request = new Request.Builder().url(ARES_FAMILIES).header("User-Agent", UA).get().build();
        try (Response r = httpClient.newCall(request).execute())
        {
            if (!r.isSuccessful() || r.body() == null) return decantFamilies;
            JsonObject root = gson.fromJson(r.body().charStream(), JsonObject.class);
            if (root == null || !root.has("families")) return decantFamilies;
            List<Map<String, Object>> rows = gson.fromJson(root.get("families"), CANDIDATE_LIST);
            if (rows != null)
            {
                decantFamilies = rows;
                familiesFetchedAt = System.currentTimeMillis();
            }
        }
        catch (Exception e)
        {
            log.warn("Ares /v1/decants/families failed: {}", e.getMessage());
        }
        return decantFamilies;
    }

    /** Sell-side quote for a held item: {name, sell_at, ge_limit, tax_at_sell}, or null. */
    public Map<String, Object> sellQuote(int itemId)
    {
        Map<String, Object> q = quote(itemId);
        if (q == null || !(q.get("sell_at") instanceof Number)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", q.get("name"));
        out.put("sell_at", q.get("sell_at"));
        out.put("ge_limit", q.get("ge_limit"));
        out.put("tax_at_sell", q.get("tax_at_sell"));
        return out;
    }

    /**
     * Current market quote for any item: {name, buy_at, sell_at, ge_limit, tax_at_sell}, or
     * null if the server has no price for it. Blocks on HTTP; call off the client thread.
     */
    public Map<String, Object> quote(int itemId)
    {
        Request request = new Request.Builder()
            .url(ARES_QUOTE + "?ids=" + itemId)
            .header("User-Agent", UA)
            .get()
            .build();
        try (Response r = httpClient.newCall(request).execute())
        {
            if (!r.isSuccessful() || r.body() == null) return null;
            JsonObject root = gson.fromJson(r.body().charStream(), JsonObject.class);
            if (root == null || !root.has("items")) return null;
            List<Map<String, Object>> rows = gson.fromJson(root.get("items"), CANDIDATE_LIST);
            return rows == null || rows.isEmpty() ? null : rows.get(0);
        }
        catch (Exception e)
        {
            log.warn("Ares /v1/market/quote failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Wiki GE buy limit for an item, or 0 if unknown.
     *
     * <p>Served from a map fetched once and held, not a call per item: this is asked in loops
     * (every item with a used limit, every scored candidate), and buy limits only change when
     * Jagex changes them.
     */
    public int geLimit(int itemId)
    {
        ensureLimits();
        Integer limit = geLimits.get(itemId);
        return limit != null ? limit : 0;
    }

    private void ensureLimits()
    {
        if (!geLimits.isEmpty() && System.currentTimeMillis() - limitsFetchedAt < LIMITS_TTL) return;
        synchronized (this)
        {
            if (!geLimits.isEmpty() && System.currentTimeMillis() - limitsFetchedAt < LIMITS_TTL) return;
            Request request = new Request.Builder().url(ARES_LIMITS).header("User-Agent", UA).get().build();
            try (Response r = httpClient.newCall(request).execute())
            {
                if (!r.isSuccessful() || r.body() == null) return;
                JsonObject root = gson.fromJson(r.body().charStream(), JsonObject.class);
                if (root == null || !root.has("limits")) return;
                Map<Integer, Integer> parsed = new ConcurrentHashMap<>();
                for (Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject("limits").entrySet())
                {
                    try { parsed.put(Integer.parseInt(e.getKey()), e.getValue().getAsInt()); }
                    catch (Exception ignored) { }
                }
                if (!parsed.isEmpty())
                {
                    geLimits = parsed;
                    limitsFetchedAt = System.currentTimeMillis();
                }
            }
            catch (Exception e)
            {
                // Keep whatever we already have; a stale limit is better than none, and the
                // callers treat 0 as "unknown" rather than "no limit".
                log.warn("Ares /v1/market/limits failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Market health for a batch of items, from Ares {@code GET /v1/market/health}, keyed by id.
     *
     * <p>Batched because this is wanted for every live offer and held stack on each suggestion
     * cycle, and one request beats a dozen. The risk thresholds that decide {@code viable} and
     * {@code dead} live on the server; this plugin only passes along the {@link RiskLevel} the
     * player selected, which is the same split Flipping Copilot's public repository uses.
     *
     * <p>Returns an empty map if the server is unreachable -- callers treat a missing entry as
     * "no health information", which is what they already did when an item was unpriced.
     */
    public Map<Integer, Map<String, Object>> evaluateItems(java.util.Collection<Integer> itemIds,
                                                           int timeframeMinutes, RiskLevel riskLevel,
                                                           boolean membersItemsAllowed)
    {
        Map<Integer, Map<String, Object>> out = new LinkedHashMap<>();
        if (itemIds == null || itemIds.isEmpty()) return out;
        StringBuilder ids = new StringBuilder();
        for (Integer id : itemIds)
        {
            if (id == null || id <= 0) continue;
            if (ids.length() > 0) ids.append(',');
            ids.append(id.intValue());
        }
        if (ids.length() == 0) return out;

        String url = ARES_HEALTH + "?ids=" + ids
            + "&timeframe=" + Math.max(1, timeframeMinutes)
            + "&risk=" + (riskLevel != null ? riskLevel.toApiValue() : "medium")
            + "&membersItemsAllowed=" + membersItemsAllowed;
        Request request = new Request.Builder().url(url).header("User-Agent", UA).get().build();
        try (Response r = httpClient.newCall(request).execute())
        {
            if (!r.isSuccessful() || r.body() == null)
            {
                log.warn("Ares /v1/market/health HTTP {}", r.code());
                return out;
            }
            JsonObject root = gson.fromJson(r.body().charStream(), JsonObject.class);
            if (root == null || !root.has("items")) return out;
            List<Map<String, Object>> rows = gson.fromJson(root.get("items"), CANDIDATE_LIST);
            if (rows == null) return out;
            for (Map<String, Object> row : rows)
            {
                Object id = row.get("id");
                if (id instanceof Number) out.put(((Number) id).intValue(), row);
            }
            return out;
        }
        catch (Exception e)
        {
            log.warn("Ares /v1/market/health failed: {}", e.getMessage());
            return out;
        }
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
                lastAresUnreachable = true;
                log.warn("Ares /v1/flips HTTP {}", r.code());
                return null;
            }
            JsonObject root = gson.fromJson(r.body().charStream(), JsonObject.class);
            if (root == null || !root.has("candidates"))
            {
                lastAresUnreachable = true;
                return null;
            }
            List<Map<String, Object>> rows = gson.fromJson(root.get("candidates"), CANDIDATE_LIST);
            if (rows == null)
            {
                lastAresUnreachable = true;
                return null;
            }
            lastAresUnreachable = false;
            log.debug("Ares /v1/flips returned {} candidates ({})", rows.size(),
                root.has("source") ? root.get("source").getAsString() : "unknown");
            return excludeIds(rows, blockedIds, skippedIds);
        }
        catch (Exception e)
        {
            lastAresUnreachable = true;
            log.warn("Ares /v1/flips failed: {}", e.getMessage());
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

}
