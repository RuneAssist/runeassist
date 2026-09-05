package com.runeassist.flip;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NPC-shop-flip candidates from Ares {@code GET /v1/shop-flips}.
 *
 * <p>The matching and margin rules used to live here, which meant they were published in this
 * repository and that every install paged the wiki's {@code storeline} table for itself. The
 * server pages it once, every half hour, for everyone.
 *
 * <p>Blocking HTTP; callers must invoke this off the RuneLite client thread, as they did when it
 * was paging the wiki directly.
 */
@Slf4j
@Singleton
public class ShopFlipService
{
    private static final String ARES_SHOP_FLIPS = "https://runeassist.ares-server.co.uk/v1/shop-flips";
    private static final String UA = "RuneAssist-flip/1.0 (github.com/RuneAssist/runeassist)";
    private static final Type ROW_LIST = new TypeToken<List<Map<String, Object>>>(){}.getType();

    @Inject private OkHttpClient httpClient;
    @Inject private Gson gson;

    private volatile long fetchedAt = 0;
    private volatile String lastError = null;

    /** Candidates best-first, or an empty list if the server is unreachable. */
    public List<Map<String, Object>> topShopFlips(int maxResults)
    {
        Request request = new Request.Builder()
            .url(ARES_SHOP_FLIPS + "?limit=" + Math.max(1, maxResults))
            .header("User-Agent", UA)
            .get()
            .build();
        try (Response r = httpClient.newCall(request).execute())
        {
            if (!r.isSuccessful() || r.body() == null)
            {
                lastError = "Shop data unavailable (HTTP " + r.code() + ")";
                return new ArrayList<>();
            }
            JsonObject root = gson.fromJson(r.body().charStream(), JsonObject.class);
            if (root == null || !root.has("candidates"))
            {
                lastError = "Shop data unavailable";
                return new ArrayList<>();
            }
            List<Map<String, Object>> rows = gson.fromJson(root.get("candidates"), ROW_LIST);
            lastError = null;
            fetchedAt = System.currentTimeMillis();
            return rows == null ? new ArrayList<>() : rows;
        }
        catch (Exception e)
        {
            lastError = "Shop data unavailable: " + e.getMessage();
            log.warn("Ares /v1/shop-flips failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Age of the last successful fetch, for the panel's "refreshed N ago" line. */
    public long cacheAgeMs()
    {
        return fetchedAt == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - fetchedAt;
    }

    public String lastError()
    {
        return lastError;
    }
}
