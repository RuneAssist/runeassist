package com.osrsmcp;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to OTHER RuneLite plugins over the EventBus PluginMessage bus -- the
 * supported inter-plugin API, no reflection.
 *
 *  - OSRS TCG: post ("osrstcg","query-owned-names"); it replies ("osrstcg",
 *    "owned-names") and pushes ("osrstcg","owned-names-changed") on any change.
 *    We cache the latest payload so get_tcg_unlocks is instant after the first.
 *  - Shortest Path: post ("shortestpath","path", {"target": WorldPoint}) to draw
 *    a route to a point in-game (read-only effect -- it never moves the player).
 *
 * Messages are posted on the client thread; the tool methods are called off it
 * (so the wait for a reply never blocks the game).
 */
@Slf4j
@Singleton
public class InteropService
{
    @Inject private EventBus eventBus;
    @Inject private ClientThread clientThread;

    private volatile Map<String, Object> tcgOwned;   // latest OSRS TCG payload
    private volatile long tcgUpdatedAt = 0;

    /** Called from the plugin's @Subscribe(PluginMessage) hook. Caches TCG replies/pushes. */
    void onPluginMessage(PluginMessage msg)
    {
        if (!"osrstcg".equals(msg.getNamespace())) return;
        String name = msg.getName();
        if ("owned-names".equals(name) || "owned-names-changed".equals(name))
        {
            Map<String, Object> data = msg.getData();
            tcgOwned = data != null ? new LinkedHashMap<>(data) : new LinkedHashMap<>();
            tcgUpdatedAt = System.currentTimeMillis();
        }
    }

    private void post(PluginMessage msg)
    {
        clientThread.invokeLater(() -> { try { eventBus.post(msg); } catch (Exception e) { log.warn("post {} failed: {}", msg.getName(), e.getMessage()); } });
    }

    // --- get_tcg_unlocks ----------------------------------------------------

    public Map<String, Object> getTcgUnlocks()
    {
        long before = tcgUpdatedAt;
        post(new PluginMessage("osrstcg", "query-owned-names", new HashMap<>()));

        // Wait briefly for the reply (TCG may answer next tick).
        long deadline = System.currentTimeMillis() + 1500;
        while (tcgUpdatedAt == before && System.currentTimeMillis() < deadline)
        {
            try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

        Map<String, Object> owned = tcgOwned;
        Map<String, Object> out = new LinkedHashMap<>();
        if (owned == null)
        {
            out.put("tcg_available", false);
            out.put("note", "No reply from the OSRS TCG plugin. Ensure it is installed, enabled, and a TCG profile is loaded.");
            return out;
        }
        out.put("tcg_available", true);
        out.put("owned_names",      owned.getOrDefault("ownedNames", java.util.Collections.emptyList()));
        out.put("owned_foil_names", owned.getOrDefault("ownedFoilNames", java.util.Collections.emptyList()));
        out.put("owned_item_ids",   owned.getOrDefault("ownedItemIds", java.util.Collections.emptyList()));
        out.put("owned_npc_ids",    owned.getOrDefault("ownedNpcIds", java.util.Collections.emptyList()));
        if (owned.containsKey("groupKey")) out.put("group_key", owned.get("groupKey"));
        out.put("owned_count", sizeOf(owned.get("ownedNames")));
        out.put("_hint", "In OSRS TCG, items/teleports/monsters are locked until their card is pulled. Use owned_names/owned_item_ids to only suggest content the player has unlocked, and flag locked things they'd need to pull.");
        return out;
    }

    // --- path_to ------------------------------------------------------------

    public Map<String, Object> pathTo(Integer x, Integer y, Integer plane, boolean clear)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        if (clear)
        {
            Map<String, Object> data = new HashMap<>();
            data.put("target", null);
            post(new PluginMessage("shortestpath", "path", data));
            out.put("cleared", true);
            out.put("note", "Asked Shortest Path to clear the current route.");
            return out;
        }
        if (x == null || y == null)
        {
            out.put("error", "Provide x and y (and optional plane) of the destination.");
            return out;
        }
        int p = plane == null ? 0 : plane;
        WorldPoint wp = new WorldPoint(x, y, p);
        Map<String, Object> data = new HashMap<>();
        data.put("target", wp);
        post(new PluginMessage("shortestpath", "path", data));
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("x", x); t.put("y", y); t.put("plane", p);
        out.put("path_requested", true);
        out.put("target", t);
        out.put("note", "Route drawn in-game via Shortest Path (requires that plugin installed + enabled). It only draws a path -- it does not move your character.");
        return out;
    }

    private static int sizeOf(Object o)
    {
        return o instanceof List ? ((List<?>) o).size() : 0;
    }
}
