package com.osrsmcp;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;

import net.runelite.client.RuneLite;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Named destinations (herb patches, banks, slayer masters, ...) with world
 * coordinates, so path_to can accept a name and the farm-run assistant can route
 * between patches. Bundled resource; coordinates are approximate (Shortest Path
 * snaps to the nearest walkable tile).
 */
@Slf4j
@Singleton
public class DestinationService
{
    private static final String RESOURCE = "/com/osrsmcp/destinations.json";

    @Inject private Gson gson;

    private static final File EXTERNAL = new File(new File(RuneLite.RUNELITE_DIR, "osrs-mcp"), "destinations.json");

    private volatile List<Map<String, Object>> destinations;
    private volatile String source = "?";

    public String getSource() { all(); return source; }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> all()
    {
        List<Map<String, Object>> d = destinations;
        if (d != null) return d;
        synchronized (this)
        {
            if (destinations != null) return destinations;
            Type t = new TypeToken<Map<String, Object>>(){}.getType();
            Map<String, Object> root = null;

            if (EXTERNAL.isFile())
            {
                try (Reader r = new InputStreamReader(new FileInputStream(EXTERNAL), StandardCharsets.UTF_8))
                { root = gson.fromJson(r, t); source = "external"; }
                catch (Exception e) { log.warn("Failed to read external destinations.json: {}", e.getMessage()); root = null; }
            }
            if (root == null)
            {
                try (InputStream in = DestinationService.class.getResourceAsStream(RESOURCE))
                {
                    if (in == null) { source = "resource-missing"; destinations = new ArrayList<>(); return destinations; }
                    root = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), t);
                    source = "bundled";
                }
                catch (Exception e) { log.warn("Failed to load bundled destinations.json: {}", e.getMessage()); source = "error:" + e.getMessage(); destinations = new ArrayList<>(); return destinations; }
            }

            Object list = root != null ? root.get("destinations") : null;
            destinations = list instanceof List ? (List<Map<String, Object>>) list : new ArrayList<>();
            return destinations;
        }
    }

    private static String norm(String s) { return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim(); }

    /** Resolve a name to {x,y,plane}, or null. Exact match first, then substring. */
    public int[] resolve(String name)
    {
        if (name == null) return null;
        String n = norm(name);
        Map<String, Object> best = null;
        for (Map<String, Object> d : all())
            if (norm((String) d.get("name")).equals(n)) { best = d; break; }
        if (best == null)
            for (Map<String, Object> d : all())
                if (norm((String) d.get("name")).contains(n) && !n.isEmpty()) { best = d; break; }
        if (best == null) return null;
        return new int[]{ asInt(best.get("x")), asInt(best.get("y")), asInt(best.get("plane")) };
    }

    /** Coordinates for a specific herb-patch name (matches the farm-run patch names). */
    public int[] herbPatch(String patchName)
    {
        return resolve(patchName + " herb patch");
    }

    public Map<String, Object> list(String category)
    {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> d : all())
        {
            if (category != null && !category.isBlank() && !category.equalsIgnoreCase(String.valueOf(d.get("category")))) continue;
            out.add(d);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("count", out.size());
        res.put("destinations", out);
        res.put("_source", source);
        res.put("_hint", "Pass a destination name to path_to to draw a route there. Coordinates are approximate.");
        return res;
    }

    private static int asInt(Object o) { return o instanceof Number ? ((Number) o).intValue() : 0; }
}
