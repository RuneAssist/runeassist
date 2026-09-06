package com.runeassist.flip;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
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

/** Thin Ares HTTP client for market data and suggestion composition. */
@Slf4j
@Singleton
public class AresMarketClient
{
    private static final String UA = "RuneAssist-flip/1.0 (github.com/RuneAssist/runeassist)";
    private static final String BASE = "https://runeassist.com";
    private static final String ARES_FLIPS = BASE + "/v1/flips";
    private static final String ARES_SUGGESTION = BASE + "/v1/suggestion";
    private static final String ARES_HEALTH = BASE + "/v1/market/health";
    private static final String ARES_LIMITS = BASE + "/v1/market/limits";
    private static final String ARES_QUOTE = BASE + "/v1/market/quote";
    private static final long LIMITS_TTL = 6 * 60 * 60 * 1000L;
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final Type ROW_LIST = new TypeToken<List<Map<String, Object>>>(){}.getType();

    private final OkHttpClient httpClient;
    private final Gson gson;

    private volatile Map<Integer, Integer> geLimits = new ConcurrentHashMap<>();
    private volatile long limitsFetchedAt = 0;
    private volatile boolean lastFromAres = false;
    private volatile boolean lastAresUnreachable = false;
    private volatile boolean lastFromCompose = false;
    private volatile boolean lastComposeUnreachable = false;

    @Inject
    public AresMarketClient(OkHttpClient httpClient, Gson gson)
    {
        this.httpClient = httpClient;
        this.gson = gson;
    }

    public boolean lastFromAres() { return lastFromAres; }
    public boolean lastAresUnreachable() { return lastAresUnreachable; }
    public boolean lastFromCompose() { return lastFromCompose; }
    public boolean lastComposeUnreachable() { return lastComposeUnreachable; }

    public Suggestion composeSuggestion(ComposeSuggestionRequest request)
    {
        lastFromCompose = false;
        lastComposeUnreachable = false;
        if (request == null)
        {
            lastComposeUnreachable = true;
            return null;
        }
        JsonObject root = postJson(ARES_SUGGESTION, gson.toJson(request), "suggestion");
        if (root == null)
        {
            lastComposeUnreachable = true;
            return null;
        }
        ComposeSuggestionResponse parsed = gson.fromJson(root, ComposeSuggestionResponse.class);
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
        log.debug("Ares /v1/suggestion composed {} {}", suggestion.getType(), suggestion.getName());
        return suggestion;
    }

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
        if (remote != null) log.info("Ares /v1/flips returned 0 candidates");
        return new ArrayList<>();
    }

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

    public Map<String, Object> quote(int itemId)
    {
        return quotes(Arrays.asList(itemId)).get(itemId);
    }

    public Map<Integer, Map<String, Object>> quotes(Collection<Integer> itemIds)
    {
        String ids = joinIds(itemIds);
        if (ids == null) return new LinkedHashMap<>();
        return itemsById(getJson(ARES_QUOTE + "?ids=" + ids, "market/quote"), "items");
    }

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
            JsonObject root = getJson(ARES_LIMITS, "market/limits");
            if (root == null || !root.has("limits")) return;
            Map<Integer, Integer> parsed = new ConcurrentHashMap<>();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("limits").entrySet())
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
    }

    public Map<Integer, Map<String, Object>> evaluateItems(Collection<Integer> itemIds,
                                                           int timeframeMinutes, RiskLevel riskLevel,
                                                           boolean membersItemsAllowed)
    {
        String ids = joinIds(itemIds);
        if (ids == null) return new LinkedHashMap<>();
        String url = ARES_HEALTH + "?ids=" + ids
            + "&timeframe=" + Math.max(1, timeframeMinutes)
            + "&risk=" + (riskLevel != null ? riskLevel.toApiValue() : "medium")
            + "&membersItemsAllowed=" + membersItemsAllowed;
        return itemsById(getJson(url, "market/health"), "items");
    }

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

        JsonObject root = postJson(ARES_FLIPS, gson.toJson(body), "flips");
        if (root == null || !root.has("candidates"))
        {
            lastAresUnreachable = true;
            return null;
        }
        List<Map<String, Object>> rows = gson.fromJson(root.get("candidates"), ROW_LIST);
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

    /** GET JSON; null on HTTP/parse failure. */
    private JsonObject getJson(String url, String label)
    {
        return execute(new Request.Builder().url(url).header("User-Agent", UA).get().build(),
            httpClient, label);
    }

    /** POST JSON with compose/flips timeouts; null on HTTP/parse failure. */
    private JsonObject postJson(String url, String body, String label)
    {
        Request req = new Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .post(RequestBody.create(JSON, body))
            .build();
        OkHttpClient timed = httpClient.newBuilder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
        return execute(req, timed, label);
    }

    private JsonObject execute(Request request, OkHttpClient client, String label)
    {
        try (Response r = client.newCall(request).execute())
        {
            if (!r.isSuccessful() || r.body() == null)
            {
                log.warn("Ares /v1/{} HTTP {}", label, r.code());
                return null;
            }
            return gson.fromJson(r.body().charStream(), JsonObject.class);
        }
        catch (Exception e)
        {
            log.warn("Ares /v1/{} failed: {}", label, e.getMessage());
            return null;
        }
    }

    private Map<Integer, Map<String, Object>> itemsById(JsonObject root, String arrayKey)
    {
        Map<Integer, Map<String, Object>> out = new LinkedHashMap<>();
        if (root == null || !root.has(arrayKey)) return out;
        List<Map<String, Object>> rows = gson.fromJson(root.get(arrayKey), ROW_LIST);
        if (rows == null) return out;
        for (Map<String, Object> row : rows)
        {
            Object id = row.get("id");
            if (id instanceof Number) out.put(((Number) id).intValue(), row);
        }
        return out;
    }

    private static String joinIds(Collection<Integer> itemIds)
    {
        if (itemIds == null || itemIds.isEmpty()) return null;
        StringBuilder ids = new StringBuilder();
        for (Integer id : itemIds)
        {
            if (id == null || id <= 0) continue;
            if (ids.length() > 0) ids.append(',');
            ids.append(id.intValue());
        }
        return ids.length() == 0 ? null : ids.toString();
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
