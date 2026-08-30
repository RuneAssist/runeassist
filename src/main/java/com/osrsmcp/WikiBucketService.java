package com.osrsmcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gateway to the OSRS Wiki's public Bucket API (Weird Gloop's structured-data
 * extension) plus a couple of MediaWiki helpers. Lets the AI query the wiki's
 * structured game data (combat achievements, monster/item stats, drop tables,
 * money-making guides, ...) without scraping.
 *
 * Read-only. All methods do blocking HTTP and MUST be called off the RuneLite
 * client thread (McpServer routes the wiki_* / bucket tools accordingly) -- they
 * need no game state, so this is safe.
 */
@Slf4j
@Singleton
public class WikiBucketService
{
    private static final String USER_AGENT   = "osrs-mcp-plugin/1.0 (github.com/nickbeddows-ctrl/osrs-mcp-plugin)";
    private static final String API          = "https://oldschool.runescape.wiki/api.php";
    private static final String WIKI         = "https://oldschool.runescape.wiki/w/";
    private static final int    BUCKET_NS     = 9592;   // Bucket: namespace
    private static final int    MAX_LIMIT     = 500;    // hard cap on rows
    private static final int    DEFAULT_LIMIT = 50;
    private static final int    MAX_BYTES     = 200_000; // response byte cap
    private static final long   CACHE_TTL_MS  = 6 * 60 * 60 * 1000L; // 6h

    @Inject private OkHttpClient httpClient;
    @Inject private Gson gson;

    private static final class Cached { final long at; final String body; Cached(String b){ this.at=System.currentTimeMillis(); this.body=b; } }
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    // --- public tool entry points -------------------------------------------

    /** List every bucket table name (namespace 9592). */
    public Map<String, Object> listBuckets()
    {
        Map<String, Object> out = new LinkedHashMap<>();
        try
        {
            String url = API + "?action=query&list=allpages&apnamespace=" + BUCKET_NS
                       + "&aplimit=500&format=json";
            JsonObject root = gson.fromJson(getCached(url), JsonObject.class);
            List<String> names = new ArrayList<>();
            for (JsonElement e : root.getAsJsonObject("query").getAsJsonArray("allpages"))
            {
                String t = e.getAsJsonObject().get("title").getAsString();
                // Return the queryable table name (lowercase, underscored) -- this is
                // exactly what wiki_bucket_query / wiki_bucket_schema expect.
                names.add(normalizeBucket(t.replaceFirst("^Bucket:", "")));
            }
            out.put("count", names.size());
            out.put("buckets", names);
            out.put("_hint", "Get a table's fields with wiki_bucket_schema, then query with wiki_bucket_query.");
        }
        catch (Exception e) { out.put("error", "Failed to list buckets: " + e.getMessage()); }
        return out;
    }

