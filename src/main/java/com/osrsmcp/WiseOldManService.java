package com.osrsmcp;

import com.google.gson.Gson;
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
 * Read-only gateway to the Wise Old Man public API (wiseoldman.net) -- the
 * player's tracked progress: overview (EHP/EHB/time-to-max) and XP/level/boss-KC
 * GAINS over a period. Fills the gap our live/wiki tools can't: history + rates.
 * Blocking HTTP, so it MUST run off the client thread (routed as a network tool).
 */
@Slf4j
@Singleton
public class WiseOldManService
{
    private static final String USER_AGENT = "osrs-mcp-plugin/1.0 (github.com/nickbeddows-ctrl/osrs-mcp-plugin)";
    private static final String BASE = "https://api.wiseoldman.net/v2";
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L; // 5 min

    @Inject private OkHttpClient httpClient;
    @Inject private Gson gson;

    private static final class Cached { final long at; final String body; Cached(String b){ at=System.currentTimeMillis(); body=b; } }
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** Account overview: level/xp totals, EHP/EHB, time-to-max, and per-skill levels. */
    public Map<String, Object> getProfile(String username)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        if (username == null || username.isBlank()) { out.put("error", "Provide a username (or log in so it can be auto-detected)."); return out; }
        try
        {
            JsonObject root = get("/players/" + enc(username.trim()));
            out.put("username", str(root, "displayName", username));
            out.put("type", str(root, "type", "?"));
            out.put("build", str(root, "build", "?"));
            out.put("combat_level", num(root, "combatLevel"));
            out.put("total_xp", num(root, "exp"));
            out.put("ehp", num(root, "ehp"));
            out.put("ehb", num(root, "ehb"));
            out.put("time_to_max_hours", num(root, "ttm"));
            out.put("last_updated", str(root, "updatedAt", "?"));

            if (root.has("latestSnapshot") && root.get("latestSnapshot").isJsonObject())
            {
                JsonObject data = root.getAsJsonObject("latestSnapshot").getAsJsonObject("data");
                if (data != null && data.has("skills"))
                {
                    Map<String, Object> skills = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> e : data.getAsJsonObject("skills").entrySet())
                    {
                        JsonObject s = e.getValue().getAsJsonObject();
                        Map<String, Object> sk = new LinkedHashMap<>();
                        sk.put("level", num(s, "level"));
                        sk.put("xp", num(s, "experience"));
                        sk.put("rank", num(s, "rank"));
                        skills.put(e.getKey(), sk);
                    }
                    out.put("skills", skills);
                }
            }
            out.put("_source", "wiseoldman.net");
        }
        catch (NotFound nf) { out.put("error", "'" + username + "' is not tracked on Wise Old Man. Update it in the WOM plugin or at wiseoldman.net first."); }
        catch (Exception e) { out.put("error", "WOM profile lookup failed: " + e.getMessage()); }
        return out;
    }

    /** XP/level/boss-KC gains over a period (five_min, day, week, month, year). */
    public Map<String, Object> getGains(String username, String period)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        if (username == null || username.isBlank()) { out.put("error", "Provide a username (or log in so it can be auto-detected)."); return out; }
        String p = period == null || period.isBlank() ? "week" : period.trim().toLowerCase();
        try
        {
            JsonObject root = get("/players/" + enc(username.trim()) + "/gained?period=" + enc(p));
            out.put("username", username);
            out.put("period", p);
            out.put("from", str(root, "startsAt", "?"));
            out.put("to", str(root, "endsAt", "?"));
            JsonObject data = root.getAsJsonObject("data");
            out.put("skills",     gainsSection(data, "skills", "experience"));
            out.put("bosses",     gainsSection(data, "bosses", "kills"));
            out.put("activities", gainsSection(data, "activities", "score"));
            out.put("_hint", "Non-zero gains show what the player has actually been training/killing this period -- weight next-step advice toward or away from it as asked.");
            out.put("_source", "wiseoldman.net");
        }
        catch (NotFound nf) { out.put("error", "'" + username + "' is not tracked on Wise Old Man. Update it first."); }
        catch (Exception e) { out.put("error", "WOM gains lookup failed: " + e.getMessage()); }
        return out;
    }

    /** Extract {metric: gained} for entries with a non-zero gain, sorted desc. */
    private List<Object> gainsSection(JsonObject data, String section, String field)
    {
        List<Object> out = new ArrayList<>();
        if (data == null || !data.has(section) || !data.get(section).isJsonObject()) return out;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : data.getAsJsonObject(section).entrySet())
        {
            JsonObject m = e.getValue().getAsJsonObject();
            if (!m.has(field) || !m.get(field).isJsonObject()) continue;
            long gained = (long) num(m.getAsJsonObject(field), "gained");
            if (gained <= 0) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", e.getKey());
            r.put("gained", gained);
            rows.add(r);
        }
        rows.sort((a, b) -> Long.compare((Long) b.get("gained"), (Long) a.get("gained")));
        out.addAll(rows);
        return out;
    }

    // --- helpers ------------------------------------------------------------

    private static final class NotFound extends Exception {}

    private JsonObject get(String path) throws Exception
    {
        String url = BASE + path;
        Cached c = cache.get(url);
        String body;
        if (c != null && System.currentTimeMillis() - c.at < CACHE_TTL_MS)
        {
            body = c.body;
        }
        else
        {
            Request req = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
            try (Response resp = httpClient.newCall(req).execute())
            {
                if (resp.code() == 404) throw new NotFound();
                if (!resp.isSuccessful()) throw new Exception("HTTP " + resp.code());
                if (resp.body() == null) throw new Exception("Empty response");
                body = resp.body().string();
                cache.put(url, new Cached(body));
            }
        }
        return gson.fromJson(body, JsonObject.class);
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String str(JsonObject o, String k, String def) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def; }
    private static double num(JsonObject o, String k) { try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : 0; } catch (Exception e) { return 0; } }
}
