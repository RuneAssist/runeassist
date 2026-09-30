package com.runeassist.flip;

import com.runeassist.flip.util.Version;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.runeassist.flip.model.ComposeSuggestionMapper;
import com.runeassist.flip.model.ComposeSuggestionRequest;
import com.runeassist.flip.model.ComposeSuggestionResponse;
import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.controller.history.AccountHttp;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Singleton
public class AresMarketClient
{
    private static final String UA = Version.USER_AGENT;
    private static final String BASE = "https://runeassist.com";
    private static final String ARES_SUGGESTION = BASE + "/v1/suggestion";
    private static final String ARES_LIMITS = BASE + "/v1/market/limits";
    private static final String ARES_QUOTE = BASE + "/v1/market/quote";
    private static final long LIMITS_TTL = 6 * 60 * 60 * 1000L;
    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final AccountHttp accountHttp;

    private volatile Map<Integer, Integer> geLimits = new ConcurrentHashMap<>();
    private volatile long limitsFetchedAt = 0;
    private volatile boolean lastFromAres = false;
    private volatile boolean lastAresUnreachable = false;
    private volatile boolean lastFromCompose = false;
    private volatile boolean lastComposeUnreachable = false;

    @Inject
    public AresMarketClient(OkHttpClient httpClient, Gson gson, AccountHttp accountHttp)
    {
        this.httpClient = httpClient;
        this.gson = gson;
        this.accountHttp = accountHttp;
    }

    public AresMarketClient(OkHttpClient httpClient, Gson gson)
    {
        this(httpClient, gson, null);
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
        JsonObject root = postJson(ARES_SUGGESTION, gson.toJson(request), "suggestion",
            request.isContributeTrainingData());
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

    private JsonObject getJson(String url, String label)
    {
        return execute(new Request.Builder().url(url).header("User-Agent", UA).get().build(),
            httpClient, label);
    }

    public int cachedGeLimit(int itemId)
    {
        if (System.currentTimeMillis() - limitsFetchedAt >= LIMITS_TTL) return 0;
        Integer limit = geLimits.get(itemId);
        return limit != null ? limit : 0;
    }

    private JsonObject postJson(String url, String body, String label, boolean authed)
    {
        Request.Builder builder = new Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .post(RequestBody.create(JSON, body));
        if (authed && accountHttp != null && accountHttp.deviceToken() != null)
        {
            builder.header("Authorization", "Bearer " + accountHttp.deviceToken());
        }
        Request req = builder.build();
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
        List<Map<String, Object>> rows = rowsOf(root.get(arrayKey));
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rowsOf(JsonElement element)
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (element == null || !element.isJsonArray()) return rows;
        for (JsonElement row : element.getAsJsonArray())
        {
            if (row != null && row.isJsonObject()) rows.add((Map<String, Object>) gson.fromJson(row, Map.class));
        }
        return rows;
    }
}
