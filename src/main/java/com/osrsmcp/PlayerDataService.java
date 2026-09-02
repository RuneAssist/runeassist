package com.osrsmcp;

import lombok.extern.slf4j.Slf4j;

import net.runelite.api.*;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Prayer;
import net.runelite.api.NPC;
import net.runelite.api.Actor;
import net.runelite.api.VarPlayer;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.config.ConfigManager;
import net.runelite.api.WorldType;
import net.runelite.api.Varbits;
import net.runelite.api.VarPlayer;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.achievementdiary.GenericDiaryRequirement;
import net.runelite.client.plugins.achievementdiary.Requirement;
import net.runelite.client.plugins.achievementdiary.SkillRequirement;
import net.runelite.client.plugins.achievementdiary.diaries.ArdougneDiaryRequirement;
import net.runelite.client.plugins.achievementdiary.diaries.DesertDiaryRequirement;
import net.runelite.client.plugins.achievementdiary.diaries.FaladorDiaryRequirement;
import net.runelite.client.plugins.achievementdiary.diaries.FremennikDiaryRequirement;
import net.runelite.client.plugins.achievementdiary.diaries.KandarinDiaryRequirement;
import net.runelite.client.plugins.achievementdiary.diaries.KaramjaDiaryRequirement;
import net.runelite.client.plugins.achievementdiary.diaries.KourendDiaryRequirement;
import net.runelite.client.plugins.achievementdiary.diaries.LumbridgeDiaryRequirement;
import net.runelite.client.plugins.achievementdiary.diaries.MorytaniaDiaryRequirement;
import net.runelite.client.plugins.achievementdiary.diaries.VarrockDiaryRequirement;
import net.runelite.client.plugins.achievementdiary.diaries.WesternDiaryRequirement;
import net.runelite.client.plugins.achievementdiary.diaries.WildernessDiaryRequirement;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;

@Singleton
@Slf4j
public class PlayerDataService
{
    @Inject private Client client;
    @Inject private ItemManager itemManager;
    @Inject private OsrsMcpConfig config;
    @Inject private PluginManager pluginManager;
    @Inject private okhttp3.OkHttpClient httpClient;
    @Inject private EquipmentStatsService equipmentStatsService;
    @Inject private DropTableService dropTableService;
    @Inject private FarmingPatchService farmingPatchService;
    @Inject private CacheWriter cacheWriter;
    @Inject private ConfigManager configManager;
    @Inject private WikiPriceService wikiPriceService;
    @Inject private SessionTracker sessionTracker;

    // Bank cache -- populated when player opens their bank
    private volatile Item[] cachedBankItems = null;
    // Seed vault cache -- populated when player opens seed vault
    private volatile Item[] cachedSeedVaultItems = null;

    public boolean isLoggedIn()
    {
        return client.getGameState() == GameState.LOGGED_IN;
    }

    /** The logged-in player's RSN, or null. Reads the client -- call on the client thread. */
    public String currentUsername()
    {
        if (!isLoggedIn()) return null;
        net.runelite.api.Player p = client.getLocalPlayer();
        return p != null ? p.getName() : null;
    }

    /**
     * get_all -- lightweight snapshot of essential context only.
     * Heavy tools (bank contents, drop tables, BiS comparison etc.)
     * are intentionally excluded -- the AI should call them on demand.
     */
    public Map<String, Object> buildSnapshot()
    {
        Map<String, Object> data = new LinkedHashMap<>();
        if (!isLoggedIn()) { data.put("error", "Player is not logged in"); return data; }
        // Core character state
        if (config.shareStats())     data.put("stats",     buildStats());
        if (config.shareEquipment()) data.put("equipment", buildEquipment());
        if (config.shareInventory()) data.put("inventory", buildInventory());
        if (config.shareLocation())  data.put("location",  buildLocation());
        // Progress
        data.put("quests",        buildQuestStates());
        data.put("diaries",       buildDiaryStates());
        data.put("collection_log",buildCollectionLog());
        data.put("prayers",       buildPrayers());
        // Active content
        data.put("slayer",        buildSlayerTask());
        data.put("clue",          buildClueScroll());
        // Economy snapshot (summary only -- not full contents)
        data.put("bank_summary",  buildBankSummary());
        data.put("ge_offers",     buildGeOffers());
        // World context
        data.put("world",         buildWorldInfo());
        return data;
    }

    /**
     * get_session_summary -- what the player has done since logging in this session:
     * XP gained per skill (sorted), total XP gained, levels gained, and minutes played.
     * Runs on the client thread.
     */
    public Map<String, Object> buildSessionSummary()
    {
        if (!isLoggedIn()) return errorMap("Player is not logged in");
        if (!sessionTracker.isCaptured()) return errorMap("Session baseline not captured yet; try again in a moment");

        Map<String, Object> result = new LinkedHashMap<>();
        long minutes = (System.currentTimeMillis() - sessionTracker.startTimeMs()) / 60000;
        result.put("session_minutes", minutes);

        long totalGained = 0;
        List<Map<String, Object>> perSkill = new ArrayList<>();
        for (Skill s : Skill.values())
        {
            if (s == Skill.OVERALL) continue;
            Integer start = sessionTracker.startXp(s);
            if (start == null) continue;
            int now = client.getSkillExperience(s);
            int gained = now - start;
            if (gained <= 0) continue;
            int startLvl = net.runelite.api.Experience.getLevelForXp(start);
            int nowLvl   = net.runelite.api.Experience.getLevelForXp(now);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("skill", s.getName());
            row.put("xp_gained", gained);
            row.put("levels_gained", nowLvl - startLvl);
            perSkill.add(row);
            totalGained += gained;
        }
        // Sort by xp_gained descending so the biggest gains are first.
        perSkill.sort((a, b) -> Long.compare(((Number) b.get("xp_gained")).longValue(),
                                             ((Number) a.get("xp_gained")).longValue()));
        result.put("total_xp_gained", totalGained);
        result.put("skills", perSkill);
        if (perSkill.isEmpty()) result.put("note", "No XP gained yet this session.");
        return result;
    }

    public Map<String, Object> buildStats()
    {
        if (!isLoggedIn()) return errorMap("Player is not logged in");
        Map<String, Object> result = new LinkedHashMap<>();
        if (config.shareUsername()) result.put("username", client.getLocalPlayer().getName());
        result.put("combat_level", client.getLocalPlayer().getCombatLevel());

        // Account type -- critical context for all advice
        net.runelite.api.vars.AccountType accountType = client.getAccountType();
        result.put("account_type", accountType.name().toLowerCase());
        boolean isAnyIronman = accountType != net.runelite.api.vars.AccountType.NORMAL;
        boolean isUim        = accountType == net.runelite.api.vars.AccountType.ULTIMATE_IRONMAN;
        boolean isHcim       = accountType == net.runelite.api.vars.AccountType.HARDCORE_IRONMAN
                            || accountType == net.runelite.api.vars.AccountType.HARDCORE_GROUP_IRONMAN;
        boolean isGroupIm    = accountType == net.runelite.api.vars.AccountType.GROUP_IRONMAN
                            || accountType == net.runelite.api.vars.AccountType.HARDCORE_GROUP_IRONMAN;
        result.put("is_ironman",       isAnyIronman);
        result.put("is_uim",           isUim);
        result.put("is_hcim",          isHcim);
        result.put("is_group_ironman",  isGroupIm);
        if (isAnyIronman)
        {
            List<String> restrictions = new ArrayList<>();
            restrictions.add("cannot trade with other players");
            restrictions.add("cannot receive items from other players");
            restrictions.add("GE access limited to bonds only -- cannot buy or sell any other items");
            if (isUim) restrictions.add("no bank access -- must carry all items");
            if (isHcim) restrictions.add("hardcore: unsafe death removes HC status");
            if (isGroupIm) restrictions.add("can trade items within group members only");
            result.put("ironman_restrictions", restrictions);
        }
        Map<String, Map<String, Object>> skills = new LinkedHashMap<>();
        for (Skill skill : Skill.values())
        {
            if (skill == Skill.OVERALL) continue;
            int level   = client.getRealSkillLevel(skill);
            int boosted = client.getBoostedSkillLevel(skill);
            int xp      = client.getSkillExperience(skill);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("level", level);
            if (boosted != level) s.put("boosted_level", boosted);
            s.put("xp", xp);
            s.put("xp_to_next_level", xpToNextLevel(xp, level));
            skills.put(skill.getName().toLowerCase(), s);
        }
        result.put("skills", skills);
        return result;
    }

