package com.osrsmcp;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.*;

/**
 * Reads herb patch states from the Time Tracking plugin's stored config.
 * Works even when the player is not near the patches.
 * Also reads live from varbits when available.
 */
@Slf4j
@Singleton
public class FarmingPatchService
{
    private static final String TT_GROUP = "timetracking";
    // Minutes per growth stage for herbs
    private static final int HERB_STAGE_MINUTES = 20;
    private static final int HERB_STAGES = 4;

    @Inject private net.runelite.api.Client client;
    @Inject private ConfigManager configManager;
    @Inject private net.runelite.client.game.ItemManager itemManager;
    @Inject private WikiPriceService wikiPriceService;
    @Inject private DestinationService destinationService;

    // Per-patch teleport hint for a herb run.
    private static final Map<String, String> PATCH_TELEPORT = new java.util.HashMap<>();
    static {
        PATCH_TELEPORT.put("Ardougne", "Ardougne cloak / Ardougne teleport");
        PATCH_TELEPORT.put("Catherby", "Camelot teleport (Seers) then run, or Catherby teleport");
        PATCH_TELEPORT.put("Civitas illa Fortis", "Civitas illa Fortis teleport (Varlamore)");
        PATCH_TELEPORT.put("Falador", "Falador teleport / Explorer's ring");
        PATCH_TELEPORT.put("Kourend", "Xeric's talisman (Xeric's Glade) or Hosidius teleport");
        PATCH_TELEPORT.put("Morytania", "Ectophial (then run to Canifis)");
        PATCH_TELEPORT.put("Troll Stronghold", "Trollheim / Stony basalt");
        PATCH_TELEPORT.put("Weiss", "Icy basalt");
        PATCH_TELEPORT.put("Farming Guild", "Skills necklace (Farming Guild) / Jewellery box");
        PATCH_TELEPORT.put("Harmony", "Harmony Island (POH portal / spellbook)");
    }

    // Herb patch definitions: name, regionID, varbitID
    private static final int[][] HERB_PATCHES = {
        // { regionID, varbitID } -- varbit IDs from VarbitID.FARMING_TRANSMIT_*
        {10548, 4774}, // Ardougne         (TRANSMIT_D)
        {11062, 4774}, // Catherby          (TRANSMIT_D)
        { 6192, 4774}, // Civitas illa Fortis (TRANSMIT_D)
        {12083, 4774}, // Falador           (TRANSMIT_D)
        { 6967, 4774}, // Kourend           (TRANSMIT_D)
        {14391, 4774}, // Morytania         (TRANSMIT_D)
        {11321, 4771}, // Troll Stronghold  (TRANSMIT_A)
        {11325, 4771}, // Weiss             (TRANSMIT_A)
        { 5021, 4775}, // Farming Guild     (TRANSMIT_E)
        {15148, 4772}, // Harmony           (TRANSMIT_B)
    };

    private static final String[] PATCH_NAMES = {
        "Ardougne", "Catherby", "Civitas illa Fortis", "Falador",
        "Kourend", "Morytania", "Troll Stronghold", "Weiss",
        "Farming Guild", "Harmony"
    };

    // Herb varbit decode table -- each entry: {minVal, maxVal, herbName, isHarvestable}
    // Pattern: 4 growing stages then 3 harvestable stages per herb, 7 values each
    // Diseased/dead ranges are 100+ for herbs (simplified: treat as diseased/dead)
    private static final Object[][] HERB_DECODE = {
        {0,  3,  "Weeds",        false},
        {4,  7,  "Guam",         false},
        {8,  10, "Guam",         true},
        {11, 14, "Marrentill",   false},
        {15, 17, "Marrentill",   true},
        {18, 21, "Tarromin",     false},
        {22, 24, "Tarromin",     true},
        {25, 28, "Harralander",  false},
        {29, 31, "Harralander",  true},
        {32, 35, "Ranarr",       false},
        {36, 38, "Ranarr",       true},
        {39, 42, "Toadflax",     false},
        {43, 45, "Toadflax",     true},
        {46, 49, "Irit",         false},
        {50, 52, "Irit",         true},
        {53, 56, "Avantoe",      false},
        {57, 59, "Avantoe",      true},
        {60, 63, "Huasca",       false},
        {64, 66, "Huasca",       true},
        {67, 67, "Weeds",        false},
        {68, 71, "Kwuarm",       false},
        {72, 74, "Kwuarm",       true},
        {75, 78, "Snapdragon",   false},
        {79, 81, "Snapdragon",   true},
        {82, 85, "Cadantine",    false},
        {86, 88, "Cadantine",    true},
        {89, 92, "Lantadyme",    false},
        {93, 95, "Lantadyme",    true},
        {96, 99, "Dwarf weed",   false},
        {100,102,"Dwarf weed",   true},
        {103,106,"Torstol",      false},
        {107,109,"Torstol",      true},
    };