    /** Return the fields + types of one bucket. */
    public Map<String, Object> bucketSchema(String bucket)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        if (bucket == null || bucket.isBlank()) { out.put("error", "Provide a bucket name."); return out; }
        String page = "Bucket:" + normalizeBucket(bucket);
        try
        {
            String raw = getCached(WIKI + enc(page) + "?action=raw");
            JsonObject schema = gson.fromJson(raw, JsonObject.class);
            Map<String, Object> fields = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> en : schema.entrySet())
            {
                JsonObject def = en.getValue().getAsJsonObject();
                fields.put(en.getKey(), def.has("type") ? def.get("type").getAsString() : "?");
            }
            out.put("bucket", bucket);
            out.put("fields", fields);
            out.put("_hint", "Every row also has an implicit page_name key. Filter with where('page_name','<Name>').");
        }
        catch (Exception e) { out.put("error", "Failed to read schema for '" + bucket + "': " + e.getMessage()); }
        return out;
    }

    /**
     * Query a bucket. Either provide (bucket, select, where, limit, order) and the
     * Lua is built here, or provide a raw Lua query string for advanced use.
     */
    public Map<String, Object> bucketQuery(String bucket, List<String> select,
                                           Map<String, String> where, Integer limit,
                                           String order, String rawLua)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        int lim = clampLimit(limit);
        String lua;
        if (rawLua != null && !rawLua.isBlank())
        {
            lua = rawLua.trim();
        }
        else
        {
            if (bucket == null || bucket.isBlank()) { out.put("error", "Provide a bucket name (or a raw query)."); return out; }
            if (select == null || select.isEmpty()) { out.put("error", "Provide at least one field in 'select' (never fetch all fields)."); return out; }
            StringBuilder sb = new StringBuilder("bucket('").append(luaEsc(normalizeBucket(bucket))).append("')");
            sb.append(".select(");
            for (int i = 0; i < select.size(); i++) { if (i > 0) sb.append(","); sb.append("'").append(luaEsc(select.get(i))).append("'"); }
            sb.append(")");
            if (where != null)
                for (Map.Entry<String, String> w : where.entrySet())
                    sb.append(".where('").append(luaEsc(w.getKey())).append("','").append(luaEsc(w.getValue())).append("')");
            if (order != null && !order.isBlank())
                sb.append(".orderBy('").append(luaEsc(order.trim())).append("')");
            sb.append(".limit(").append(lim).append(").run()");
            lua = sb.toString();
        }

        try
        {
            String url = API + "?action=bucket&format=json&query=" + enc(lua);
            String body = getCapped(url);
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (root.has("error"))
            {
                out.put("error", root.get("error").getAsString());
                out.put("query", lua);
                return out;
            }
            JsonArray rows = root.has("bucket") && root.get("bucket").isJsonArray()
                           ? root.getAsJsonArray("bucket") : new JsonArray();
            List<Object> results = new ArrayList<>();
            for (JsonElement r : rows) results.add(gson.fromJson(r, Object.class));
            out.put("query", lua);
            out.put("count", results.size());
            if (results.size() >= lim) out.put("_note", "Result hit the limit of " + lim + " -- narrow with 'where' or raise 'limit' (max " + MAX_LIMIT + ").");
            out.put("rows", results);
        }
        catch (Exception e) { out.put("error", "Bucket query failed: " + e.getMessage()); out.put("query", lua); }
        return out;
    }

    /** Convenience wrapper: combat achievements, optionally filtered by tier and/or monster. */
    public Map<String, Object> combatAchievements(String tier, String monster)
    {
        Map<String, String> where = new LinkedHashMap<>();
        if (tier != null && !tier.isBlank())    where.put("tier", capitalise(tier.trim()));
        if (monster != null && !monster.isBlank()) where.put("monster", monster.trim());
        List<String> select = new ArrayList<>();
        select.add("name"); select.add("monster"); select.add("tier"); select.add("type"); select.add("task");
        return bucketQuery("combat_achievement", select, where.isEmpty() ? null : where, MAX_LIMIT, null, null);
    }

    // --- helpers ------------------------------------------------------------

    private int clampLimit(Integer limit)
    {
        if (limit == null) return DEFAULT_LIMIT;
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private String getCached(String url) throws Exception
    {
        Cached c = cache.get(url);
        if (c != null && System.currentTimeMillis() - c.at < CACHE_TTL_MS) return c.body;
        String body = get(url);
        cache.put(url, new Cached(body));
        return body;
    }

    /** Like getCached but enforces the response byte cap (for query results). */
    private String getCapped(String url) throws Exception
    {
        Cached c = cache.get(url);
        if (c != null && System.currentTimeMillis() - c.at < CACHE_TTL_MS) return c.body;
        Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful()) throw new Exception("HTTP " + response.code());
            if (response.body() == null) throw new Exception("Empty response body");
            byte[] bytes = response.body().bytes();
            if (bytes.length > MAX_BYTES)
                throw new Exception("Response too large (" + bytes.length + " bytes); tighten 'select'/'limit'");
            String body = new String(bytes, StandardCharsets.UTF_8);
            cache.put(url, new Cached(body));
            return body;
        }
    }

    private String get(String url) throws Exception
    {
        Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful()) throw new Exception("HTTP " + response.code());
            if (response.body() == null) throw new Exception("Empty response body");
            return response.body().string();
        }
    }

    /**
     * Normalise a bucket name to the queryable table form the Bucket API expects:
     * lowercase, spaces -> underscores. Accepts display names ("Infobox monster"),
     * page titles ("Infobox_monster") or the raw form ("infobox_monster") alike.
     * (MediaWiki auto-capitalises the first letter for the Bucket: page title, so
     * the lowercase form is also fine for schema lookups.)
     */
    private static String normalizeBucket(String s) { return s == null ? "" : s.trim().toLowerCase().replace(' ', '_'); }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /** Escape single quotes / backslashes so a value can't break out of the Lua string literal. */
    private static String luaEsc(String s) { return s.replace("\\", "\\\\").replace("'", "\\'"); }

    private static String capitalise(String s)
    {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