    public List<Map<String, Object>> buildEquipment()
    {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!isLoggedIn()) return result;
        ItemContainer container = client.getItemContainer(InventoryID.EQUIPMENT);
        if (container == null) return result;
        String[] slotNames = {"head","cape","amulet","weapon","body","shield","unused","legs","gloves","boots","ring","ammo"};
        Item[] items = container.getItems();
        for (int i = 0; i < items.length; i++)
        {
            if (items[i].getId() <= 0) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("slot", (i < slotNames.length) ? slotNames[i] : "slot_" + i);
            entry.put("id", items[i].getId());
            entry.put("name", itemManager.getItemComposition(items[i].getId()).getName());
            entry.put("quantity", items[i].getQuantity());
            result.add(entry);
        }
        return result;
    }

    public List<Map<String, Object>> buildInventory()
    {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!isLoggedIn()) return result;
        ItemContainer container = client.getItemContainer(InventoryID.INVENTORY);
        if (container == null) return result;
        Map<String, Map<String, Object>> collapsed = new LinkedHashMap<>();
        for (Item item : container.getItems())
        {
            if (item.getId() <= 0) continue;
            String name = itemManager.getItemComposition(item.getId()).getName();
            if (collapsed.containsKey(name))
            {
                int qty = (int) collapsed.get(name).get("quantity");
                collapsed.get(name).put("quantity", qty + item.getQuantity());
            }
            else
            {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", item.getId()); entry.put("name", name); entry.put("quantity", item.getQuantity());
                collapsed.put(name, entry);
            }
        }
        result.addAll(collapsed.values());
        return result;
    }

    public Map<String, Object> buildLocation()
    {
        if (!isLoggedIn()) return errorMap("Player is not logged in");
        Map<String, Object> result = new LinkedHashMap<>();
        WorldPoint wp = client.getLocalPlayer().getWorldLocation();
        result.put("x", wp.getX()); result.put("y", wp.getY()); result.put("plane", wp.getPlane());
        result.put("region_id", wp.getRegionID());
        String area = RegionNames.getName(wp.getRegionID());
        if (area != null) result.put("area", area);
        return result;
    }

    public Map<String, Object> buildQuestStates()
    {
        if (!isLoggedIn()) return errorMap("Player is not logged in");
        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> finished    = new ArrayList<>();
        List<Map<String, Object>> inProgress  = new ArrayList<>();
        List<Map<String, Object>> notStarted  = new ArrayList<>();
        for (Quest quest : Quest.values())
        {
            QuestState state = quest.getState(client);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", quest.getName());
            entry.put("state", state.name().toLowerCase());
            switch (state)
            {
                case FINISHED:   finished.add(entry);  break;
                case IN_PROGRESS: inProgress.add(entry); break;
                case NOT_STARTED: notStarted.add(entry); break;
            }
        }

        result.put("quest_points", client.getVarpValue(VarPlayer.QUEST_POINTS));
        // Write quest cache
        try
        {
            List<String> doneNames = new ArrayList<>();
            List<String> ipNames   = new ArrayList<>();
            List<String> nsNames   = new ArrayList<>();
            for (Map<String, Object> q : finished)   doneNames.add((String) q.get("name"));
            for (Map<String, Object> q : inProgress) ipNames.add((String) q.get("name"));
            for (Map<String, Object> q : notStarted) nsNames.add((String) q.get("name"));
            cacheWriter.writeQuests(client.getVarpValue(VarPlayer.QUEST_POINTS), doneNames, ipNames, nsNames);
        }
        catch (Exception ignored) {}
        result.put("completed_count", finished.size());
        result.put("in_progress_count", inProgress.size());
        result.put("not_started_count", notStarted.size());
        result.put("completed", finished);
        result.put("in_progress", inProgress);
        result.put("not_started", notStarted);
        return result;
    }

    public Map<String, Object> buildDiaryStates()
    {
        if (!isLoggedIn()) return errorMap("Player is not logged in");

        // Each entry: region -> { easy, medium, hard, elite } (1 = complete, 0 = not)
        int[][] diaries = {
            {Varbits.DIARY_ARDOUGNE_EASY,  Varbits.DIARY_ARDOUGNE_MEDIUM,  Varbits.DIARY_ARDOUGNE_HARD,  Varbits.DIARY_ARDOUGNE_ELITE},
            {Varbits.DIARY_DESERT_EASY,    Varbits.DIARY_DESERT_MEDIUM,    Varbits.DIARY_DESERT_HARD,    Varbits.DIARY_DESERT_ELITE},
            {Varbits.DIARY_FALADOR_EASY,   Varbits.DIARY_FALADOR_MEDIUM,   Varbits.DIARY_FALADOR_HARD,   Varbits.DIARY_FALADOR_ELITE},
            {Varbits.DIARY_FREMENNIK_EASY, Varbits.DIARY_FREMENNIK_MEDIUM, Varbits.DIARY_FREMENNIK_HARD, Varbits.DIARY_FREMENNIK_ELITE},
            {Varbits.DIARY_KANDARIN_EASY,  Varbits.DIARY_KANDARIN_MEDIUM,  Varbits.DIARY_KANDARIN_HARD,  Varbits.DIARY_KANDARIN_ELITE},
            {Varbits.DIARY_KARAMJA_EASY,   Varbits.DIARY_KARAMJA_MEDIUM,   Varbits.DIARY_KARAMJA_HARD,   Varbits.DIARY_KARAMJA_ELITE},
            {Varbits.DIARY_KOUREND_EASY,   Varbits.DIARY_KOUREND_MEDIUM,   Varbits.DIARY_KOUREND_HARD,   Varbits.DIARY_KOUREND_ELITE},
            {Varbits.DIARY_LUMBRIDGE_EASY, Varbits.DIARY_LUMBRIDGE_MEDIUM, Varbits.DIARY_LUMBRIDGE_HARD, Varbits.DIARY_LUMBRIDGE_ELITE},
            {Varbits.DIARY_MORYTANIA_EASY, Varbits.DIARY_MORYTANIA_MEDIUM, Varbits.DIARY_MORYTANIA_HARD, Varbits.DIARY_MORYTANIA_ELITE},
            {Varbits.DIARY_VARROCK_EASY,   Varbits.DIARY_VARROCK_MEDIUM,   Varbits.DIARY_VARROCK_HARD,   Varbits.DIARY_VARROCK_ELITE},
            {Varbits.DIARY_WESTERN_EASY,   Varbits.DIARY_WESTERN_MEDIUM,   Varbits.DIARY_WESTERN_HARD,   Varbits.DIARY_WESTERN_ELITE},
            {Varbits.DIARY_WILDERNESS_EASY,Varbits.DIARY_WILDERNESS_MEDIUM,Varbits.DIARY_WILDERNESS_HARD,Varbits.DIARY_WILDERNESS_ELITE},
        };
        String[] regions = {"ardougne","desert","falador","fremennik","kandarin","karamja",
                             "kourend","lumbridge","morytania","varrock","western_provinces","wilderness"};
        String[] tiers   = {"easy","medium","hard","elite"};

        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < regions.length; i++)
        {
            Map<String, Object> region = new LinkedHashMap<>();
            for (int j = 0; j < tiers.length; j++)
                region.put(tiers[j], client.getVarbitValue(diaries[i][j]) == 1);
            result.put(regions[i], region);
        }
        return result;
    }

    // --- Achievement Diary task-level requirements --------------------------------
    // Uses RuneLite's own bundled diary requirement data. Each requirement is
    // live-checked against the client via satisfiesRequirement(), so "met" reflects
    // the real account. Note: this reports whether you MEET a task's requirements,
    // not whether the task is already ticked off -- cross-reference get_diary_states
    // for tier completion.

    private LinkedHashMap<String, GenericDiaryRequirement> diaryRequirementSources()
    {
        LinkedHashMap<String, GenericDiaryRequirement> regions = new LinkedHashMap<>();
        regions.put("ardougne",          new ArdougneDiaryRequirement());
        regions.put("desert",            new DesertDiaryRequirement());
        regions.put("falador",           new FaladorDiaryRequirement());
        regions.put("fremennik",         new FremennikDiaryRequirement());
        regions.put("kandarin",          new KandarinDiaryRequirement());
        regions.put("karamja",           new KaramjaDiaryRequirement());
        regions.put("kourend",           new KourendDiaryRequirement());
        regions.put("lumbridge",         new LumbridgeDiaryRequirement());
        regions.put("morytania",         new MorytaniaDiaryRequirement());
        regions.put("varrock",           new VarrockDiaryRequirement());
        regions.put("western_provinces", new WesternDiaryRequirement());
        regions.put("wilderness",        new WildernessDiaryRequirement());
        return regions;
    }

    // DiaryRequirement is package-private in RuneLite, so its instances are reached
    // via reflection on their public getTask()/getRequirements() methods. Returns a
    // list of {String task, List<Requirement> requirements}.
    private List<Object[]> diaryTasks(GenericDiaryRequirement src)
    {
        List<Object[]> out = new ArrayList<>();
        java.lang.reflect.Method mTask = null, mReq = null;
        Set<?> set = src.getRequirements();
        for (Object dr : set)
        {
            try
            {
                if (mTask == null)
                {
                    mTask = dr.getClass().getMethod("getTask");         mTask.setAccessible(true);
                    mReq  = dr.getClass().getMethod("getRequirements"); mReq.setAccessible(true);
                }
                String task = (String) mTask.invoke(dr);
                @SuppressWarnings("unchecked")
                List<Requirement> reqs = (List<Requirement>) mReq.invoke(dr);
                out.add(new Object[]{ task, reqs });
            }
            catch (Exception ignored) {}
        }
        return out;
    }

    /**
     * Evaluate each diary region's task requirements twice: against the live account
     * ("met_now") and against a projected state ("met_under_plan") -- projected skill
     * levels and a projected set of finished quests. Used by project_plan to find
     * regions that become requirement-complete under a plan. Region granularity: the
     * RuneLite diary requirement classes do not expose per-tier task splits.
     * Runs on the client thread (reads live state for non-skill/quest requirements).
     */
    public List<Map<String, Object>> evaluateDiaryRegions(Map<Skill, Integer> projLevels, Set<Quest> projFinished)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, GenericDiaryRequirement> entry : diaryRequirementSources().entrySet())
        {
            int total = 0, metNow = 0, metPlan = 0;
            for (Object[] dr : diaryTasks(entry.getValue()))
            {
                total++;
                @SuppressWarnings("unchecked")
                List<Requirement> reqs = (List<Requirement>) dr[1];
                boolean allNow = true, allPlan = true;
                for (Requirement r : reqs)
                {
                    boolean now;
                    try { now = r.satisfiesRequirement(client); } catch (Exception e) { now = false; }
                    boolean plan = satisfiesUnderPlan(r, projLevels, projFinished, now);
                    if (!now)  allNow = false;
                    if (!plan) allPlan = false;
                }
                if (allNow)  metNow++;
                if (allPlan) metPlan++;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("region", entry.getKey());
            m.put("total_tasks", total);
            m.put("met_now", metNow);
            m.put("met_under_plan", metPlan);
            out.add(m);
        }
        return out;
    }

    // Evaluate a single diary Requirement under a projected plan: SkillRequirements use
    // projected levels; quest requirements use the projected finished set; anything else
    // falls back to its live value (unchanged by the plan).
    private boolean satisfiesUnderPlan(Requirement r, Map<Skill, Integer> projLevels, Set<Quest> projFinished, boolean liveValue)
    {
        if (r instanceof SkillRequirement)
        {
            SkillRequirement sr = (SkillRequirement) r;
            int have = projLevels.getOrDefault(sr.getSkill(), client.getRealSkillLevel(sr.getSkill()));
            return have >= sr.getLevel();
        }
        try
        {
            java.lang.reflect.Method mq = r.getClass().getMethod("getQuest");
            mq.setAccessible(true);
            Object q = mq.invoke(r);
            if (q instanceof Quest)
            {
                Quest quest = (Quest) q;
                return projFinished.contains(quest) || quest.getState(client) == QuestState.FINISHED;
            }
        }
        catch (Exception ignored) { /* not a quest requirement */ }
        return liveValue;
    }

    public Map<String, Object> buildDiaryRequirements()
    {
        if (!isLoggedIn()) return errorMap("Player is not logged in");
        Map<String, Object> result = new LinkedHashMap<>();

        for (Map.Entry<String, GenericDiaryRequirement> entry : diaryRequirementSources().entrySet())
        {
            List<Map<String, Object>> tasks = new ArrayList<>();
            int metCount = 0;
            for (Object[] dr : diaryTasks(entry.getValue()))
            {
                Map<String, Object> task = new LinkedHashMap<>();
                task.put("task", (String) dr[0]);
                @SuppressWarnings("unchecked")
                List<Requirement> taskReqs = (List<Requirement>) dr[1];
                List<Map<String, Object>> reqs = new ArrayList<>();
                boolean allMet = true;
                for (Requirement r : taskReqs)
                {
                    boolean met;
                    try { met = r.satisfiesRequirement(client); }
                    catch (Exception e) { met = false; }
                    if (!met) allMet = false;
                    Map<String, Object> rm = new LinkedHashMap<>();
                    rm.put("requirement", r.toString());
                    rm.put("met", met);
                    if (r instanceof SkillRequirement)
                    {
                        SkillRequirement sr = (SkillRequirement) r;
                        int cur = client.getRealSkillLevel(sr.getSkill());
                        rm.put("skill", sr.getSkill().getName().toLowerCase());
                        rm.put("required_level", sr.getLevel());
                        rm.put("current_level", cur);
                        if (cur < sr.getLevel()) rm.put("levels_short", sr.getLevel() - cur);
                    }
                    reqs.add(rm);
                }
                task.put("requirements", reqs);
                task.put("all_requirements_met", allMet);
                if (allMet) metCount++;
                tasks.add(task);
            }
            Map<String, Object> region = new LinkedHashMap<>();
            region.put("total_tasks", tasks.size());
            region.put("tasks_with_requirements_met", metCount);
            region.put("tasks", tasks);
            result.put(entry.getKey(), region);
        }
        result.put("_note", "Requirements are live-checked against your account. 'met' means you satisfy the task's skill/quest requirement, not that the task is already completed -- use get_diary_states for tier completion.");
        return result;
    }

    // --- Next-goal ranking --------------------------------------------------------
    // Combines locally-verifiable signals: skills closest to a level-up / to 99,
    // quests already in progress, and diary regions where you already meet the most
    // task requirements. Everything here is derived from live client data.

    public Map<String, Object> buildNextGoals()
    {
        if (!isLoggedIn()) return errorMap("Player is not logged in");
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. Skills closest to their next level and to 99.
        List<Map<String, Object>> nextLevel = new ArrayList<>();
        List<Map<String, Object>> toNinetyNine = new ArrayList<>();
        for (Skill skill : Skill.values())
        {
            if (skill == Skill.OVERALL) continue;
            int level = client.getRealSkillLevel(skill);
            int xp    = client.getSkillExperience(skill);
            if (level >= 99) continue;
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("skill", skill.getName().toLowerCase());
            g.put("level", level);
            g.put("xp_to_next_level", xpToNextLevel(xp, level));
            nextLevel.add(g);

            Map<String, Object> n = new LinkedHashMap<>();
            n.put("skill", skill.getName().toLowerCase());
            n.put("level", level);
            n.put("xp_to_99", Math.max(0, XP_TABLE[98] - xp));
            toNinetyNine.add(n);
        }
        nextLevel.sort(Comparator.comparingLong(m -> ((Number) m.get("xp_to_next_level")).longValue()));
        toNinetyNine.sort(Comparator.comparingLong(m -> ((Number) m.get("xp_to_99")).longValue()));
        result.put("closest_level_ups",  nextLevel.subList(0, Math.min(5, nextLevel.size())));
        result.put("closest_to_99",      toNinetyNine.subList(0, Math.min(5, toNinetyNine.size())));

        // 2. Quests already in progress -- finishing started quests is low-friction.
        List<String> inProgress = new ArrayList<>();
        for (Quest quest : Quest.values())
            if (quest.getState(client) == QuestState.IN_PROGRESS) inProgress.add(quest.getName());
        result.put("quests_in_progress", inProgress);

        // 3. Diary regions ranked by how many task requirements you already meet
        //    but the tier is not yet complete -- these are the nearest diary wins.
        int[][] tierVarbits = {
            {Varbits.DIARY_ARDOUGNE_EASY,  Varbits.DIARY_ARDOUGNE_MEDIUM,  Varbits.DIARY_ARDOUGNE_HARD,  Varbits.DIARY_ARDOUGNE_ELITE},
            {Varbits.DIARY_DESERT_EASY,    Varbits.DIARY_DESERT_MEDIUM,    Varbits.DIARY_DESERT_HARD,    Varbits.DIARY_DESERT_ELITE},
            {Varbits.DIARY_FALADOR_EASY,   Varbits.DIARY_FALADOR_MEDIUM,   Varbits.DIARY_FALADOR_HARD,   Varbits.DIARY_FALADOR_ELITE},
            {Varbits.DIARY_FREMENNIK_EASY, Varbits.DIARY_FREMENNIK_MEDIUM, Varbits.DIARY_FREMENNIK_HARD, Varbits.DIARY_FREMENNIK_ELITE},
            {Varbits.DIARY_KANDARIN_EASY,  Varbits.DIARY_KANDARIN_MEDIUM,  Varbits.DIARY_KANDARIN_HARD,  Varbits.DIARY_KANDARIN_ELITE},
            {Varbits.DIARY_KARAMJA_EASY,   Varbits.DIARY_KARAMJA_MEDIUM,   Varbits.DIARY_KARAMJA_HARD,   Varbits.DIARY_KARAMJA_ELITE},
            {Varbits.DIARY_KOUREND_EASY,   Varbits.DIARY_KOUREND_MEDIUM,   Varbits.DIARY_KOUREND_HARD,   Varbits.DIARY_KOUREND_ELITE},
            {Varbits.DIARY_LUMBRIDGE_EASY, Varbits.DIARY_LUMBRIDGE_MEDIUM, Varbits.DIARY_LUMBRIDGE_HARD, Varbits.DIARY_LUMBRIDGE_ELITE},
            {Varbits.DIARY_MORYTANIA_EASY, Varbits.DIARY_MORYTANIA_MEDIUM, Varbits.DIARY_MORYTANIA_HARD, Varbits.DIARY_MORYTANIA_ELITE},
            {Varbits.DIARY_VARROCK_EASY,   Varbits.DIARY_VARROCK_MEDIUM,   Varbits.DIARY_VARROCK_HARD,   Varbits.DIARY_VARROCK_ELITE},
            {Varbits.DIARY_WESTERN_EASY,   Varbits.DIARY_WESTERN_MEDIUM,   Varbits.DIARY_WESTERN_HARD,   Varbits.DIARY_WESTERN_ELITE},
            {Varbits.DIARY_WILDERNESS_EASY,Varbits.DIARY_WILDERNESS_MEDIUM,Varbits.DIARY_WILDERNESS_HARD,Varbits.DIARY_WILDERNESS_ELITE},
        };
        LinkedHashMap<String, GenericDiaryRequirement> sources = diaryRequirementSources();
        List<Map<String, Object>> diaryWins = new ArrayList<>();
        int idx = 0;
        for (Map.Entry<String, GenericDiaryRequirement> entry : sources.entrySet())
        {
            boolean eliteDone = client.getVarbitValue(tierVarbits[idx][3]) == 1;
            idx++;
            if (eliteDone) continue; // whole diary already finished

            int total = 0, met = 0;
            Map<String, Integer> missingSkills = new LinkedHashMap<>();
            for (Object[] dr : diaryTasks(entry.getValue()))
            {
                total++;
                boolean allMet = true;
                @SuppressWarnings("unchecked")
                List<Requirement> taskReqs = (List<Requirement>) dr[1];
                for (Requirement r : taskReqs)
                {
                    boolean ok;
                    try { ok = r.satisfiesRequirement(client); } catch (Exception e) { ok = false; }
                    if (!ok)
                    {
                        allMet = false;
                        if (r instanceof SkillRequirement)
                        {
                            SkillRequirement sr = (SkillRequirement) r;
                            String sk = sr.getSkill().getName().toLowerCase();
                            int shortBy = sr.getLevel() - client.getRealSkillLevel(sr.getSkill());
                            missingSkills.merge(sk, Math.max(shortBy, 0), Math::max);
                        }
                    }
                }
                if (allMet) met++;
            }
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("region", entry.getKey());
            w.put("total_tasks", total);
            w.put("tasks_with_requirements_met", met);
            w.put("tasks_blocked", total - met);
            if (!missingSkills.isEmpty()) w.put("blocking_skills", missingSkills);
            diaryWins.add(w);
        }
        // Fewest blocked tasks first -> nearest to a full clear.
        diaryWins.sort(Comparator.comparingInt(m -> (Integer) m.get("tasks_blocked")));
        result.put("nearest_diary_regions", diaryWins.subList(0, Math.min(5, diaryWins.size())));

        result.put("_note", "Ranking is derived from live client data: skill XP, quest states, and diary task requirements you already meet. It does not account for quest-start requirements (RuneLite does not expose those) -- treat quest suggestions as 'in progress' only.");
        return result;
    }

        public Map<String, Object> buildSlayerTask()
    {
        if (!isLoggedIn()) return errorMap("Player is not logged in");
        Map<String, Object> result = new LinkedHashMap<>();

        int remaining = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
        if (remaining <= 0)
        {
            result.put("task", null);
            result.put("remaining", 0);
        }
        else
        {
            // Look up task name from game DB
            String taskName = null;
            try
            {
                int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
                List<Integer> taskRows = client.getDBRowsByValue(
                    DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, taskId);
                if (!taskRows.isEmpty())
                    taskName = (String) client.getDBTableField(
                        taskRows.get(0), DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)[0];
            }
            catch (Exception e) { /* task name unavailable */ }

            result.put("task", taskName);
            result.put("remaining", remaining);
            result.put("initial_amount", client.getVarpValue(VarPlayerID.SLAYER_COUNT_ORIGINAL));

            // Location (e.g. "Catacombs of Kourend")
            try
            {
                int areaId = client.getVarpValue(VarPlayerID.SLAYER_AREA);
                if (areaId > 0)
                {
                    List<Integer> areaRows = client.getDBRowsByValue(
                        DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, 0, areaId);
                    if (!areaRows.isEmpty())
                        result.put("location", client.getDBTableField(
                            areaRows.get(0), DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, 0)[0]);
                }
            }
            catch (Exception e) { /* location unavailable */ }
        }

        result.put("points", client.getVarbitValue(VarbitID.SLAYER_POINTS));
        result.put("streak", client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED));
        return result;
    }

    public Map<String, Object> buildClueScroll()
    {
        if (!isLoggedIn()) return errorMap("Player is not logged in");
        Map<String, Object> result = new LinkedHashMap<>();

        ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
        String tier = null;

        if (inventory != null)
        {
            for (Item item : inventory.getItems())
            {
                if (item.getId() <= 0) continue;
                String name = itemManager.getItemComposition(item.getId()).getName().toLowerCase();
                if (!name.contains("clue scroll")) continue;
                if      (name.contains("beginner")) { tier = "beginner"; break; }
                else if (name.contains("easy"))     { tier = "easy";     break; }
                else if (name.contains("medium"))   { tier = "medium";   break; }
                else if (name.contains("hard"))     { tier = "hard";     break; }
                else if (name.contains("elite"))    { tier = "elite";    break; }
                else if (name.contains("master"))   { tier = "master";   break; }
            }
        }

        result.put("active", tier != null);
        result.put("tier", tier);
        return result;
    }

    public List<Map<String, Object>> buildGeOffers()
    {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!isLoggedIn()) return result;

        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null) return result;

        for (int i = 0; i < offers.length; i++)
        {
            GrandExchangeOffer offer = offers[i];
            if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("slot", i + 1);
            entry.put("state", offer.getState().name().toLowerCase());
            entry.put("item_id", offer.getItemId());
            entry.put("item_name", itemManager.getItemComposition(offer.getItemId()).getName());
            entry.put("quantity_traded", offer.getQuantitySold());
            entry.put("total_quantity", offer.getTotalQuantity());
            entry.put("price_per_item", offer.getPrice());
            entry.put("total_spent", offer.getSpent());
            result.add(entry);
        }
        return result;
    }

        public Map<String, Object> buildInstalledPlugins()
    {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> pluginList = new ArrayList<>();

        for (Plugin plugin : pluginManager.getPlugins())
        {
            PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
            if (descriptor == null) continue;
            if (descriptor.hidden()) continue;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", descriptor.name());
            entry.put("enabled", pluginManager.isPluginEnabled(plugin));

            // Distinguish built-in vs Plugin Hub by package name
            String pkg = plugin.getClass().getPackageName();
            entry.put("type", pkg.startsWith("net.runelite.client.plugins") ? "builtin" : "hub");

            pluginList.add(entry);
        }

        // Sort: hub plugins first, then alphabetically
        pluginList.sort((a, b) -> {
            int typeCompare = ((String) a.get("type")).compareTo((String) b.get("type"));
            if (typeCompare != 0) return typeCompare;
            return ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
        });

        result.put("total", pluginList.size());
        result.put("enabled_count", pluginList.stream().filter(p -> (Boolean) p.get("enabled")).count());
        result.put("plugins", pluginList);
        return result;
    }

        /** Called by OsrsMcpPlugin when a bank container event fires. */
    private static final String CONFIG_GROUP = "osrsmcp";
    private static final String KEY_BANK      = "bank_items";
    private static final String KEY_SEED_VAULT= "seed_vault_items";

    public void onBankChanged(ItemContainerChanged event)
    {
        if (event.getContainerId() == InventoryID.BANK.getId())
        {
            cachedBankItems = event.getItemContainer().getItems();
            persistItems(KEY_BANK, cachedBankItems);
        }
        else if (event.getContainerId() == InventoryID.SEED_VAULT.getId())
        {
            cachedSeedVaultItems = event.getItemContainer().getItems();
            persistItems(KEY_SEED_VAULT, cachedSeedVaultItems);
        }
    }

    /** Called on plugin startup to restore cached items from profile config. */
    public void loadPersistedItems()
    {
        cachedBankItems      = restoreItems(KEY_BANK);
        cachedSeedVaultItems = restoreItems(KEY_SEED_VAULT);
        log.debug("OSRS MCP: Restored {} bank items, {} seed vault items from config",
            cachedBankItems != null ? cachedBankItems.length : 0,
            cachedSeedVaultItems != null ? cachedSeedVaultItems.length : 0);
    }

    private void persistItems(String key, Item[] items)
    {
        if (items == null) return;
        StringBuilder sb = new StringBuilder();
        for (Item item : items)
        {
            if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) continue;
            if (sb.length() > 0) sb.append(",");
            sb.append(item.getId()).append(":").append(item.getQuantity());
        }
        configManager.setRSProfileConfiguration(CONFIG_GROUP, key, sb.toString());
    }

    private Item[] restoreItems(String key)
    {
        String stored = configManager.getRSProfileConfiguration(CONFIG_GROUP, key);
        if (stored == null || stored.isEmpty()) return null;
        String[] parts = stored.split(",");
        List<Item> items = new ArrayList<>();
        for (String part : parts)
        {
            try
            {
                String[] kv = part.split(":");
                if (kv.length != 2) continue;
                items.add(new Item(Integer.parseInt(kv[0]), Integer.parseInt(kv[1])));
            }
            catch (NumberFormatException ignored) {}
        }
        return items.toArray(new Item[0]);
    }

    public Map<String, Object> buildCollectionLog()
    {
        if (!isLoggedIn()) return errorMap("Player is not logged in");
        Map<String, Object> result = new LinkedHashMap<>();

        int total    = client.getVarpValue(VarPlayerID.COLLECTION_COUNT);
        int totalMax = client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX);

        result.put("unique_obtained", total);
        result.put("unique_total",    totalMax);
        result.put("completion_percent", totalMax > 0
            ? Math.round((total * 1000.0 / totalMax)) / 10.0 : 0.0);

        // Per-category breakdown
        Map<String, Object> categories = new LinkedHashMap<>();
        addCategory(categories, "bosses",    VarPlayerID.COLLECTION_COUNT_BOSSES,    VarPlayerID.COLLECTION_COUNT_BOSSES_MAX);
        addCategory(categories, "raids",     VarPlayerID.COLLECTION_COUNT_RAIDS,     VarPlayerID.COLLECTION_COUNT_RAIDS_MAX);
        addCategory(categories, "clues",     VarPlayerID.COLLECTION_COUNT_CLUES,     VarPlayerID.COLLECTION_COUNT_CLUES_MAX);
        addCategory(categories, "minigames", VarPlayerID.COLLECTION_COUNT_MINIGAMES, VarPlayerID.COLLECTION_COUNT_MINIGAMES_MAX);
        addCategory(categories, "other",     VarPlayerID.COLLECTION_COUNT_OTHER,     VarPlayerID.COLLECTION_COUNT_OTHER_MAX);
        result.put("categories", categories);

        return result;
    }

    private void addCategory(Map<String, Object> map, String name, int obtainedVar, int maxVar)
    {
        int obtained = client.getVarpValue(obtainedVar);
        int max      = client.getVarpValue(maxVar);
        Map<String, Object> cat = new LinkedHashMap<>();
        cat.put("obtained", obtained);
        cat.put("total",    max);
        map.put(name, cat);
    }

        public Map<String, Object> buildPrayers()
    {
        if (!isLoggedIn()) return errorMap("Player is not logged in");

        // Active prayers (currently toggled on)
        List<String> active = new ArrayList<>();
        for (Prayer prayer : Prayer.values())
        {
            if (client.getVarbitValue(prayer.getVarbit()) == 1)
                active.add(formatPrayerName(prayer.name()));
        }

        // Unlock status for prayers that require specific unlocks beyond Prayer level
        Map<String, Object> unlocks = new LinkedHashMap<>();
        unlocks.put("preserve", client.getVarbitValue(VarbitID.PRAYER_PRESERVE_UNLOCKED) == 1);
        unlocks.put("rigour",   client.getVarbitValue(VarbitID.PRAYER_RIGOUR_UNLOCKED)   == 1);
        unlocks.put("augury",   client.getVarbitValue(VarbitID.PRAYER_AUGURY_UNLOCKED)   == 1);
        // Chivalry and Piety unlock via King's Ransom + Knight Waves Training Grounds
        boolean kingsRansomDone = Quest.KINGS_RANSOM.getState(client) == QuestState.FINISHED;
        unlocks.put("chivalry_and_piety_quest_done", kingsRansomDone);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active_prayers", active);
        result.put("special_unlocks", unlocks);
        return result;
    }

    private String formatPrayerName(String enumName)
    {
        // Convert THICK_SKIN -> Thick Skin, RP_REJUVENATION -> Ruinous Powers: Rejuvenation
        if (enumName.startsWith("RP_"))
        {
            String rest = enumName.substring(3).replace("_", " ");
            return "Ruinous Powers: " + capitalise(rest);
        }
        return capitalise(enumName.replace("_", " "));
    }

    private String capitalise(String s)
    {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder();
        for (String word : s.split(" "))
        {
            if (!word.isEmpty())
            {
                sb.append(Character.toUpperCase(word.charAt(0)));
                sb.append(word.substring(1).toLowerCase());
                sb.append(" ");
            }
        }
        return sb.toString().trim();
    }

        public Map<String, Object> buildNearbyNpcs()
    {
        if (!isLoggedIn()) return errorMap("Player is not logged in");

        List<NPC> npcs = client.getNpcs();
        List<Map<String, Object>> result = new ArrayList<>();

        for (NPC npc : npcs)
        {
            if (npc == null || npc.getName() == null) continue;
            // Skip dead NPCs and untargettable NPCs (id -1)
            if (npc.isDead() || npc.getId() < 0) continue;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", npc.getName());
            entry.put("combat_level", npc.getCombatLevel());
            entry.put("id", npc.getId());

            // Health ratio (approximate %, -1 if unknown)
            int healthRatio = npc.getHealthRatio();
            int healthScale = npc.getHealthScale();
            if (healthRatio >= 0 && healthScale > 0)
                entry.put("health_percent", Math.round(healthRatio * 100.0 / healthScale));

            // Location
            if (npc.getWorldLocation() != null)
            {
                entry.put("x", npc.getWorldLocation().getX());
                entry.put("y", npc.getWorldLocation().getY());
            }

            result.add(entry);
        }

        // Sort by combat level descending, then name
        result.sort((a, b) -> {
            int cl = Integer.compare((int) b.get("combat_level"), (int) a.get("combat_level"));
            return cl != 0 ? cl : ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", result.size());
        out.put("npcs", result);
        return out;
    }

    public Map<String, Object> buildWorldInfo()
    {
        if (!isLoggedIn()) return errorMap("Player is not logged in");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("world", client.getWorld());

        java.util.EnumSet<WorldType> types = client.getWorldType();
        result.put("members",    types.contains(WorldType.MEMBERS));
        result.put("pvp",        WorldType.isPvpWorld(types));
        result.put("high_risk",  types.contains(WorldType.HIGH_RISK));
        result.put("deadman",    types.contains(WorldType.DEADMAN));
        result.put("seasonal",   types.contains(WorldType.SEASONAL));
        result.put("skill_total",types.contains(WorldType.SKILL_TOTAL));
        result.put("bounty",     types.contains(WorldType.BOUNTY));

        // Friendly world type label
        String label = "Standard";
        if (types.contains(WorldType.DEADMAN))         label = "Deadman";
        else if (types.contains(WorldType.SEASONAL))   label = "Seasonal";
        else if (WorldType.isPvpWorld(types))          label = "PvP";
        else if (types.contains(WorldType.HIGH_RISK))  label = "High Risk";
        else if (types.contains(WorldType.BOUNTY))     label = "Bounty";
        else if (types.contains(WorldType.SKILL_TOTAL)) label = "Skill Total";
        result.put("type_label", label);

        return result;
    }

        // XP_TABLE[level] = cumulative XP required to reach (level + 1), indices 0..98.
        // Generated from the official OSRS XP formula; verified against known
        // checkpoints (L70=737627, L92=6517253, L96=9684577, L99=13034431).
        private static final int[] XP_TABLE = {
        0,83,174,276,388,512,650,801,969,1154,
        1358,1584,1833,2107,2411,2746,3115,3523,3973,4470,
        5018,5624,6291,7028,7842,8740,9730,10824,12031,13363,
        14833,16456,18247,20224,22406,24815,27473,30408,33648,37224,
        41171,45529,50339,55649,61512,67983,75127,83014,91721,101333,
        111945,123660,136594,150872,166636,184040,203254,224466,247886,273742,
        302288,333804,368599,407015,449428,496254,547953,605032,668051,737627,
        814445,899257,992895,1096278,1210421,1336443,1475581,1629200,1798808,1986068,
        2192818,2421087,2673114,2951373,3258594,3597792,3972294,4385776,4842295,5346332,
        5902831,6517253,7195629,7944614,8771558,9684577,10692629,11805606,13034431
    };

    private int xpToNextLevel(int xp, int level)
    {
        if (level >= 99) return 0;
        return Math.max(0, XP_TABLE[level] - xp);
    }

    private Map<String, Object> errorMap(String message)
    {
        Map<String, Object> m = new LinkedHashMap<>(); m.put("error", message); return m;
    }

    public Map<String, Object> buildPriceTrends(List<Integer> itemIds)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();

        for (int id : itemIds)
        {
            WikiPriceService.PriceData  latest = wikiPriceService.getPrice(id);
            WikiPriceService.VolumeData vol5m  = wikiPriceService.getVolume5m(id);
            WikiPriceService.VolumeData vol1h  = wikiPriceService.getVolume1h(id);
            WikiPriceService.ItemMeta   meta   = wikiPriceService.getMeta(id);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",   id);
            item.put("name", meta != null ? meta.name : "item " + id);

            if (latest != null)
            {
                item.put("current_buy",  latest.high);
                item.put("current_sell", latest.low);
                item.put("margin",       latest.margin());
            }
            if (vol5m != null)
            {
                item.put("avg_buy_5m",    vol5m.avgHigh);
                item.put("avg_sell_5m",   vol5m.avgLow);
                item.put("volume_5m",     vol5m.totalVol());
            }
            if (vol1h != null)
            {
                item.put("avg_buy_1h",    vol1h.avgHigh);
                item.put("avg_sell_1h",   vol1h.avgLow);
                item.put("volume_1h",     vol1h.totalVol());

                // Trend direction based on buy price: 5m avg vs 1h avg
                if (vol5m != null && vol5m.avgHigh > 0 && vol1h.avgHigh > 0)
                {
                    double changePct = (vol5m.avgHigh - vol1h.avgHigh) * 100.0 / vol1h.avgHigh;
                    String direction = changePct > 1.0 ? "rising" : changePct < -1.0 ? "falling" : "stable";
                    item.put("trend",            direction);
                    item.put("trend_change_pct", Math.round(changePct * 10) / 10.0);
                }
            }
            if (meta != null) item.put("ge_limit", meta.limit);
            items.add(item);
        }

        result.put("items",              items);
        result.put("prices_age_seconds", wikiPriceService.getPricesAgeSeconds());
        return result;
    }

        public Map<String, Object> buildFarmingPatches()
    {
        Map<String, Object> result = farmingPatchService.buildFarmingPatches();
        try { cacheWriter.writeFarming(result); } catch (Exception ignored) {}
        return result;
    }

    public Map<String, Object> buildFarmRun()
    {
        return farmingPatchService.buildFarmRun();
    }

    public Map<String, Object> buildDropTable(String npcName)
    {
        return dropTableService.getDropTable(npcName);
    }

    // ── COMBAT CONTEXT (Phase 11) ─────────────────────────────────────────────

    public Map<String, Object> buildCombatContext()
    {
        if (!isLoggedIn()) return errorMap("Player is not logged in");
        Map<String, Object> result = new LinkedHashMap<>();

        // Base combat levels
        result.put("attack_level",    client.getRealSkillLevel(net.runelite.api.Skill.ATTACK));
        result.put("strength_level",  client.getRealSkillLevel(net.runelite.api.Skill.STRENGTH));
        result.put("defence_level",   client.getRealSkillLevel(net.runelite.api.Skill.DEFENCE));
        result.put("ranged_level",    client.getRealSkillLevel(net.runelite.api.Skill.RANGED));
        result.put("magic_level",     client.getRealSkillLevel(net.runelite.api.Skill.MAGIC));
        result.put("prayer_level",    client.getRealSkillLevel(net.runelite.api.Skill.PRAYER));
        result.put("hitpoints",       client.getRealSkillLevel(net.runelite.api.Skill.HITPOINTS));
        result.put("current_hp",      client.getBoostedSkillLevel(net.runelite.api.Skill.HITPOINTS));
        result.put("current_prayer",  client.getBoostedSkillLevel(net.runelite.api.Skill.PRAYER));

        // Attack style (index into weapon's style list)
        int attackStyleIdx = client.getVarpValue(VarPlayer.ATTACK_STYLE);
        result.put("attack_style_index", attackStyleIdx);

        // Special attack
        int specEnergy = client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT);
        result.put("special_attack_percent", specEnergy / 10); // stored as tenths
        result.put("spec_enabled", client.getVarpValue(VarPlayer.SPECIAL_ATTACK_ENABLED) == 1);

        // Active combat prayers
        List<String> activePrayers = new ArrayList<>();
        for (Prayer prayer : Prayer.values())
            if (client.getVarbitValue(prayer.getVarbit()) == 1)
                activePrayers.add(formatPrayerName(prayer.name()));
        result.put("active_prayers", activePrayers);

        // Detect potions in inventory that affect combat
        Map<String, Boolean> boosts = detectCombatBoosts();
        result.put("potions_detected", boosts);

        // Current target (what the player is attacking)
        Actor target = client.getLocalPlayer().getInteracting();
        if (target instanceof NPC)
        {
            NPC npc = (NPC) target;
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name",           npc.getName());
            t.put("combat_level",   npc.getCombatLevel());
            t.put("id",             npc.getId());
            int hr = npc.getHealthRatio(), hs = npc.getHealthScale();
            if (hr >= 0 && hs > 0) t.put("health_percent", Math.round(hr * 100.0 / hs));
            result.put("current_target", t);
        }
        else
        {
            result.put("current_target", null);
            // Fall back to slayer task if no active target
            int slayerRemaining = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
            if (slayerRemaining > 0)
                result.put("slayer_task_context", buildSlayerTask());
        }

        return result;
    }

    private Map<String, Boolean> detectCombatBoosts()
    {
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        Map<String, Boolean> boosts = new LinkedHashMap<>();
        boosts.put("super_attack",    false);
        boosts.put("super_strength",  false);
        boosts.put("super_combat",    false);
        boosts.put("ranging_potion",  false);
        boosts.put("bastion_potion",  false);
        boosts.put("imbued_heart",    false);
        boosts.put("overload",        false);
        boosts.put("prayer_potion",   false);
        boosts.put("sanfew_serum",    false);

        if (inv == null) return boosts;
        for (Item item : inv.getItems())
        {
            if (item.getId() <= 0) continue;
            String name = itemManager.getItemComposition(item.getId()).getName().toLowerCase();
            if (name.contains("super attack"))        boosts.put("super_attack",   true);
            if (name.contains("super strength"))      boosts.put("super_strength", true);
            if (name.contains("super combat"))        boosts.put("super_combat",   true);
            if (name.contains("ranging potion") || name.contains("ranging pot")) boosts.put("ranging_potion", true);
            if (name.contains("bastion"))             boosts.put("bastion_potion", true);
            if (name.contains("imbued heart"))        boosts.put("imbued_heart",   true);
            if (name.contains("overload"))            boosts.put("overload",       true);
            if (name.contains("prayer potion"))       boosts.put("prayer_potion",  true);
            if (name.contains("sanfew"))              boosts.put("sanfew_serum",   true);
        }
        return boosts;
    }

    public Map<String, Object> buildBossKc()
    {
        Map<String, Object> result = new LinkedHashMap<>();

        // Game-tracked slayer boss KCs (VarPlayerID)
        result.put("zulrah",               client.getVarpValue(VarPlayerID.TOTAL_SNAKEBOSS_KILLS));
        result.put("kraken",               client.getVarpValue(VarPlayerID.TOTAL_KRAKEN_BOSS_KILLS));
        result.put("vorkath",              client.getVarpValue(VarPlayerID.TOTAL_VORKATH_KILLS));
        result.put("grotesque_guardians",  client.getVarpValue(VarPlayerID.TOTAL_GARGBOSS_KILLS));
        result.put("alchemical_hydra",     client.getVarpValue(VarPlayerID.TOTAL_HYDRABOSS_KILLS));
        result.put("barrows",              client.getVarpValue(VarPlayerID.TOTAL_BARROWS_CHESTS));
        result.put("catacomb_bosses",      client.getVarpValue(VarPlayerID.TOTAL_CATA_BOSS_KILLS));

        // Profile-stored KCs (set by ChatCommands plugin from chat messages)
        // Common bosses stored by name
        String[] profileBosses = {
            "abyssal sire", "cerberus", "chaos elemental", "commander zilyana",
            "corporeal beast", "dagannoth prime", "dagannoth rex", "dagannoth supreme",
            "general graardor", "giant mole", "kree'arra", "k'ril tsutsaroth",
            "nex", "nightmare", "phosani's nightmare", "sarachnis",
            "scorpia", "scurrius", "skotizo", "tempoross",
            "theatre of blood", "chambers of xeric", "tombs of amascut",
            "thermonuclear smoke devil", "tzkal-zuk", "tztok-jad",
            "vardorvis", "duke sucellus", "the leviathan", "the whisperer",
            "callisto", "venenatis", "vet'ion", "king black dragon",
            "deranged archaeologist", "obor", "bryophyta"
        };

        Map<String, Object> profileKc = new LinkedHashMap<>();
        for (String boss : profileBosses)
        {
            Integer kc = configManager.getRSProfileConfiguration("killcount", boss, int.class);
            if (kc != null && kc > 0) profileKc.put(boss, kc);
        }
        result.put("profile_kc", profileKc);
        result.put("note", "Profile KCs require ChatCommands plugin to be active and bosses killed while it was enabled.");

        return result;
    }

        public Map<String, Object> buildNpcInfo(String npcName)
    {
        if (npcName == null || npcName.trim().isEmpty())
            return errorMap("No NPC name provided");

        // Capitalise first letter of each word for Wiki page title
        String[] words = npcName.trim().split("\\\\s+");
        StringBuilder title = new StringBuilder();
        for (String w : words)
        {
            if (!w.isEmpty())
                title.append(Character.toUpperCase(w.charAt(0)))
                     .append(w.substring(1).toLowerCase()).append(" ");
        }
        String pageTitle = title.toString().trim().replace(" ", "_");

        try
        {
            String url = "https://oldschool.runescape.wiki/w/Special:Export/" + pageTitle;
            okhttp3.Request req = new okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "osrs-mcp-plugin/1.0")
                .build();
            String body;
            try (okhttp3.Response resp = httpClient.newCall(req).execute())
            {
                if (!resp.isSuccessful()) return errorMap("Wiki page not found for: " + npcName);
                body = resp.body().string();
            }

            // Extract wiki text from XML
            int textStart = body.indexOf("<text");
            int textEnd   = body.indexOf("</text>");
            if (textStart < 0 || textEnd < 0) return errorMap("Could not parse Wiki page for: " + npcName);
            int contentStart = body.indexOf(">", textStart) + 1;
            String wikiText = body.substring(contentStart, textEnd);

            // Parse infobox fields: |key = value or |key1 = value
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", npcName);

            String[] fields = {
                "combat", "hitpoints", "slaylvl", "slayxp", "max_hit",
                "attack_speed", "aggressive", "poisonous",
                "immunepoison", "immunevenom", "immunecannon", "immunethrall",
                "dstab", "dslash", "dcrush", "dmagic", "drange",
                "astab", "aslash", "acrush", "amagic", "arange",
                "elementalweaknesstype", "elementalweaknesspercent"
            };

            for (String field : fields)
            {
                // Try exact field, then field with suffix 1
                String val = extractWikiField(wikiText, field);
                if (val == null) val = extractWikiField(wikiText, field + "1");
                if (val != null && !val.isEmpty() && !val.equals("N/A") && !val.equals("?"))
                    result.put(field, val.trim());
            }

            if (result.size() <= 1)
                return errorMap("No stats found for: " + npcName + ". Check the name matches the OSRS Wiki page title.");

            result.put("wiki_url", "https://oldschool.runescape.wiki/w/" + pageTitle);
            return result;
        }
        catch (Exception e)
        {
            log.warn("OSRS MCP: Failed to fetch NPC info for {}: {}", npcName, e.getMessage());
            return errorMap("Failed to fetch Wiki data for: " + npcName);
        }
    }

    private String extractWikiField(String wikiText, String field)
    {
        // Matches: |field = value or |field= value (with or without spaces)
        String pattern = "\\|\\s*" + java.util.regex.Pattern.quote(field) + "\\s*=\\s*([^\\|\\n\\}]+)";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(wikiText);
        if (m.find())
        {
            String val = m.group(1).trim();
            // Strip wiki markup like [[...]] and {{...}}
            val = val.replaceAll("\\[\\[([^\\|\\]]+)\\|?[^\\]]*\\]\\]", "$1");
            val = val.replaceAll("\\{\\{[^\\}]+\\}\\}", "");
            val = val.replaceAll("<[^>]+>", "");
            return val.trim();
        }
        return null;
    }

        // ── EQUIPMENT STATS (Phase 12) ───────────────────────────────────────────

    // Plain snapshot of worn gear (item names + strength level) taken on the client
    // thread, so the per-item wiki stat fetches can then run OFF the client thread.
    public static final class EquipSnapshot
    {
        final String error;
        final List<String[]> worn;   // {slotName, itemName}
        final int strengthLevel;
        EquipSnapshot(String error)                 { this.error = error; this.worn = null; this.strengthLevel = 0; }
        EquipSnapshot(List<String[]> worn, int str) { this.error = null;  this.worn = worn; this.strengthLevel = str; }
    }

    /** Client thread: read worn items + strength into a plain snapshot (no HTTP). */
    public EquipSnapshot snapshotEquipmentStats()
    {
        if (!isLoggedIn()) return new EquipSnapshot("Player is not logged in");
        ItemContainer worn = client.getItemContainer(InventoryID.EQUIPMENT);
        if (worn == null) return new EquipSnapshot("No equipment data available");
        String[] slotNames = {"head","cape","amulet","weapon","body","shield",
                              "legs","hands","feet","ring","ammo","","",""};
        List<String[]> out = new ArrayList<>();
        Item[] items = worn.getItems();
        for (int i = 0; i < items.length && i < slotNames.length; i++)
        {
            Item item = items[i];
            if (item == null || item.getId() <= 0) continue;
            String name = itemManager.getItemComposition(item.getId()).getName();
            if (name == null || name.equals("null") || name.isEmpty()) continue;
            out.add(new String[]{ slotNames[i].isEmpty() ? "slot_" + i : slotNames[i], name });
        }
        return new EquipSnapshot(out, client.getRealSkillLevel(net.runelite.api.Skill.STRENGTH));
    }

    /** Convenience: snapshot + fetch in one call (runs the HTTP on the calling thread). */
    public Map<String, Object> buildEquipmentStats()
    {
        return buildEquipmentStatsOffThread(snapshotEquipmentStats());
    }

    /** Off-thread: fetch per-item stats (HTTP, 24h-cached) and assemble totals. */
    public Map<String, Object> buildEquipmentStatsOffThread(EquipSnapshot snap)
    {
        if (snap.error != null) return errorMap(snap.error);

        Map<String, Object> result      = new LinkedHashMap<>();
        Map<String, Object> bySlot      = new LinkedHashMap<>();
        Map<String, Object> missing     = new LinkedHashMap<>();

        // Running totals
        int totalAstab = 0, totalAslash = 0, totalAcrush = 0, totalAmagic = 0, totalArange = 0;
        int totalDstab = 0, totalDslash = 0, totalDcrush = 0, totalDmagic = 0, totalDrange = 0;
        int totalStr = 0, totalPrayer = 0;

        for (String[] wornItem : snap.worn)
        {
            String slotName = wornItem[0];
            String name     = wornItem[1];

            EquipmentStatsService.EquipmentStats stats = equipmentStatsService.getStats(name);
            if (stats != null)
            {
                bySlot.put(slotName, stats.toMap());
                totalAstab  += stats.astab;  totalAslash += stats.aslash;
                totalAcrush += stats.acrush; totalAmagic += stats.amagic;
                totalArange += stats.arange;
                totalDstab  += stats.dstab;  totalDslash += stats.dslash;
                totalDcrush += stats.dcrush; totalDmagic += stats.dmagic;
                totalDrange += stats.drange;
                totalStr    += stats.str;    totalPrayer += stats.prayer;
            }
            else
            {
                missing.put(slotName, name);
            }
        }

        // Totals
        Map<String, Object> totals = new LinkedHashMap<>();
        Map<String, Object> attackTotals = new LinkedHashMap<>();
        attackTotals.put("stab", totalAstab); attackTotals.put("slash", totalAslash);
        attackTotals.put("crush", totalAcrush); attackTotals.put("magic", totalAmagic);
        attackTotals.put("ranged", totalArange);
        Map<String, Object> defenceTotals = new LinkedHashMap<>();
        defenceTotals.put("stab", totalDstab); defenceTotals.put("slash", totalDslash);
        defenceTotals.put("crush", totalDcrush); defenceTotals.put("magic", totalDmagic);
        defenceTotals.put("ranged", totalDrange);
        Map<String, Object> otherTotals = new LinkedHashMap<>();
        otherTotals.put("strength", totalStr);
        otherTotals.put("prayer", totalPrayer);
        totals.put("attack_bonuses",  attackTotals);
        totals.put("defence_bonuses", defenceTotals);
        totals.put("other_bonuses",   otherTotals);

        // Estimated melee max hit (aggressive style, no prayer/potion)
        int baseLvl = snap.strengthLevel;
        int effectiveStr = baseLvl + 8 + 3; // +3 for aggressive style
        double maxHitRaw = 0.5 + effectiveStr * (totalStr + 64.0) / 640.0;
        totals.put("est_max_hit_melee_no_boost", (int) maxHitRaw);

        result.put("totals",  totals);
        result.put("by_slot", bySlot);
        if (!missing.isEmpty())
            result.put("stats_unavailable", missing);

        return result;
    }

        public Map<String, Object> buildSeedVault()
    {
        Map<String, Object> result = new LinkedHashMap<>();
        if (cachedSeedVaultItems == null)
        {
            result.put("cached", false);
            result.put("message", "Seed vault not yet opened this session. Visit the Farming Guild and open your seed vault to populate this.");
            return result;
        }

        List<Map<String, Object>> seeds    = new ArrayList<>();
        List<Map<String, Object>> saplings = new ArrayList<>();
        List<Map<String, Object>> other    = new ArrayList<>();
        long totalValue = 0;

        for (Item item : cachedSeedVaultItems)
        {
            if (item.getId() <= 0 || item.getQuantity() <= 0) continue;
            String name = itemManager.getItemComposition(item.getId()).getName();
            if (name == null || name.equals("null")) continue;

            String nameLower = name.toLowerCase();
            WikiPriceService.ItemMeta meta = wikiPriceService.getMeta(item.getId());
            WikiPriceService.PriceData pd  = wikiPriceService.getPrice(item.getId());

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name",     name);
            entry.put("id",       item.getId());
            entry.put("quantity", item.getQuantity());
            if (pd != null && pd.low > 0)
            {
                long stackValue = (long) pd.low * item.getQuantity();
                entry.put("price_each",  pd.low);
                entry.put("total_value", stackValue);
                totalValue += stackValue;
            }
            if (meta != null && meta.examine != null)
                entry.put("examine", meta.examine);

            if (nameLower.contains("sapling") || nameLower.contains("seedling"))
                saplings.add(entry);
            else if (nameLower.contains("seed") || nameLower.contains("spore")
                  || nameLower.contains("potato") || nameLower.contains("onion")
                  || nameLower.contains("cabbage") || nameLower.contains("tomato")
                  || nameLower.contains("sweetcorn") || nameLower.contains("strawberry")
                  || nameLower.contains("watermelon") || nameLower.contains("snape grass"))
                seeds.add(entry);
            else
                other.add(entry);
        }

        Comparator<Map<String,Object>> byQty = (a, b) ->
            Integer.compare((int) b.get("quantity"), (int) a.get("quantity"));
        seeds.sort(byQty);
        saplings.sort(Comparator.comparing(e -> (String) e.get("name")));
        other.sort(byQty);

        result.put("cached",      true);
        result.put("total_value", totalValue);
        result.put("seeds",       seeds);
        result.put("saplings",    saplings);
        result.put("other",       other);
        return result;
    }

        public Map<String, Object> buildCacheIndex()
    {
        Map<String, Object> result = new LinkedHashMap<>();
        java.io.File dir = new java.io.File(cacheWriter.getCacheDir());
        List<Map<String, Object>> files = new ArrayList<>();
        if (dir.exists() && dir.isDirectory())
        {
            java.io.File[] mdFiles = dir.listFiles((d, n) -> n.endsWith(".md"));
            if (mdFiles != null)
            {
                java.util.Arrays.sort(mdFiles, Comparator.comparing(java.io.File::getName));
                java.time.format.DateTimeFormatter fmt =
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                for (java.io.File f : mdFiles)
                {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("file",         f.getName());
                    entry.put("size_bytes",   f.length());
                    entry.put("last_updated", java.time.LocalDateTime
                        .ofInstant(java.time.Instant.ofEpochMilli(f.lastModified()),
                                   java.time.ZoneId.systemDefault()).format(fmt));
                    files.add(entry);
                }
            }
        }
        result.put("cache_dir", cacheWriter.getCacheDir());
        result.put("files",     files);
        result.put("hint",      "Call read_cache with a file name to get the full contents.");
        return result;
    }

    public Map<String, Object> readCacheFile(String filename)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        if (filename == null || filename.trim().isEmpty())
            return errorMap("No filename provided. Call get_cache_index to see available files.");

        // Sanitise -- only allow .md files, no path traversal
        String clean = new java.io.File(filename).getName();
        if (!clean.endsWith(".md"))
            return errorMap("Only .md files are available. Example: bank.md");

        java.io.File file = new java.io.File(cacheWriter.getCacheDir(), clean);
        if (!file.exists())
            return errorMap("Cache file not found: " + clean + ". Call get_cache_index to see available files.");

        try
        {
            String contents = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                                         java.nio.charset.StandardCharsets.UTF_8);
            result.put("file",     clean);
            result.put("contents", contents);
            return result;
        }
        catch (java.io.IOException e)
        {
            return errorMap("Failed to read cache file: " + e.getMessage());
        }
    }

        // ── BIS COMPARISON (Phase 16) ─────────────────────────────────────────────

    /**
     * Compares current gear against bank items slot-by-slot for a given combat style.
     * style: "melee", "ranged", or "magic"
     */
    // Plain snapshot for BiS comparison, taken on the client thread (worn item
    // names/ids + bank items grouped by slot), so the per-item wiki stat fetches
    // can then run OFF the client thread.
    public static final class BisSnapshot
    {
        final String error;
        final String style;
        final List<Object[]> worn;                 // {String slot, String name, Integer id}
        final boolean bankAvailable;
        final Map<String, List<String>> bankBySlot;
        BisSnapshot(String error) { this.error = error; this.style = null; this.worn = null; this.bankAvailable = false; this.bankBySlot = null; }
        BisSnapshot(String style, List<Object[]> worn, boolean bankAvailable, Map<String, List<String>> bankBySlot)
        { this.error = null; this.style = style; this.worn = worn; this.bankAvailable = bankAvailable; this.bankBySlot = bankBySlot; }
    }

    /** Client thread: snapshot worn gear + bank-by-slot (itemManager/client reads, no HTTP). */
    public BisSnapshot snapshotBisComparison(String style)
    {
        if (!isLoggedIn()) return new BisSnapshot("Player is not logged in");
        if (style == null || style.trim().isEmpty()) style = "melee";
        style = style.toLowerCase().trim();

        ItemContainer worn = client.getItemContainer(InventoryID.EQUIPMENT);
        if (worn == null) return new BisSnapshot("No equipment data available");

        String[] slotNames = {"head","cape","amulet","weapon","body","shield",
                              "legs","hands","feet","ring","ammo","","",""};
        Item[] wornItems = worn.getItems();
        List<Object[]> wornSnap = new ArrayList<>();
        for (int i = 0; i < wornItems.length && i < slotNames.length; i++)
        {
            if (slotNames[i].isEmpty()) continue;
            Item item = wornItems[i];
            if (item == null || item.getId() <= 0) continue;
            String name = itemManager.getItemComposition(item.getId()).getName();
            if (name == null || name.equals("null")) continue;
            wornSnap.add(new Object[]{ slotNames[i], name, item.getId() });
        }

        if (cachedBankItems == null)
            return new BisSnapshot(style, wornSnap, false, new LinkedHashMap<>());

        // Filter bank to equippable items, group by inferred slot
        Map<String, List<String>> bankBySlot = new LinkedHashMap<>();
        for (Item item : cachedBankItems)
        {
            if (item.getId() <= 0 || item.getQuantity() <= 0) continue;
            net.runelite.api.ItemComposition comp = itemManager.getItemComposition(item.getId());
            String name = comp.getName();
            if (name == null || name.equals("null")) continue;
            String[] actions = comp.getInventoryActions();
            boolean equippable = false;
            if (actions != null)
                for (String a : actions)
                    if ("Wear".equals(a) || "Wield".equals(a)) { equippable = true; break; }
            if (!equippable) continue;

            // Infer slot -- reuse logic from buildBankClassified
            String nameLower = name.toLowerCase();
            boolean wield = false;
            if (actions != null) for (String a : actions) if ("Wield".equals(a)) wield = true;
            String slot = inferSlot(nameLower, wield);
            bankBySlot.computeIfAbsent(slot, k -> new ArrayList<>()).add(name);
        }

        return new BisSnapshot(style, wornSnap, true, bankBySlot);
    }

    /** Convenience: snapshot + fetch on the calling thread. */
    public Map<String, Object> buildBisComparison(String style)
    {
        return buildBisComparisonOffThread(snapshotBisComparison(style));
    }

    /** Off-thread: fetch per-item stats (HTTP) and assemble the comparison from the snapshot. */
    public Map<String, Object> buildBisComparisonOffThread(BisSnapshot snap)
    {
        if (snap.error != null) return errorMap(snap.error);
        final String style = snap.style;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("style", style);

        // ── Current gear (fetch stats off-thread) ─────────────────────────────
        Map<String, Map<String, Object>> currentGear = new LinkedHashMap<>();
        for (Object[] w : snap.worn)
        {
            String slot = (String) w[0];
            String name = (String) w[1];
            int id      = (Integer) w[2];
            EquipmentStatsService.EquipmentStats stats = equipmentStatsService.getStats(name);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("id", id);
            if (stats != null) entry.put("stats", stats.toMap());
            currentGear.put(slot, entry);
        }
        result.put("current_gear", currentGear);

        if (!snap.bankAvailable)
        {
            result.put("upgrades_found", false);
            result.put("message", "Open your bank to compare with banked items.");
            return result;
        }
        Map<String, List<String>> bankBySlot = snap.bankBySlot;

        // ── Slot-by-slot comparison ───────────────────────────────────────────
        List<Map<String, Object>> upgrades = new ArrayList<>();
        List<Map<String, Object>> compared  = new ArrayList<>();

        for (Map.Entry<String, Map<String, Object>> entry : currentGear.entrySet())
        {
            String slot = entry.getKey();
            Map<String, Object> current = entry.getValue();
            String currentName = (String) current.get("name");
            EquipmentStatsService.EquipmentStats currentStats =
                (current.get("stats") != null) ? equipmentStatsService.getStats(currentName) : null;

            List<String> candidates = bankBySlot.getOrDefault(slot, Collections.emptyList());
            Map<String, Object> slotResult = new LinkedHashMap<>();
            slotResult.put("slot", slot);
            slotResult.put("equipped", currentName);
            slotResult.put("equipped_score", scoreItem(currentStats, style));

            List<Map<String, Object>> options = new ArrayList<>();
            for (String candidateName : candidates)
            {
                if (candidateName.equalsIgnoreCase(currentName)) continue;
                EquipmentStatsService.EquipmentStats cStats = equipmentStatsService.getStats(candidateName);
                if (cStats == null) continue;
                double score = scoreItem(cStats, style);
                double currentScore = scoreItem(currentStats, style);
                if (score > currentScore)
                {
                    Map<String, Object> opt = new LinkedHashMap<>();
                    opt.put("name",  candidateName);
                    opt.put("score", score);
                    opt.put("score_gain", score - currentScore);
                    opt.put("stats", cStats.toMap());
                    options.add(opt);
                }
            }
            options.sort((a, b) -> Double.compare((double) b.get("score_gain"), (double) a.get("score_gain")));
            if (!options.isEmpty())
            {
                slotResult.put("best_upgrade", options.get(0));
                upgrades.add(slotResult);
            }
            compared.add(slotResult);
        }

        result.put("upgrades_available", !upgrades.isEmpty());
        result.put("upgrade_count",      upgrades.size());
        result.put("upgrades",           upgrades);
        result.put("full_comparison",    compared);
        return result;
    }

    /**
     * Score an item for a given combat style.
     * Higher = better for that style.
     */
    private double scoreItem(EquipmentStatsService.EquipmentStats stats, String style)
    {
        if (stats == null) return 0;
        switch (style)
        {
            case "melee":  return stats.str + stats.aslash + stats.acrush + stats.astab;
            case "ranged": return stats.arange + (stats.rstr * 3.0); // rstr weighted higher as it affects max hit
            case "magic":  return stats.amagic + (stats.mdmg * 5.0); // mdmg weighted higher as each % is very impactful
            default:       return stats.str + stats.aslash;
        }
    }

        // ── BANK CLASSIFICATION (Phase 10) ───────────────────────────────────────

    public Map<String, Object> buildBankClassified()
    {
        Map<String, Object> result = new LinkedHashMap<>();
        if (cachedBankItems == null)
        {
            result.put("cached", false);
            result.put("message", "Bank not yet opened this session.");
            return result;
        }

        List<Map<String, Object>> equipment   = new ArrayList<>();
        List<Map<String, Object>> food        = new ArrayList<>();
        List<Map<String, Object>> potions     = new ArrayList<>();
        List<Map<String, Object>> runes       = new ArrayList<>();
        List<Map<String, Object>> ammo        = new ArrayList<>();
        List<Map<String, Object>> materials   = new ArrayList<>();
        List<Map<String, Object>> other       = new ArrayList<>();

        for (Item item : cachedBankItems)
        {
            if (item.getId() <= 0 || item.getQuantity() <= 0) continue;
            net.runelite.api.ItemComposition comp = itemManager.getItemComposition(item.getId());
            String name = comp.getName();
            if (name == null || name.equals("null")) continue;

            String nameLower = name.toLowerCase();
            String[] actions = comp.getInventoryActions();
            boolean hasWear   = hasAction(actions, "Wear");
            boolean hasWield  = hasAction(actions, "Wield");
            boolean hasEat    = hasAction(actions, "Eat");
            boolean hasDrink  = hasAction(actions, "Drink");

            Map<String, Object> entry = buildBankEntry(item, comp, name, nameLower);

            if (isRune(nameLower))                           runes.add(entry);
            else if (isAmmo(nameLower))                      ammo.add(entry);
            else if (hasEat)                                 food.add(entry);
            else if (hasDrink || isPotion(nameLower))        potions.add(entry);
            else if (hasWear || hasWield)                    { entry.put("slot", inferSlot(nameLower, hasWield)); equipment.add(entry); }
            else if (comp.isStackable() && !comp.isTradeable()) other.add(entry);
            else if (comp.isStackable())                     materials.add(entry);
            else                                             other.add(entry);
        }

        // Sort each category by quantity desc
        Comparator<Map<String,Object>> byQty = (a, b) ->
            Integer.compare((int) b.get("quantity"), (int) a.get("quantity"));

        equipment.sort(Comparator.comparing(e -> (String) e.get("name")));
        food.sort(byQty);
        potions.sort(byQty);
        runes.sort(byQty);
        ammo.sort(byQty);
        materials.sort(byQty);
        other.sort(Comparator.comparing(e -> (String) e.get("name")));

        result.put("cached", true);
        result.put("equipment",  equipment);
        result.put("food",       food);
        result.put("potions",    potions);
        result.put("runes",      runes);
        result.put("ammo",       ammo);
        result.put("materials",  materials);
        result.put("other",      other);
        return result;
    }

    private Map<String, Object> buildBankEntry(Item item, net.runelite.api.ItemComposition comp, String name, String nameLower)
    {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name",     name);
        entry.put("id",       item.getId());
        entry.put("quantity", item.getQuantity());
        // Attach Wiki examine text if available
        WikiPriceService.ItemMeta meta = wikiPriceService.getMeta(item.getId());
        if (meta != null && meta.examine != null && !meta.examine.isEmpty())
            entry.put("examine", meta.examine);
        return entry;
    }

    private boolean hasAction(String[] actions, String target)
    {
        if (actions == null) return false;
        for (String a : actions)
            if (target.equals(a)) return true;
        return false;
    }

    private boolean isRune(String name)
    {
        return name.endsWith("rune") || name.equals("death rune") || name.equals("blood rune")
            || name.equals("soul rune") || name.equals("wrath rune") || name.equals("rune");
    }

    private boolean isAmmo(String name)
    {
        return name.contains("arrow") || name.contains("bolt") || name.contains("dart")
            || name.contains("knife") || name.contains("thrownaxe") || name.contains("javelin")
            || name.contains("cannonball") || name.contains("chinchompa");
    }

    private boolean isPotion(String name)
    {
        return name.contains("potion") || name.contains("brew") || name.contains("restore")
            || name.contains("flask") || name.contains("mix") || name.contains("antipoison")
            || name.contains("antidote") || name.contains("antifire") || name.contains("prayer pot")
            || name.contains("stamina") || name.contains("divine") || name.contains("ancient brew");
    }

    private String inferSlot(String name, boolean wield)
    {
        if (wield) return name.contains("shield") || name.contains("defender") || name.contains("book")
                              || name.contains("buckler") || name.contains("ward") ? "shield" : "weapon";
        if (name.contains("helm") || name.contains("hat") || name.contains("hood") || name.contains("coif")
            || name.contains("mask") || name.contains("tiara") || name.contains("crown") || name.contains("berserker helm")) return "head";
        if (name.contains("cape") || name.contains("cloak") || name.contains("backpack")) return "cape";
        if (name.contains("amulet") || name.contains("necklace") || name.contains("pendant")
            || name.contains("salve") || name.contains("torture") || name.contains("fury")) return "amulet";
        if (name.contains("platebody") || name.contains("chainbody") || name.contains("hauberk")
            || name.contains(" top") || name.contains("chestplate") || name.contains(" body")
            || name.contains("tabard") || name.contains("tunic") || name.contains("robetop")) return "body";
        if (name.contains("platelegs") || name.contains("skirt") || name.contains("chaps")
            || name.contains("tassets") || name.contains("robebottom") || name.contains("trousers")) return "legs";
        if (name.contains("gloves") || name.contains("gauntlets") || name.contains("vambraces")) return "gloves";
        if (name.contains("boots") || name.contains("shoes") || name.contains("sandals")) return "boots";
        if (name.contains("ring")) return "ring";
        return "equipment";
    }

        // ── BANK TOOLS (Phase 9) ──────────────────────────────────────────────────

    public Map<String, Object> buildBankSummary()
    {
        Map<String, Object> result = new LinkedHashMap<>();
        if (cachedBankItems == null)
        {
            result.put("cached", false);
            result.put("message", "Bank not yet opened this session.");
            result.put("coins",   getCoins());
            return result;
        }
        long totalValue  = 0;
        int  uniqueItems = 0;
        for (Item item : cachedBankItems)
        {
            if (item.getId() <= 0 || item.getQuantity() <= 0) continue;
            totalValue += (long) itemManager.getItemPrice(item.getId()) * item.getQuantity();
            uniqueItems++;
        }
        result.put("cached",      true);
        result.put("total_value", totalValue);
        result.put("unique_items",uniqueItems);
        result.put("coins",       getCoins());
        return result;
    }

    public Map<String, Object> buildBankTopValue()
    {
        Map<String, Object> result = new LinkedHashMap<>();
        if (cachedBankItems == null)
        {
            result.put("cached", false);
            result.put("message", "Bank not yet opened this session.");
            return result;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Item item : cachedBankItems)
        {
            if (item.getId() <= 0 || item.getQuantity() <= 0) continue;
            int  price      = itemManager.getItemPrice(item.getId());
            long stackValue = (long) price * item.getQuantity();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name",        itemManager.getItemComposition(item.getId()).getName());
            entry.put("id",          item.getId());
            entry.put("quantity",    item.getQuantity());
            entry.put("price_each",  price);
            entry.put("total_value", stackValue);
            items.add(entry);
        }
        items.sort((a, b) -> Long.compare((long) b.get("total_value"), (long) a.get("total_value")));
        List<Map<String, Object>> top100 = items.subList(0, Math.min(100, items.size()));
        result.put("cached",      true);
        result.put("item_count",  items.size());
        result.put("showing",     top100.size());
        result.put("items",       top100);
        return result;
    }

    public Map<String, Object> buildBankCoins()
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("coins_inventory", getInventoryCoins());
        result.put("coins_bank",      getBankCoins());
        result.put("coins_total",     getCoins());
        return result;
    }

    private long getCoins()        { return getInventoryCoins() + getBankCoins(); }

    // Coins cached on the client thread so off-thread callers (the Flips panel) can size a
    // flip budget without touching the Client. Refreshed each tick by the plugin.
    private volatile long cachedCoins = -1;
    /** Refresh the cached coin total. MUST be called on the client thread. */
    public void refreshCoinsCache() { cachedCoins = getCoins(); }
    /** Last cached inventory+bank coins, or -1 if never captured. Client-free. */
    public long cachedCoins() { return cachedCoins; }

    private long getInventoryCoins()
    {
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv == null) return 0;
        for (Item item : inv.getItems())
            if (item.getId() == 995) return item.getQuantity();
        return 0;
    }
    private long getBankCoins()
    {
        if (cachedBankItems == null) return 0;
        for (Item item : cachedBankItems)
            if (item.getId() == 995) return item.getQuantity();
        return 0;
    }

    // ── PRICES & FLIPPING (Phase 9) ───────────────────────────────────────────

    public Map<String, Object> buildItemPrices(List<Integer> itemIds)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> prices = new ArrayList<>();
        for (int id : itemIds)
        {
            WikiPriceService.PriceData pd   = wikiPriceService.getPrice(id);
            WikiPriceService.ItemMeta  meta = wikiPriceService.getMeta(id);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id",   id);
            entry.put("name", meta != null ? meta.name : "item " + id);
            if (pd != null)
            {
                entry.put("buy_price",   pd.high);
                entry.put("sell_price",  pd.low);
                entry.put("margin",      pd.margin());
                entry.put("margin_pct",  Math.round(pd.marginPct() * 10) / 10.0);
                if (meta != null) entry.put("ge_limit", meta.limit);
            }
            else entry.put("error", "Price not available");
            prices.add(entry);
        }
        result.put("prices_age_seconds", wikiPriceService.getPricesAgeSeconds());
        result.put("items", prices);
        return result;
    }

    /**
     * Market-wide flip candidates — v1 rules model (docs/flip-model-goal.md). Ranks items
     * by margin-after-tax x liquidity, penalised for risk (thin/one-sided/wide-spread),
     * from the public price+volume API. Client-free, so it runs off the client thread as a
     * NETWORK tool. Ported from tools/flip-model.mjs (validated on live prices).
     */
    public Map<String, Object> buildFlipSuggestions(long capital, int minVolume, int minMargin, int top)
    {
        final int    MIN_VOLUME = minVolume > 0 ? minVolume : 500; // 1h units traded
        final int    MIN_MARGIN = minMargin > 0 ? minMargin : 20;  // gp after tax
        final int    TOP        = top > 0 ? top : 20;

        Map<String, Object> result = new LinkedHashMap<>();
        Map<Integer, WikiPriceService.ItemMeta> allMeta = wikiPriceService.getAllMeta();
        if (allMeta == null || allMeta.isEmpty())
        {
            result.put("error", "Price mapping unavailable; try again shortly.");
            return result;
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        final int TIMEFRAME_MIN = 5; // MCP path: size qty to ~5 minutes of volume, not the full GE limit
        long nowSec = System.currentTimeMillis() / 1000L;
        for (WikiPriceService.ItemMeta m : allMeta.values())
        {
            if (m.limit <= 0) continue; // unknown buy limit — never invent a quantity
            if (m.name != null)
            {
                String n = m.name.toLowerCase();
                if (n.contains("placeholder") || n.startsWith("broken ") || n.contains("(nz)")) continue;
            }
            WikiPriceService.PriceData  pd = wikiPriceService.getPrice(m.id);
            WikiPriceService.VolumeData v  = wikiPriceService.getVolume1h(m.id);
            WikiPriceService.VolumeData v5 = wikiPriceService.getVolume5m(m.id);
            if (v == null) continue;
            int vol = v.totalVol();
            if (vol < MIN_VOLUME) continue;
            if (Math.min(v.highVol, v.lowVol) < 150) continue;

            int buy, sell;
            if (v5 != null && v5.avgLow > 0 && v5.avgHigh > v5.avgLow)
            {
                buy = v5.avgLow; sell = v5.avgHigh;
            }
            else if (v.avgLow > 0 && v.avgHigh > v.avgLow)
            {
                buy = v.avgLow; sell = v.avgHigh;
            }
            else if (pd != null && pd.low > 0 && pd.high > pd.low
                && nowSec - pd.highTime <= 90 * 60 && nowSec - pd.lowTime <= 90 * 60)
            {
                buy = pd.low; sell = pd.high;
            }
            else continue;

            long tax = GeTax.taxAmount(m.id, sell);
            int margin = (int) (sell - buy - tax);
            if (margin < MIN_MARGIN) continue;
            double marginPct = margin * 100.0 / buy;
            if (marginPct > 12) continue; // wide spread = stale/odd, not a real flip

            double imbalance = Math.abs(v.highVol - v.lowVol) / (double) vol;
            if (imbalance > 0.55) continue;

            int perHour = Math.max(1, Math.min(v.highVol, v.lowVol)); // bottleneck side
            double perMinute = perHour / 60.0;
            if (v5 != null)
            {
                int side5 = Math.max(0, Math.min(v5.highVol, v5.lowVol));
                if (side5 > 0) perMinute = side5 / 5.0;
            }
            int liqQty = Math.max(1, (int) Math.floor(perMinute * TIMEFRAME_MIN));
            int qtyCap = Math.min(m.limit, liqQty);
            if (capital > 0) qtyCap = (int) Math.min(qtyCap, capital / buy);
            if (qtyCap < 1) continue; // can't afford even one within the capital budget
            double fillHrs = (double) qtyCap / perHour;

            double spreadRisk = marginPct > 15 ? 0.35 : 0;
            double liqRisk    = vol < MIN_VOLUME * 4 ? 0.4 : 0;
            double risk       = Math.min(0.9, imbalance * 0.6 + spreadRisk + liqRisk);
            double turnover   = 1.0 / Math.max(0.25, fillHrs);
            double score      = (double) margin * qtyCap * turnover * (1 - risk);

            List<String> flags = new ArrayList<>();
            if (imbalance > 0.5) flags.add("one-sided");
            if (spreadRisk > 0)  flags.add("wide-spread");
            if (liqRisk > 0)     flags.add("thin");

            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", m.name);
            s.put("id", m.id);
            s.put("buy_at", buy);
            s.put("sell_at", sell);
            s.put("margin_post_tax", margin);
            s.put("margin_pct", Math.round(marginPct * 10) / 10.0);
            s.put("volume_1h", vol);
            s.put("ge_limit", m.limit);
            s.put("est_fill_hours", Math.round(fillHrs * 100) / 100.0);
            s.put("suggested_qty", qtyCap);
            s.put("cost", (long) buy * qtyCap);
            s.put("projected_profit", (long) margin * qtyCap);
            s.put("flags", flags);
            s.put("score", Math.round(score));
            rows.add(s);
        }

        rows.sort((a, b) -> Long.compare(((Number) b.get("score")).longValue(),
                                         ((Number) a.get("score")).longValue()));

        result.put("prices_age_seconds", wikiPriceService.getPricesAgeSeconds());
        result.put("count", rows.size());
        result.put("suggestions", rows.size() > TOP ? new ArrayList<>(rows.subList(0, TOP)) : rows);
        result.put("note", "Market-wide flip candidates ranked by margin-after-tax x liquidity, "
            + "penalised for risk (thin / one-sided / wide-spread). GE tax 2% (cap 5M). "
            + "Ironman can only buy bonds on the GE; UIM cannot use the GE at all. Fill time "
            + "is a volume estimate, not a guarantee.");
        return result;
    }

    /**
     * SELL-side suggestions: for items the player is holding (open buy positions from the
     * flip tracker), a suggestion to sell at the current market high, priced after tax and
     * netted against the average buy. Only non-loss sells (current sell &gt;= avg buy) are
     * returned, ranked by profit. Shaped like the buy suggestions so the same card renders
     * them, with side="sell". Client-free (uses cached wiki prices) — safe off the EDT.
     */
    public List<Map<String, Object>> buildHeldSellSuggestions(List<Map<String, Object>> openPositions)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        if (openPositions == null) return out;
        for (Map<String, Object> pos : openPositions)
        {
            int id = pos.get("item_id") instanceof Number ? ((Number) pos.get("item_id")).intValue() : 0;
            long qty = pos.get("qty") instanceof Number ? ((Number) pos.get("qty")).longValue() : 0;
            long avgBuy = pos.get("avg_buy") instanceof Number ? ((Number) pos.get("avg_buy")).longValue() : 0;
            if (id <= 0 || qty <= 0) continue;

            WikiPriceService.PriceData pd = wikiPriceService.getPrice(id);
            if (pd == null || pd.high <= 0) continue;
            long sell = pd.high;
            long tax = GeTax.taxAmount(id, sell);
            long marginEa = sell - tax - avgBuy; // may be negative = underwater

            WikiPriceService.ItemMeta m = wikiPriceService.getMeta(id);
            List<String> flags = new ArrayList<>();
            if (marginEa < 0) flags.add("cut loss");
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", id);
            s.put("name", m != null && m.name != null ? m.name : ("item " + id));
            s.put("side", "sell");
            s.put("loss", marginEa < 0);
            s.put("buy_at", avgBuy);              // your cost basis
            s.put("sell_at", sell);
            s.put("margin_post_tax", marginEa);
            s.put("margin_pct", avgBuy > 0 ? Math.round(marginEa * 1000.0 / avgBuy) / 10.0 : 0.0);
            s.put("suggested_qty", qty);
            s.put("ge_limit", 0);                 // no buy-limit on selling
            s.put("projected_profit", marginEa * qty);
            s.put("flags", flags);
            s.put("score", marginEa * qty);
            out.add(s);
        }
        out.sort((a, b) -> Long.compare(((Number) b.get("score")).longValue(),
                                        ((Number) a.get("score")).longValue()));
        return out;
    }

    /**
     * A compact flip quote for a SINGLE item, using the same tax/margin math as
     * {@link #buildFlipSuggestions}. For the GE offer-setup overlay: given the item the
     * player is configuring, return suggested buy/sell, post-tax margin, 1h volume, buy
     * limit and a short verdict. May block on a price/volume fetch, so callers must run it
     * OFF the client/render thread and cache the result. Returns {@code null} if there is
     * no usable price data for the item.
     */
    public Map<String, Object> flipQuoteForItem(int itemId)
    {
        if (itemId <= 0) return null;
        WikiPriceService.PriceData  pd = wikiPriceService.getPrice(itemId);
        WikiPriceService.VolumeData v  = wikiPriceService.getVolume1h(itemId);
        WikiPriceService.ItemMeta   m  = wikiPriceService.getMeta(itemId);
        if (pd == null) return null;

        int buy = pd.low, sell = pd.high;
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("id", itemId);
        q.put("name", m != null ? m.name : ("item " + itemId));
        q.put("buy_at", buy);
        q.put("sell_at", sell);
        q.put("ge_limit", m != null ? m.limit : 0);
        q.put("volume_1h", v != null ? v.totalVol() : 0);

        if (buy <= 0 || sell <= 0 || sell <= buy)
        {
            q.put("margin_post_tax", 0);
            q.put("margin_pct", 0.0);
            q.put("verdict", "no margin");
            return q;
        }
        long tax    = GeTax.taxAmount(itemId, sell);
        int  margin = (int) (sell - buy - tax);
        double marginPct = margin * 100.0 / buy;
        q.put("margin_post_tax", margin);
        q.put("margin_pct", Math.round(marginPct * 10) / 10.0);

        List<String> flags = new ArrayList<>();
        if (v != null)
        {
            int vol = v.totalVol();
            double imbalance = vol > 0 ? Math.abs(v.highVol - v.lowVol) / (double) vol : 1;
            if (imbalance > 0.5) flags.add("one-sided");
            if (vol < 200)       flags.add("thin");
        }
        if (marginPct > 25) flags.add("wide-spread");
        q.put("flags", flags);

        String verdict = margin <= 0 ? "skip"
            : (!flags.isEmpty() ? "risky (" + String.join(", ", flags) + ")"
            : (marginPct >= 3 ? "good margin" : "thin margin"));
        q.put("verdict", verdict);
        return q;
    }

    public Map<String, Object> buildMoneyMakingContext()
    {
        if (!isLoggedIn()) return errorMap("Player is not logged in");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("coins",         getCoins());
        result.put("location",      buildLocation());
        result.put("combat_level",  client.getLocalPlayer().getCombatLevel());
        // Slayer task context
        Map<String, Object> slayer = buildSlayerTask();
        if (slayer.get("task") != null) result.put("slayer_task", slayer);
        // Key stats for common money making (Slayer, RC, Farming, Herblore, etc.)
        Map<String, Object> stats = new LinkedHashMap<>();
        for (net.runelite.api.Skill skill : net.runelite.api.Skill.values())
        {
            if (skill == net.runelite.api.Skill.OVERALL) continue;
            stats.put(skill.getName().toLowerCase(), client.getRealSkillLevel(skill));
        }
        result.put("stats",         stats);
        result.put("members_world", client.getWorldType().contains(net.runelite.api.WorldType.MEMBERS));
        net.runelite.api.vars.AccountType mmAt = client.getAccountType();
        result.put("account_type", mmAt.name().toLowerCase());
        result.put("is_ironman",   mmAt != net.runelite.api.vars.AccountType.NORMAL);
        result.put("is_uim",       mmAt == net.runelite.api.vars.AccountType.ULTIMATE_IRONMAN);
        if (mmAt != net.runelite.api.vars.AccountType.NORMAL)
            result.put("note", "Ironman restrictions apply -- no player trading, GE only usable for bonds. All items must be self-obtained. Money making methods must be self-sufficient (bossing, skilling, alching, thieving etc.).");
        return result;
    }
}