    /**
     * Herb-run assistant: live patch states + a recommendation, a kit list, and each
     * patch's teleport hint and coordinates (for path_to). Herb patches only (the
     * common run); other patch types use get_farming_patches / live tracking.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buildFarmRun()
    {
        Map<String, Object> base = buildFarmingPatches();
        int ready   = (int) base.getOrDefault("ready", 0);
        int growing = (int) base.getOrDefault("growing", 0);
        int empty   = (int) base.getOrDefault("empty", 0);
        int other   = (int) base.getOrDefault("other", 0);

        List<Map<String, Object>> patches = new ArrayList<>();
        long soonest = Long.MAX_VALUE;
        for (Map<String, Object> p : (List<Map<String, Object>>) base.get("patches"))
        {
            String name = String.valueOf(p.get("location"));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("patch", name);
            entry.put("state", p.get("state"));
            if (p.containsKey("herb")) entry.put("herb", p.get("herb"));
            if (p.containsKey("est_minutes_remaining"))
            {
                Object rem = p.get("est_minutes_remaining");
                entry.put("est_minutes_remaining", rem);
                if (rem instanceof Number) soonest = Math.min(soonest, ((Number) rem).longValue());
            }
            entry.put("teleport", PATCH_TELEPORT.getOrDefault(name, "-"));
            int[] c = destinationService.herbPatch(name);
            if (c != null)
            {
                Map<String, Object> coord = new LinkedHashMap<>();
                coord.put("x", c[0]); coord.put("y", c[1]); coord.put("plane", c[2]);
                entry.put("coords", coord);
                entry.put("destination_name", name + " herb patch");
            }
            patches.add(entry);
        }

        String recommendation;
        if (ready > 0)
            recommendation = "Herb run recommended: " + ready + " patch(es) ready to harvest and replant"
                + (empty > 0 ? " (" + empty + " empty to plant too)." : ".");
        else if (empty > 0 && growing == 0)
            recommendation = empty + " empty patch(es) -- plant a new herb run now.";
        else if (growing > 0)
            recommendation = "Patches still growing" + (soonest != Long.MAX_VALUE ? "; earliest ready in ~" + soonest + " min." : ".");
        else
            recommendation = "No fresh data -- visit the patches (or check the Time Tracking plugin) to update states.";

        List<String> kit = new ArrayList<>(java.util.Arrays.asList(
            "Seed dibber, rake (or magic secateurs to skip weeds with 65 Farming build)", "Spade",
            "Herb seeds (best you can plant) x number of patches",
            "Ultracompost (or bottomless compost bucket)",
            "Magic secateurs (10% more yield)", "Teleports per patch (see each patch's 'teleport')"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", "ready " + ready + " / growing " + growing + " / empty " + empty + " / unknown " + other);
        out.put("recommendation", recommendation);
        out.put("items_to_bring", kit);
        out.put("patches", patches);
        out.put("_hint", "Use path_to with a patch's destination_name to draw the route to it. States are cached from last visit -- visit to refresh.");
        out.put("_note", "Herb patches only. Bring the highest-level herb seed you can plant; disease-free patches (e.g. Weiss, Trollheim, Hosidius with 100% favour) reduce losses.");
        return out;
    }

    public Map<String, Object> buildFarmingPatches()
    {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> patches = new ArrayList<>();
        int readyCount = 0, growingCount = 0, emptyCount = 0, otherCount = 0;

        for (int i = 0; i < HERB_PATCHES.length; i++)
        {
            int regionId = HERB_PATCHES[i][0];
            int varbitId = HERB_PATCHES[i][1];
            String patchName = PATCH_NAMES[i];

            String key = regionId + "." + varbitId;
            String stored = configManager.getRSProfileConfiguration(TT_GROUP, key);

            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("location", patchName);

            if (stored == null)
            {
                patch.put("state",   "unknown");
                patch.put("message", "Visit this patch to update its state");
                otherCount++;
            }
            else
            {
                String[] parts = stored.split(":");
                int varbitVal = 0;
                long storedTime = 0;
                try
                {
                    varbitVal  = Integer.parseInt(parts[0]);
                    if (parts.length > 1) storedTime = Long.parseLong(parts[1]);
                }
                catch (NumberFormatException ignored) {}

                String[] decoded = decodeHerbVarbit(varbitVal);
                String herbName   = decoded[0];
                String cropState  = decoded[1];

                patch.put("herb",  herbName);
                patch.put("state", cropState);

                long ageSeconds = Instant.now().getEpochSecond() - storedTime;
                patch.put("data_age_minutes", ageSeconds / 60);

                if ("growing".equals(cropState))
                {
                    int stage = varbitVal % 4; // approximate stage within herb
                    int stagesLeft = HERB_STAGES - stage;
                    long minutesLeft = (long) stagesLeft * HERB_STAGE_MINUTES - (ageSeconds / 60);
                    patch.put("est_minutes_remaining", Math.max(0, minutesLeft));
                    growingCount++;
                }
                else if ("harvestable".equals(cropState))
                {
                    // Attach GE price for the herb
                    attachHerbPrice(patch, herbName);
                    readyCount++;
                }
                else if ("empty".equals(cropState))
                {
                    emptyCount++;
                }
                else
                {
                    otherCount++;
                }
            }
            patches.add(patch);
        }

        result.put("ready",        readyCount);
        result.put("growing",      growingCount);
        result.put("empty",        emptyCount);
        result.put("other",        otherCount);
        result.put("patches",      patches);
        result.put("note",         "State is cached from last visit. Visit patches to refresh.");
        return result;
    }

    private String[] decodeHerbVarbit(int value)
    {
        // Diseased range (simplified -- actual ranges are 110-168 for diseased, 169+ for dead)
        if (value >= 110 && value <= 168) return new String[]{"Unknown herb", "diseased"};
        if (value >= 169 && value <= 228) return new String[]{"Unknown herb", "dead"};

        for (Object[] row : HERB_DECODE)
        {
            int min = (int) row[0], max = (int) row[1];
            if (value >= min && value <= max)
            {
                String herb  = (String) row[2];
                boolean harv = (boolean) row[3];
                if ("Weeds".equals(herb)) return new String[]{"Weeds", "empty"};
                return new String[]{herb, harv ? "harvestable" : "growing"};
            }
        }
        return new String[]{"Unknown", "unknown"};
    }

    // Cached grimy herb name -> item ID, built once from WikiPriceService mapping
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> herbNameToId =
        new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean herbMapBuilt = false;

    private Integer getHerbId(String grimyName)
    {
        if (!herbMapBuilt)
        {
            Map<Integer, WikiPriceService.ItemMeta> allMeta = wikiPriceService.getAllMeta();
            for (Map.Entry<Integer, WikiPriceService.ItemMeta> entry : allMeta.entrySet())
            {
                WikiPriceService.ItemMeta meta = entry.getValue();
                if (meta != null && meta.name != null && meta.name.toLowerCase().startsWith("grimy "))
                    herbNameToId.put(meta.name.toLowerCase(), entry.getKey());
            }
            herbMapBuilt = true;
        }
        return herbNameToId.get(grimyName.toLowerCase());
    }

    private void attachHerbPrice(Map<String, Object> patch, String herbName)
    {
        String grimyName = "grimy " + herbName.toLowerCase();
        Integer id = getHerbId(grimyName);
        if (id == null) return;
        WikiPriceService.PriceData pd = wikiPriceService.getPrice(id);
        if (pd != null && pd.low > 0)
        {
            patch.put("herb_ge_price", pd.low);
            patch.put("herb_item_name", "Grimy " + herbName.toLowerCase());
        }
    }
}
