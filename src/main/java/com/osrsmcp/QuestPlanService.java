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

import net.runelite.client.RuneLite;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Type;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    // Optional override dir: drop regenerated JSON here and call reload_planner_data (no restart).
    private static final File EXTERNAL_DIR = new File(RuneLite.RUNELITE_DIR, "osrs-mcp");

    @Inject private Client client;
    @Inject private Gson gson;
    @Inject private PlayerDataService playerDataService;

    // Parsed resource: quest name -> { requirements{...}, xp_rewards{...}, xp_choice?, unlocks? }
    private volatile Map<String, Map<String, Object>> questData;
    private volatile List<String> optimalOrder;
    private volatile String questSource = "?";
    private volatile String questGenerated = "?";
    // normalized wiki quest name -> RuneLite Quest (built once from Quest.values())
    private volatile Map<String, Quest> liveByNorm;

    /** For the panel: where quest data loaded from, and its generation timestamp. */
    public String getQuestSource()    { data(); return questSource; }
    public String getQuestGenerated() { data(); return questGenerated; }
    public int getQuestCount()        { return data().size(); }

    // --- data loading -------------------------------------------------------

    /** Read a JSON root object, preferring EXTERNAL_DIR/<filename> over the bundled resource. */
    private Map<String, Object> readRoot(String filename, String bundledResource, Consumer<String> sourceSink)
    {
        Type t = new TypeToken<Map<String, Object>>(){}.getType();
        File ext = new File(EXTERNAL_DIR, filename);
        if (ext.isFile())
        {
            try (Reader r = new InputStreamReader(new FileInputStream(ext), StandardCharsets.UTF_8))
            {
                Map<String, Object> root = gson.fromJson(r, t);
                if (root != null) { sourceSink.accept("external (" + ext.getAbsolutePath() + ")"); return root; }
            }
            catch (Exception e) { log.warn("Failed to read external {}: {} -- falling back to bundled", ext, e.getMessage()); }
        }
        try (InputStream in = QuestPlanService.class.getResourceAsStream(bundledResource))
        {
            if (in == null) { sourceSink.accept("missing"); return new LinkedHashMap<>(); }
            Map<String, Object> root = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), t);
            sourceSink.accept("bundled");
            return root != null ? root : new LinkedHashMap<>();
        }
        catch (Exception e) { log.warn("Failed to load {}: {}", bundledResource, e.getMessage()); sourceSink.accept("error"); return new LinkedHashMap<>(); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> data()
    {
        Map<String, Map<String, Object>> d = questData;
        if (d != null) return d;
        synchronized (this)
        {
            if (questData != null) return questData;
            Map<String, Object> root = readRoot("quest_data.json", RESOURCE, s -> questSource = s);
            questData = (Map<String, Map<String, Object>>) (Map<?, ?>) root.getOrDefault("quests", new LinkedHashMap<>());
            Object ord = root.get("optimal_order");
            optimalOrder = ord instanceof List ? asList(ord) : new ArrayList<>();
            questGenerated = String.valueOf(root.getOrDefault("_generated", "?"));
            log.info("Loaded quest_data ({}): {} quests, {} route entries (generated {})", questSource, questData.size(), optimalOrder.size(), questGenerated);
            return questData;
        }
    }

    /** Clear caches so the next call re-reads from EXTERNAL_DIR (or bundled). Used by reload_planner_data. */
    public synchronized Map<String, Object> reloadData()
    {
        questData = null; optimalOrder = null; trainingData = null;
        questSource = "?"; questGenerated = "?";
        data();
        Map<String, Object> td = trainingData();
        Object methods = td.get("methods");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reloaded", true);
        out.put("external_dir", EXTERNAL_DIR.getAbsolutePath());
        out.put("quest_data_source", questSource);
        out.put("quest_data_generated", questGenerated);
        out.put("quests_loaded", questData.size());
        out.put("route_entries", optimalOrder.size());
        out.put("training_methods_source", trainingSource);
        out.put("training_methods_loaded", methods instanceof List ? ((List<?>) methods).size() : 0);
        out.put("_note", "Drop regenerated quest_data.json / training_methods.json in external_dir and call this to apply them without restarting the client. Plugin CODE changes still need a restart.");
        return out;
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

    // --- project_plan (Layer 2 deterministic simulator) ---------------------

    /**
     * Deterministically apply a plan onto the live account and report the exact result.
     * args: complete_quests (list of quest names to assume done), train (skill -> target level).
     */
    public Map<String, Object> buildProjectPlan(Map<String, Object> args)
    {
        if (client.getGameState() != GameState.LOGGED_IN)
            return err("Player is not logged in -- project_plan simulates against live account state.");
        Map<String, Map<String, Object>> d = data();
        if (d.isEmpty()) return err("quest_data.json missing or empty (run tools/gen-quest-data.mjs and rebuild).");

        List<String> completeQuests = asList(args == null ? null : args.get("complete_quests"));
        Map<String, Object> train   = asMap(args == null ? null : args.get("train"));

        // 1. current XP + levels for every real skill
        Map<Skill, Integer> curXp = new LinkedHashMap<>();
        Map<Skill, Integer> curLvl = new LinkedHashMap<>();
        for (Skill sk : Skill.values())
        {
            if (sk == Skill.OVERALL) continue;
            int xp = client.getSkillExperience(sk);
            curXp.put(sk, xp);
            curLvl.put(sk, client.getRealSkillLevel(sk));
        }

        // 2. XP gained from completing the listed quests (fixed rewards only)
        Map<String, Integer> xpFromQuests = new LinkedHashMap<>();
        List<Map<String, Object>> choiceXp = new ArrayList<>();
        List<String> unknownQuests = new ArrayList<>();
        for (String qn : completeQuests)
        {
            Map<String, Object> def = d.get(qn);
            if (def == null) { def = matchQuestKey(d, qn); }
            if (def == null) { unknownQuests.add(qn); continue; }
            Map<String, Object> xr = asMap(def.get("xp_rewards"));
            for (Map.Entry<String, Object> e : xr.entrySet())
                xpFromQuests.merge(e.getKey(), asInt(e.getValue()), Integer::sum);
            if (def.get("xp_choice") instanceof List)
                for (Object c : (List<?>) def.get("xp_choice")) { Map<String, Object> cm = asMap(c); cm.put("from_quest", qn); choiceXp.add(cm); }
        }

        // 3. projected XP = current + quest XP; then trained up to target levels
        Map<Skill, Integer> projXp = new LinkedHashMap<>(curXp);
        for (Map.Entry<String, Integer> e : xpFromQuests.entrySet())
        {
            Skill sk = skillByName(e.getKey());
            if (sk != null) projXp.merge(sk, e.getValue(), Integer::sum);
        }
        Map<String, Object> xpStillToTrain = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : train.entrySet())
        {
            Skill sk = skillByName(e.getKey());
            int target = asInt(e.getValue());
            if (sk == null || target < 1 || target > 99) continue;
            int needXp = xpForLevel(target);
            int have = projXp.getOrDefault(sk, 0);
            if (needXp > have) { xpStillToTrain.put(e.getKey().toLowerCase(), needXp - have); projXp.put(sk, needXp); }
            else xpStillToTrain.put(e.getKey().toLowerCase(), 0);
        }

        // 4. resulting levels for every skill that changed
        Map<Skill, Integer> projLvl = new LinkedHashMap<>();
        Map<String, Object> resultingLevels = new LinkedHashMap<>();
        for (Skill sk : curXp.keySet())
        {
            int lvl = levelForXp(projXp.get(sk));
            projLvl.put(sk, lvl);
            if (lvl != curLvl.get(sk)) resultingLevels.put(sk.getName().toLowerCase(), lvl);
        }

        // 5. finished-quest sets (live now vs projected)
        Set<Quest> finishedNow = new java.util.HashSet<>();
        for (Quest q : Quest.values()) if (q.getState(client) == QuestState.FINISHED) finishedNow.add(q);
        Set<Quest> finishedPlan = new java.util.HashSet<>(finishedNow);
        for (String qn : completeQuests) { Quest lq = questNameToLive(qn); if (lq != null) finishedPlan.add(lq); }
        int qp = client.getVarpValue(VarPlayer.QUEST_POINTS);

        // 6. quests newly eligible under the plan (met under plan, not met now, not finished)
        List<String> newlyEligible = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : d.entrySet())
        {
            Quest live = questNameToLive(e.getKey());
            if (live != null && live.getState(client) == QuestState.FINISHED) continue;
            if (finishedPlan.contains(live) && !finishedNow.contains(live)) continue; // will have just done it
            Map<String, Object> req = asMap(e.getValue().get("requirements"));
            boolean now  = questMeets(req, curLvl, finishedNow, qp);
            boolean plan = questMeets(req, projLvl, finishedPlan, qp);
            if (plan && !now) newlyEligible.add(e.getKey());
        }
        java.util.Collections.sort(newlyEligible);

        // 7. diary regions that become requirement-complete under the plan
        List<Map<String, Object>> newlyCompleteDiaries = new ArrayList<>();
        for (Map<String, Object> reg : playerDataService.evaluateDiaryRegions(projLvl, finishedPlan))
        {
            int total = asInt(reg.get("total_tasks")), now = asInt(reg.get("met_now")), plan = asInt(reg.get("met_under_plan"));
            if (plan == total && now < total) newlyCompleteDiaries.add(reg);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applied", mapOf("complete_quests", completeQuests, "train", train));
        result.put("resulting_levels", resultingLevels);
        result.put("xp_gained_from_quests", xpFromQuests);
        result.put("xp_still_to_train", xpStillToTrain);
        if (!choiceXp.isEmpty()) result.put("unassigned_choice_xp", choiceXp);
        result.put("newly_eligible_quests", newlyEligible);
        result.put("newly_requirement_complete_diary_regions", newlyCompleteDiaries);
        if (!unknownQuests.isEmpty()) result.put("_unknown_quests", unknownQuests);
        result.put("_notes", "Deterministic: quest XP applied to live XP, levels recomputed, requirements re-evaluated. "
            + "Quest-point totals are not projected (per-quest QP rewards are not in the bundled data), so a quest blocked only by QP may not appear until you gain the points. "
            + "Skill-choice lamp XP is reported as unassigned_choice_xp, not auto-placed. Diary results are region-level (requirements met, not tier completion).");
        return result;
    }

    private boolean questMeets(Map<String, Object> req, Map<Skill, Integer> levels, Set<Quest> finished, int qp)
    {
        for (Map.Entry<String, Object> s : asMap(req.get("skills")).entrySet())
        {
            Skill sk = skillByName(s.getKey());
            if (sk == null) continue;
            if (levels.getOrDefault(sk, 1) < asInt(s.getValue())) return false;
        }
        if (qp < asInt(req.get("quest_points"))) return false;
        for (String pr : asList(req.get("quests")))
        {
            Quest pq = questNameToLive(pr);
            if (pq != null && !finished.contains(pq)) return false;
        }
        return true;
    }

    private Map<String, Object> matchQuestKey(Map<String, Map<String, Object>> d, String name)
    {
        for (Map.Entry<String, Map<String, Object>> e : d.entrySet())
            if (norm(e.getKey()).equals(norm(name))) return e.getValue();
        return null;
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1); m.put(k2, v2);
        return m;
    }

    // --- get_optimal_quest_route --------------------------------------------

    /**
     * The OSRS Wiki's Optimal Quest Guide ordering (baked into quest_data.json) as a
     * planning prior, annotated with live quest state. Returns the ordered route, how
     * far the account has progressed, and the next quests -- separating the next one
     * that is startable now from those still blocked (with the reasons).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buildOptimalQuestRoute(Map<String, Object> args)
    {
        Map<String, Map<String, Object>> qd = data();
        // optimal_order lives at the root of the resource, not under quests
        List<String> order = loadOptimalOrder();
        if (order.isEmpty()) return err("optimal_order missing from quest_data.json (regenerate with tools/gen-quest-data.mjs).");

        boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
        int qp = loggedIn ? client.getVarpValue(VarPlayer.QUEST_POINTS) : 0;
        boolean onlyRemaining = args != null && Boolean.TRUE.equals(args.get("only_remaining"));

        List<Map<String, Object>> route = new ArrayList<>();
        List<Map<String, Object>> nextBlocked = new ArrayList<>();
        Map<String, Object> nextStartable = null;
        int completed = 0, pos = 0;
        for (String name : order)
        {
            pos++;
            Quest live = questNameToLive(name);
            QuestState state = live != null ? live.getState(client) : null;
            boolean done = state == QuestState.FINISHED;
            if (done) completed++;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("position", pos);
            row.put("quest", name);
            row.put("state", state != null ? state.name().toLowerCase() : "unknown");

            // for not-yet-finished quests, live-check whether requirements are met now
            if (loggedIn && !done && live != null)
            {
                Map<String, Object> def = qd.get(name);
                if (def == null) def = matchQuestKey(qd, name);
                if (def != null)
                {
                    Map<String, Object> req = asMap(def.get("requirements"));
                    List<String> unmet = new ArrayList<>();
                    checkSkills(asMap(req.get("skills")), unmet);
                    if (qp < asInt(req.get("quest_points"))) unmet.add("quest points " + qp + "/" + asInt(req.get("quest_points")));
                    for (String pr : asList(req.get("quests")))
                    {
                        Quest pqq = questNameToLive(pr);
                        if (pqq != null && pqq.getState(client) != QuestState.FINISHED) unmet.add("quest: " + pr);
                    }
                    row.put("meets_requirements", unmet.isEmpty());
                    if (!unmet.isEmpty()) row.put("blocked_by", unmet);
                    if (nextStartable == null && unmet.isEmpty()) nextStartable = row;
                    else if (!unmet.isEmpty() && nextBlocked.size() < 10) nextBlocked.add(row);
                }
            }

            if (!(onlyRemaining && done)) route.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "OSRS Wiki Optimal Quest Guide (a recommended ordering, not account-specific -- adapt to the player's goal).");
        result.put("route_length", order.size());
        result.put("completed_in_route", completed);
        if (loggedIn)
        {
            if (nextStartable != null) result.put("next_startable_now", nextStartable);
            if (!nextBlocked.isEmpty()) result.put("next_blocked", nextBlocked);
        }
        result.put("route", route);
        return result;
    }

    private List<String> loadOptimalOrder()
    {
        data();   // populates optimalOrder alongside questData from the same root
        List<String> o = optimalOrder;
        return o != null ? o : new ArrayList<>();
    }

    // --- get_training_methods (Layer 3) -------------------------------------

    private static final String TRAINING_RESOURCE = "/com/osrsmcp/training_methods.json";
    private volatile Map<String, Object> trainingData;
    private volatile String trainingSource = "?";

    private Map<String, Object> trainingData()
    {
        Map<String, Object> t = trainingData;
        if (t != null) return t;
        synchronized (this)
        {
            if (trainingData != null) return trainingData;
            trainingData = readRoot("training_methods.json", TRAINING_RESOURCE, s -> trainingSource = s);
            return trainingData;
        }
    }

    /** Curated training methods with rough XP/hr, annotated live with whether the account meets each method's requirements. args: skill (optional filter). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buildTrainingMethods(Map<String, Object> args)
    {
        Map<String, Object> td = trainingData();
        Object methodsObj = td.get("methods");
        if (!(methodsObj instanceof List)) return err("training_methods.json missing or empty.");

        boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
        String filter = args != null && args.get("skill") != null ? args.get("skill").toString().trim().toLowerCase() : null;

        List<Object> out = new ArrayList<>();
        for (Object mo : (List<Object>) methodsObj)
        {
            Map<String, Object> m = asMap(mo);
            String skill = String.valueOf(m.get("skill")).toLowerCase();
            if (filter != null && !skill.equals(filter)) continue;

            Map<String, Object> entry = new LinkedHashMap<>(m);
            if (loggedIn)
            {
                List<String> unmet = new ArrayList<>();
                checkSkills(asMap(asMap(m.get("requirements")).get("skills")), unmet);
                entry.put("meets_requirements", unmet.isEmpty());
                if (!unmet.isEmpty()) entry.put("blocked_by", unmet);
                Skill sk = skillByName(skill);
                if (sk != null) entry.put("current_level", client.getRealSkillLevel(sk));
            }
            out.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dated", td.get("_dated"));
        result.put("disclaimer", "Approximate XP/hr; varies with gear, efficiency and game updates. meets_requirements/current_level are live when logged in.");
        if (filter != null) result.put("skill", filter);
        result.put("count", out.size());
        result.put("methods", out);
        return result;
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
