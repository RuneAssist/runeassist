package com.runeassist.flip;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.runeassist.flip.model.ComposeSuggestionMapper;
import com.runeassist.flip.model.ComposeSuggestionRequest;
import com.runeassist.flip.model.ComposeSuggestionResponse;
import com.runeassist.flip.model.RiskLevel;
import com.runeassist.flip.model.Suggestion;
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
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

    /** Thin Ares HTTP client for market data and suggestion composition. Flip <em>ranking</em> */
@Slf4j
@Singleton
public class AresMarketClient
{
    private static final String UA = "RuneAssist-flip/1.0 (github.com/RuneAssist/runeassist)";
    private static final String ARES_FLIPS = "https://runeassist.ares-server.co.uk/v1/flips";
    private static final String ARES_SUGGESTION = "https://runeassist.ares-server.co.uk/v1/suggestion";
    private static final String ARES_HEALTH = "https://runeassist.ares-server.co.uk/v1/market/health";
    private static final String ARES_LIMITS = "https://runeassist.ares-server.co.uk/v1/market/limits";
    private static final String ARES_QUOTE = "https://runeassist.ares-server.co.uk/v1/market/quote";
    private static final long LIMITS_TTL = 6 * 60 * 60 * 1000L;
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final Type CANDIDATE_LIST = new TypeToken<List<Map<String, Object>>>(){}.getType();

    private final OkHttpClient httpClient;
    private final Gson gson;

    private volatile Map<Integer, Integer> geLimits = new ConcurrentHashMap<>();
    private volatile long limitsFetchedAt = 0;
    /** True when the last {@link #topFlips} call returned a reachable Ares response. */
    private volatile boolean lastFromAres = false;
    /** True when the last Ares {@code /v1/flips} call failed (HTTP/timeout/parse), not empty-ok. */
    private volatile boolean lastAresUnreachable = false;
    /** True when the last {@link #composeSuggestion} call returned a usable suggestion. */
    private volatile boolean lastFromCompose = false;
    /** True when the last {@link #composeSuggestion} call failed (HTTP/timeout/unusable body). */
    private volatile boolean lastComposeUnreachable = false;

    @Inject
    public AresMarketClient(OkHttpClient httpClient, Gson gson)
    {
        this.httpClient = httpClient;
        this.gson = gson;
    }

    /** Whether the last ranked candidate list came from a reachable Ares response. */
    public boolean lastFromAres()
    {
        return lastFromAres;
    }

    /** True when Ares {@code /v1/flips} failed; empty-but-reachable is {@code false}. */
    public boolean lastAresUnreachable()
    {
        return lastAresUnreachable;
    }

    /** True when the last {@link #composeSuggestion} returned a mapped {@link Suggestion}. */
    public boolean lastFromCompose()
    {
        return lastFromCompose;
    }

    /** True when the last {@link #composeSuggestion} failed; callers soft-fail to WAIT. */
    public boolean lastComposeUnreachable()
    {
        return lastComposeUnreachable;
    }

    /** Ask Ares to compose the next typed suggestion from a live GE / held snapshot. */
    public Suggestion composeSuggestion(ComposeSuggestionRequest request)
    {
        lastFromCompose = false;
        lastComposeUnreachable = false;
        if (request == null)
        {
            lastComposeUnreachable = true;
            return null;
        }

        Request req = new Request.Builder()
            .url(ARES_SUGGESTION)
            .header("User-Agent", UA)
            .post(RequestBody.create(JSON, gson.toJson(request)))
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
                lastComposeUnreachable = true;
                log.warn("Ares /v1/suggestion HTTP {}", r.code());
                return null;
            }
            ComposeSuggestionResponse parsed =
                gson.fromJson(r.body().charStream(), ComposeSuggestionResponse.class);
            Suggestion suggestion = ComposeSuggestionMapper.toSuggestion(parsed);
            if (suggestion == null)
            {
                lastComposeUnreachable = true;
                log.warn("Ares /v1/suggestion returned unusable body (ok={}, error={})",
                    parsed != null && parsed.isOk(),
                    parsed != null ? parsed.getError() : null);
                return null;
            }
            lastFromCompose = true;
            lastFromAres = true;
            lastAresUnreachable = false;
            lastComposeUnreachable = false;
            log.debug("Ares /v1/suggestion composed {} {}",
                suggestion.getType(), suggestion.getName());
            return suggestion;
        }
        catch (Exception e)
        {
            lastComposeUnreachable = true;
            log.warn("Ares /v1/suggestion failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Ranked flip candidates from Ares {@code POST /v1/flips}, or empty when the server has
     * none / is unreachable. There is no local ranking fallback.
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
        lastFromAres = remote != null;
        if (remote != null)
        {
            log.info("Ares /v1/flips returned 0 candidates");
        }
        return new ArrayList<>();
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

    /** Current market quote for one item, or null. Blocks on HTTP; call off the client thread. */
    public Map<String, Object> quote(int itemId)
    {
        Map<Integer, Map<String, Object>> all = quotes(Arrays.asList(itemId));
        return all.get(itemId);
    }

    /**
     * Batch market quotes from {@code GET /v1/market/quote?ids=...}, keyed by item id.
     * One request for many ids — used for held-stock sells and portfolio marks.
     */
    public Map<Integer, Map<String, Object>> quotes(Collection<Integer> itemIds)
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

        Request request = new Request.Builder()
            .url(ARES_QUOTE + "?ids=" + ids)
            .header("User-Agent", UA)
            .get()
            .build();
        try (Response r = httpClient.newCall(request).execute())
        {
            if (!r.isSuccessful() || r.body() == null) return out;
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
            log.warn("Ares /v1/market/quote failed: {}", e.getMessage());
            return out;
        }
    }

    /** GE buy limit for an item from the cached Ares limits map, or 0 if unknown. */
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
                log.warn("Ares /v1/market/limits failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Market health for a batch of items from Ares {@code GET /v1/market/health}.
     * Empty map if unreachable — callers treat a missing entry as no health info.
     */
    public Map<Integer, Map<String, Object>> evaluateItems(Collection<Integer> itemIds,
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

    /** POST /v1/flips. Returns candidates, or null if unreachable. */
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
