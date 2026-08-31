package com.osrsmcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.vars.AccountType;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Layer 1 of the account planner. Loads the build-time bundled quest_data.json
 * (requirements + XP rewards, generated from the OSRS Wiki by
 * tools/gen-quest-data.mjs) and answers get_quest_rewards -- each quest's
 * requirements, XP rewards, and a live, account-accurate meets_requirements flag.
 *
 * Also exposes helpers (levelForXp / xpForLevel / questNameToLive) reused by the
 * Layer 2 simulator (project_plan).
 *
 * All live-state methods read the game and MUST run on the client thread.
 */
@Slf4j
@Singleton
public class QuestPlanService
{
    private static final String RESOURCE = "/com/osrsmcp/quest_data.json";

    @Inject private Client client;
    @Inject private Gson gson;

    // Parsed resource: quest name -> { requirements{...}, xp_rewards{...}, xp_choice?, unlocks? }
    private volatile Map<String, Map<String, Object>> questData;
    // normalized wiki quest name -> RuneLite Quest (built once from Quest.values())
    private volatile Map<String, Quest> liveByNorm;

    // --- data loading -------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> data()
    {
        Map<String, Map<String, Object>> d = questData;
        if (d != null) return d;
        synchronized (this)
        {
            if (questData != null) return questData;
            try (InputStream in = QuestPlanService.class.getResourceAsStream(RESOURCE))
            {
                if (in == null) { log.warn("quest_data.json not found on classpath"); questData = new LinkedHashMap<>(); return questData; }
                Type t = new TypeToken<Map<String, Object>>(){}.getType();
                Map<String, Object> root = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), t);
                questData = (Map<String, Map<String, Object>>) (Map<?, ?>) root.getOrDefault("quests", new LinkedHashMap<>());
                log.info("Loaded quest_data.json: {} quests (generated {})", questData.size(), root.get("_generated"));
            }
            catch (Exception e) { log.warn("Failed to load quest_data.json: {}", e.getMessage()); questData = new LinkedHashMap<>(); }
            return questData;
        }
    }

    /** Normalise a quest name for fuzzy matching between wiki names and RuneLite's Quest enum. */
    private static String norm(String s)
    {
        if (s == null) return "";
        String n = s.toLowerCase()
            .replace("&", "and")
            .replaceAll("[^a-z0-9 ]", " ")   // drop apostrophes/punctuation
            .replaceAll("\\s+", " ")
            .trim();
        // treat "desert treasure i" == "desert treasure"; strip a trailing lone roman "i"
        n = n.replaceAll(" i$", "");
        return n;
    }

    private Map<String, Quest> liveIndex()
    {
        Map<String, Quest> idx = liveByNorm;
        if (idx != null) return idx;
        idx = new HashMap<>();
        for (Quest q : Quest.values()) idx.put(norm(q.getName()), q);
        liveByNorm = idx;
        return idx;
    }

    /** Resolve a wiki quest name to the live RuneLite Quest, or null if none matches. */
    public Quest questNameToLive(String wikiName)
    {
        return liveIndex().get(norm(wikiName));
    }

    // --- get_quest_rewards --------------------------------------------------

    public Map<String, Object> buildQuestRewards()
    {
        if (client.getGameState() != GameState.LOGGED_IN)
            return err("Player is not logged in -- quest rewards need live account state.");

        Map<String, Map<String, Object>> d = data();
        if (d.isEmpty()) return err("quest_data.json missing or empty (run tools/gen-quest-data.mjs and rebuild).");

        boolean ironman = client.getAccountType() != null && client.getAccountType().isIronman();
        int questPoints = client.getVarpValue(VarPlayer.QUEST_POINTS);

        List<Map<String, Object>> entries = new ArrayList<>();
        int eligible = 0, completed = 0, unmatched = 0;
        for (Map.Entry<String, Map<String, Object>> e : d.entrySet())
        {
            String name = e.getKey();
            Map<String, Object> def = e.getValue();
            Map<String, Object> req = asMap(def.get("requirements"));

            Quest live = questNameToLive(name);
            QuestState state = live != null ? live.getState(client) : null;
            if (live == null) unmatched++;
            if (state == QuestState.FINISHED) completed++;

            List<String> unmet = new ArrayList<>();

            // skill requirements (+ ironman-only reqs when on an ironman account)
            Map<String, Object> skills = asMap(req.get("skills"));
            checkSkills(skills, unmet);
            if (ironman) checkSkills(asMap(req.get("skills_ironman")), unmet);

            // quest-point requirement
            int qpReq = asInt(req.get("quest_points"));
            if (questPoints < qpReq) unmet.add("quest points " + questPoints + "/" + qpReq);

            // prerequisite quests
            List<String> prereqs = asList(req.get("quests"));
            for (String pr : prereqs)
            {
                Quest pq = questNameToLive(pr);
                if (pq == null) continue; // can't verify; don't block on unknown
                if (pq.getState(client) != QuestState.FINISHED) unmet.add("quest: " + pr);
            }

            boolean meets = unmet.isEmpty();
            boolean done = state == QuestState.FINISHED;
            if (meets && !done) eligible++;

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("quest", name);
            out.put("state", state != null ? state.name().toLowerCase() : "unknown");
            out.put("requirements", req);
            out.put("xp_rewards", def.getOrDefault("xp_rewards", new LinkedHashMap<>()));
            if (def.containsKey("xp_choice")) out.put("xp_choice", def.get("xp_choice"));
            if (def.containsKey("unlocks"))   out.put("unlocks", def.get("unlocks"));
            out.put("meets_requirements", meets);
            if (!done && !unmet.isEmpty()) out.put("blocked_by", unmet);
            entries.add(out);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("account_type", ironman ? "ironman" : "main");
        result.put("quest_points", questPoints);
        result.put("total_quests", entries.size());
        result.put("completed_count", completed);
        result.put("eligible_now_count", eligible); // requirements met but not yet finished
        if (unmatched > 0) result.put("_unmatched_to_live", unmatched);
        result.put("quests", entries);
        return result;
    }

    private void checkSkills(Map<String, Object> skills, List<String> unmet)
    {
        for (Map.Entry<String, Object> s : skills.entrySet())
        {
            Skill sk = skillByName(s.getKey());
            int need = asInt(s.getValue());
            if (sk == null) continue;
            int have = client.getRealSkillLevel(sk);
            if (have < need) unmet.add(s.getKey() + " " + have + "/" + need);
        }
    }

    // --- shared XP helpers (used by project_plan too) -----------------------

    /** Cumulative XP required to reach a given level (1..99). */
    public static int xpForLevel(int level)
    {
        if (level <= 1) return 0;
        if (level > 99) level = 99;
        double sum = 0;
        for (int i = 1; i < level; i++) sum += Math.floor(i + 300 * Math.pow(2, i / 7.0));
        return (int) Math.floor(sum / 4);
    }

    /** Level (1..99) for a given XP amount. */
    public static int levelForXp(int xp)
    {
        int level = 1;
        while (level < 99 && xpForLevel(level + 1) <= xp) level++;
        return level;
    }

    // --- helpers ------------------------------------------------------------

    private static Skill skillByName(String name)
    {
        if (name == null) return null;
        try { return Skill.valueOf(name.trim().toUpperCase()); }
        catch (IllegalArgumentException ex) { return null; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o)
    {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<String> asList(Object o)
    {
        List<String> out = new ArrayList<>();
        if (o instanceof List) for (Object x : (List<Object>) o) out.add(String.valueOf(x));
        return out;
    }

    private static int asInt(Object o)
    {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return o == null ? 0 : (int) Double.parseDouble(o.toString()); }
        catch (NumberFormatException ex) { return 0; }
    }

    private static Map<String, Object> err(String msg)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }
}
