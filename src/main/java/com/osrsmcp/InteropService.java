package com.osrsmcp;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
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
    @Inject private Gson gson;

    // Inventory Setups equipment-container slot indices (14 slots; 6/8/11 unused).
    private static final Map<String, Integer> EQUIP_SLOT = new LinkedHashMap<>();
    static {
        EQUIP_SLOT.put("head", 0); EQUIP_SLOT.put("cape", 1); EQUIP_SLOT.put("amulet", 2);
        EQUIP_SLOT.put("weapon", 3); EQUIP_SLOT.put("body", 4); EQUIP_SLOT.put("shield", 5);
        EQUIP_SLOT.put("legs", 7); EQUIP_SLOT.put("hands", 9); EQUIP_SLOT.put("feet", 10);
        EQUIP_SLOT.put("ring", 12); EQUIP_SLOT.put("ammo", 13);
    }

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

    // --- Inventory Setups (namespace "inventory-setups") --------------------
    // "get-setups" fills a mutable collection synchronously during post(); "view"
    // opens/equips a setup and filters the bank; "clear" clears the active setup.

    public Map<String, Object> getInventorySetups()
    {
        Map<String, Object> out = new LinkedHashMap<>();
        java.util.Set<String> names = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        clientThread.invokeLater(() ->
        {
            try
            {
                Map<String, Object> data = new HashMap<>();
                data.put("setups", names);                 // handler fills this in-place
                eventBus.post(new PluginMessage("inventory-setups", "get-setups", data));
            }
            catch (Exception e) { log.warn("get-setups failed: {}", e.getMessage()); }
            finally { done.complete(null); }
        });
        try { done.get(2, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}

        List<String> list = new ArrayList<>(names);
        java.util.Collections.sort(list);
        out.put("setups_available", true);
        out.put("count", list.size());
        out.put("setups", list);
        if (list.isEmpty())
            out.put("note", "No setups returned. Ensure the Inventory Setups plugin is installed/enabled (and that you have created some).");
        out.put("_hint", "These are the player's saved gear/inventory presets. Use view_inventory_setup to open one in-game.");
        return out;
    }

    public Map<String, Object> viewInventorySetup(String name, boolean clear)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        if (clear)
        {
            Map<String, Object> data = new HashMap<>();
            if (name != null && !name.isBlank()) data.put("setup", name);
            post(new PluginMessage("inventory-setups", "clear", data));
            out.put("cleared", true);
            return out;
        }
        if (name == null || name.isBlank()) { out.put("error", "Provide the setup name (from get_inventory_setups)."); return out; }
        Map<String, Object> data = new HashMap<>();
        data.put("setup", name);
        post(new PluginMessage("inventory-setups", "view", data));
        out.put("viewing", name);
        out.put("note", "Opened the setup in-game and filtered the bank (Inventory Setups plugin required). Does not move or equip items automatically.");
        return out;
    }

    // --- export_inventory_setup ---------------------------------------------
    // Build an Inventory Setups import string (InventorySetupPortable JSON). The AI
    // designs the loadout; the player pastes this into the plugin's Import button.
    // Safe: import is user-initiated and additive -- a bad string just errors.

    public Map<String, Object> exportInventorySetup(String name, Map<String, Object> equipment, List<Object> inventory, String notes)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        if (name == null || name.isBlank()) { out.put("error", "Provide a setup name."); return out; }

        // equipment: 14 slots, defaults to empty (-1)
        List<Object> eq = new ArrayList<>();
        for (int i = 0; i < 14; i++) eq.add(item(-1, 1));
        if (equipment != null)
            for (Map.Entry<String, Object> e : equipment.entrySet())
            {
                Integer idx = EQUIP_SLOT.get(e.getKey().toLowerCase().trim());
                int id = asInt(e.getValue());
                if (idx != null && id > 0) eq.set(idx, item(id, 1));
            }

        // inventory: 28 slots, fill in order from the provided list
        List<Object> inv = new ArrayList<>();
        if (inventory != null)
            for (Object o : inventory)
            {
                if (inv.size() >= 28) break;
                int id, q = 1;
                if (o instanceof Map) { Map<?, ?> m = (Map<?, ?>) o; id = asInt(m.get("id")); if (m.get("q") != null) q = asInt(m.get("q")); }
                else id = asInt(o);
                if (id > 0) inv.add(item(id, q));
            }
        while (inv.size() < 28) inv.add(item(-1, 1));

        Map<String, Object> setup = new LinkedHashMap<>();
        setup.put("inv", inv);
        setup.put("eq", eq);
        setup.put("rp", null); setup.put("bp", null); setup.put("qv", null);
        setup.put("afi", new LinkedHashMap<>());
        setup.put("name", name);
        setup.put("notes", notes != null && !notes.isBlank() ? notes : null);
        setup.put("hc", null); setup.put("dc", null);
        Map<String, Object> portable = new LinkedHashMap<>();
        portable.put("setup", setup);
        portable.put("layout", null);

        out.put("name", name);
        out.put("import_string", gson.toJson(portable));
        out.put("instructions", "In RuneLite: open the Inventory Setups panel, click the Import (down-arrow) button, and paste this string. It adds a new setup; it does not overwrite existing ones.");
        out.put("_note", "Item ids required (get them from the wiki infobox_item.item_id or get_item_prices). Best-effort format -- if import fails, tell me and I'll adjust.");
        return out;
    }

    private static Map<String, Object> item(int id, int q)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        if (q != 1) m.put("q", q);
        return m;
    }

    private static int asInt(Object o)
    {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return o == null ? 0 : (int) Double.parseDouble(o.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static int sizeOf(Object o)
    {
        return o instanceof List ? ((List<?>) o).size() : 0;
    }
}
