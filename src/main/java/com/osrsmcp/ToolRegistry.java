package com.osrsmcp;


import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Singleton
public class ToolRegistry
{
    @Inject private PlayerDataService playerDataService;
    @Inject private WikiBucketService wikiBucketService;
    @Inject private QuestPlanService questPlanService;
    @Inject private InteropService interopService;
    @Inject private WiseOldManService wiseOldManService;
    @Inject private DestinationService destinationService;
    @Inject private TaskService taskService;
    @Inject private ClientThread clientThread;
    @Inject private OsrsMcpConfig config;
    @Inject private Gson gson;

    // Domain guidance handed to the AI client on connect. Steers how it should
    // reason about the data these tools expose, so advice is OSRS-aware by default.
    private static final String INSTRUCTIONS =
        "This server exposes live Old School RuneScape (OSRS) data for the connected RuneLite account. "
        + "RESPONSE STYLE (important): keep it digestible and match depth to the question. Lead with the single biggest takeaway. For 'what next', give at MOST 2 concrete next moves, each from a DIFFERENT category (skilling / combat i.e. slayer or bossing / a single quest / a diary / money-making or flipping) -- pick the 2 with the best value now, then offer the rest ('want combat, money-making or diary ideas instead?'). Be realistic about scale: never present something gated behind a huge grind (e.g. a diary needing a skill dozens of levels above the player's current) as a near-term step -- name the grind and set it aside. For any SKILLING suggestion, ALWAYS give the method, its XP/hr and the rough hours to the target (use get_training_methods + the XP gap) and where to train (name the spot; offer to draw the route with path_to only if they ask). For QUESTS, suggest ONE high-value quest, not a batch. Do NOT open with a giant multi-step plan; ASK 'want the full plan?' before dumping one, and even then lead with a 2-3 line TL;DR. A few lines or a small table beats paragraphs.\n"
        + "TIME BUDGET: if the player says how long they have ('I have 30 mins'), only suggest things that FIT -- use get_training_methods XP/hr and get_dailies times to size it, and say roughly what they'll get done. Short sessions (<30 min) suit a dailies sweep (get_dailies), a farm/bird-house run, a slayer task, or a chunk of a skilling method; save long quests/grinds for when they have time. Prefer things that fit the window over the theoretically-optimal.\n"
        + "DAILIES: for 'what are my dailies / recurring tasks', call get_dailies (battlestaves, herb boxes, Miscellania, farm/tree/bird-house runs, etc.) and get_farm_run for live patch state; list what's available now with rough times.\n"
        + "When giving advice:\n"
        + "- Always call get_all first for a cheap overview, then drill in with specific tools.\n"
        + "- Respect account type: check is_ironman/is_uim/is_hcim in stats. Ironmen cannot buy gear on the GE (bonds only), so never suggest 'just buy X' for them; suggest how to obtain it instead.\n"
        + "- For 'what should I do next', prefer get_next_goals, then get_diary_requirements for concrete diary tasks the player already qualifies for.\n"
        + "\nPLANNING (multi-step goals): to plan quests/levels/diaries, use the planner tools instead of guessing:\n"
        + "- get_quest_rewards: every quest's requirements, XP rewards, unlocks and a live meets_requirements flag (with blocked_by). Use to see what's startable now and what each quest is worth.\n"
        + "- project_plan: the source of truth for 'what if'. Give it quests to assume done and/or skill training targets; it returns the EXACT resulting levels, XP still to train, newly-eligible quests and newly requirement-complete diary regions. Never do quest/level arithmetic yourself -- call project_plan and trust it. Propose an order, verify each step with it, iterate.\n"
        + "- get_training_methods: approximate XP/hr per method (+ live meets_requirements) so you can turn 'XP to train' into rough hours. Rates are ballpark and dated -- say so.\n"
        + "- get_optimal_quest_route: the wiki's Optimal Quest Guide ordering annotated with the player's state (next_startable_now / next_blocked). Anchor quest plans on this route, then adapt to the goal.\n"
        + "- get_wom_gains / get_wom_profile: recent XP/KC gains and progress rates from Wise Old Man -- ground 'what next' advice in what the player has actually been doing.\n"
        + "\nSIDE-EFFECTING TOOLS -- CONSENT REQUIRED (critical): a few tools change what the player sees or their saved data, not just read it: path_to (draws/clears a route on the game screen via Shortest Path), add_task / complete_task / remove_task (edit the player's saved goals list), view_inventory_setup (opens a setup in-game), export_inventory_setup (creates an importable loadout). NEVER call these on your own initiative, as a side effect of an advice answer, or 'to be helpful'. Call them ONLY when the player's latest message explicitly asks for that action ('guide me to X' / 'draw a path to' -> path_to; 'add a goal' / 'track this' -> add_task; 'open my X setup' -> view_inventory_setup). For a plain 'what should I do next' or any advice question, DESCRIBE where to go and OFFER ('want me to draw the route?' / 'want me to save that as a goal?') -- do not do it. When unsure whether the player wants the side effect, ask first.\n"
        + "OTHER PLUGINS / ACTIVITIES: get_inventory_setups + view_inventory_setup (gear presets). export_inventory_setup designs a loadout the player imports. get_tcg_unlocks (OSRS TCG: only suggest unlocked content). path_to draws a route via Shortest Path -- pass a get_destinations name or coords (guide, never auto-move; consent-gated, see above). get_farm_run for herb runs (states, kit, per-patch teleport + path_to coords).\n"
        + "- Rank suggestions by leverage: unlocks that gate multiple diaries/quests, skills within a level or two of a milestone, and content the player's gear and stats already support.\n"
        + "- For gear/BiS questions use get_equipment_stats and get_bis_comparison, and note when the player is not in combat gear (stats will read low).\n"
        + "- For money-making, prefer the player's measured context (get_money_making_context, get_boss_kc) and account for GE buy limits and the 1% GE tax on flips.\n"
        + "- Diary 'requirements met' (get_diary_requirements) means the player QUALIFIES for a task, not that it is done; cross-reference get_diary_states for tier completion.\n"
        + "- Be specific and quantitative: cite levels, XP remaining, GP values and item names from the tools rather than generic advice.\n"
        + "- NEVER state a GP / gold value you have not actually computed from tool data. To give a money figure, get the item's live price with get_item_prices and multiply by a quantity you can justify (e.g. XP-to-go / XP-per-action, or kills * drop value). Show the arithmetic briefly. If you cannot ground it, say 'I'd need to check prices' -- do NOT estimate gp from memory. (A number like 'tens of millions' from finishing one 99 is almost always wrong: e.g. ~333k Woodcutting XP is under ~900 redwood logs, a few hundred k gp, not tens of millions.)\n"
        + "- Likewise for TIME and XP/HR: base 'hours to go' and rate claims on get_training_methods (and the XP gap), not recalled numbers; call the rates ballpark and dated. Do not assert a specific hours-to-goal you didn't derive from a tool rate.\n"
        + "\nWIKI KNOWLEDGE (Bucket gateway): the player's own state comes from the in-game tools above; general game knowledge comes from the wiki. Routing:\n"
        + "- Combat achievements -> get_combat_achievements (filter by tier/monster).\n"
        + "- Monster stats / weakness / max hit / slayer level -> wiki_bucket_query('infobox_monster', ...) (fields: combat_level, hitpoints, max_hit, slayer_level, elemental_weakness, *_defence_bonus, attack_style, attack_speed).\n"
        + "- Item GE buy limit / alch value / weight -> wiki_bucket_query('infobox_item', ...) (item_id, buy_limit, high_alchemy_value, value, weight, tradeable).\n"
        + "- Equipment bonuses by slot -> wiki_bucket_query('infobox_bonuses', ...) keyed by where('page_name','<item>').\n"
        + "- BiS gear -> wiki_bucket_query('recommended_equipment', ...); money-making -> 'money_making_guide'; drops -> 'dropsline'; recipes -> 'recipe'.\n"
        + "- For anything else, discover with wiki_list_buckets then wiki_bucket_schema before querying.\n"
        + "- Narrative content (strategy, mechanics, walkthroughs) is NOT in the buckets -- use wiki_search then wiki_get_page for readable prose.\n"
        + "- Query hygiene: always pass 'select' and a small 'limit'; filter with 'where'; some buckets hold detail in a json/*_json field to parse. Never query the wiki for the player's character data.";

    // Tools that hit the wiki over HTTP and read no game state. Routed OFF the
    // client thread so a slow fetch never freezes the game (and isn't bound by
    // the 5s client-thread budget).
    private static final Set<String> NETWORK_TOOLS = new HashSet<>(Arrays.asList(
        "wiki_list_buckets", "wiki_bucket_schema", "wiki_bucket_query", "get_combat_achievements",
        "wiki_search", "wiki_get_page",
        // pure wiki-scrape tools: no live game state, so a slow page fetch must not
        // hold the client thread (they only take a name argument and hit HTTP).
        "get_drop_table", "get_npc_info",
        // live GE price lookups (blocking HTTP, no live game state needed)
        "get_item_prices", "get_price_trends", "get_flip_suggestions",
        // inter-plugin messaging (post on client thread internally, then await a reply)
        "get_tcg_unlocks", "path_to", "get_inventory_setups", "view_inventory_setup", "get_destinations",
        // Wise Old Man web API (progress history / gains)
        "get_wom_profile", "get_wom_gains"));

    // Hybrid tools: read live game state, THEN do per-item HTTP. Handled in two
    // phases -- a quick client-thread snapshot, then the fetch off the client thread.
    private static final Set<String> HYBRID_TOOLS = new HashSet<>(Arrays.asList(
        "get_equipment_stats", "get_bis_comparison"));

    public String getInstructions() { return INSTRUCTIONS; }

    /**
     * Invoke a tool by name, returning its result map (the same payload the HTTP
     * transport wraps into a JSON-RPC result). Routes NETWORK_TOOLS off the client
     * thread, HYBRID_TOOLS in two phases, and everything else on the client thread.
     * May throw {@link java.util.concurrent.TimeoutException} when a game-thread
     * call exceeds the 5s budget -- callers should NOT swallow it.
     */
    public Map<String, Object> callTool(String toolName, JsonObject args) throws Exception
    {
        if (NETWORK_TOOLS.contains(toolName)) {
            return dispatchNetworkTool(toolName, args);
        } else if (HYBRID_TOOLS.contains(toolName)) {
            return dispatchHybridTool(toolName, args);
        } else {
            java.util.concurrent.CompletableFuture<Map<String, Object>> future =
                new java.util.concurrent.CompletableFuture<>();
            clientThread.invokeLater(() -> {
                try   { future.complete(dispatchTool(toolName, args)); }
                catch (Exception e) { future.completeExceptionally(e); }
            });
            return future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /** The JSON-RPC result object for tools/list. */
    public JsonObject getToolsListResult()
    {
        JsonObject result = new JsonObject();
        JsonArray tools = new JsonArray();
        tools.add(buildTool("get_player_stats", "Get the player's current skill levels, XP, XP to next level, and combat level."));
        tools.add(buildTool("get_equipment",    "Get a list of items the player currently has equipped, organised by slot."));
        tools.add(buildTool("get_inventory",    "Get the contents of the player's inventory including item names and quantities."));
        tools.add(buildTool("get_location",     "Get the player's current in-game location including world coordinates and area name."));
        tools.add(buildTool("get_all",          "Get all available player data in a single call: stats, equipment, inventory and location."));
        tools.add(buildTool("get_quest_states",  "Get the state of all quests (completed, in progress, not started) and total quest points."));
        tools.add(buildTool("get_diary_states",  "Get completion status of all Achievement Diaries across all regions and tiers (easy/medium/hard/elite)."));
        tools.add(buildTool("get_diary_requirements", "Get task-level Achievement Diary requirements for every region, each live-checked against the account. For each task, shows the skill/quest requirements and whether they are met, including how many levels short you are. Use this to find diary tasks you already qualify for and exactly what is blocking the rest."));
        tools.add(buildTool("get_next_goals",    "Get a ranked list of the most actionable next goals derived from live data: skills closest to a level-up and to 99, quests already in progress, and diary regions where you already meet the most task requirements (nearest to a full clear, with blocking skills)."));
        tools.add(buildTool("get_quest_rewards", "For every quest: its requirements (prerequisite quests, skill levels, quest points), XP rewards, skill-choice lamps, notable unlocks, and a live meets_requirements flag checked against the account (with blocked_by reasons). Use for quest planning -- which quests you can start now, and what each is worth. account_type and eligible_now_count summarise the set."));
        {
            JsonObject props = new JsonObject();
            JsonObject cq = new JsonObject(); cq.addProperty("type", "array"); JsonObject cqi = new JsonObject(); cqi.addProperty("type", "string"); cq.add("items", cqi);
            cq.addProperty("description", "Quest names to assume completed (their fixed XP rewards are applied and they count as prerequisites).");
            props.add("complete_quests", cq);
            JsonObject tr = new JsonObject(); tr.addProperty("type", "object"); tr.addProperty("description", "Training targets, skill -> target level (1-99), e.g. {\"agility\":70}.");
            props.add("train", tr);
            tools.add(buildToolWithSchema("project_plan", "Deterministically simulate a plan against the live account and report the exact outcome: resulting_levels, xp_gained_from_quests, xp_still_to_train, newly_eligible_quests, and diary regions that become requirement-complete. Give it quests to assume done and/or skill training targets; it does the multi-step XP/level/requirement arithmetic exactly so advice is trustworthy. Propose an ordering, call this to verify each step, iterate.", props, new String[]{}));
        }
        {
            JsonObject props = new JsonObject();
            props.add("skill", strProp("Optional skill to filter to, e.g. 'agility'."));
            tools.add(buildToolWithSchema("get_training_methods", "Curated training methods with approximate XP/hr and start requirements, so you can reason about TIME not just XP. Optionally filter by skill. When logged in, each method is annotated with meets_requirements and your current_level. Rates are ballpark and dated -- state that when advising.", props, new String[]{}));
        }
        tools.add(buildTool("get_dailies", "Daily / weekly / recurring tasks (battlestaves, herb boxes, Miscellania, farm/tree/bird-house runs, buckets of sand, Tears of Guthix), requirement-checked live so 'available' = doable now with rough times. Use for 'what are my dailies' and as quick fillers for short play sessions. Pair with get_farm_run for live patch state."));
        {
            JsonObject tp = new JsonObject();
            tp.add("text", strProp("The goal text, e.g. 'Finish 70 Agility for the shortcut'."));
            tp.add("metric", strProp("Optional AUTO-complete metric: a skill name (e.g. 'agility'), 'total_level' or 'combat_level'. Omit for a manual task."));
            tp.add("target", numProp("Target value for the metric (e.g. 70). Required when metric is set."));
            tools.add(buildToolWithSchema("add_task", "Add a goal to the player's RuneAssist checklist. AUTO tasks (metric + target) tick themselves when the live value is reached; without a metric it's a manual task. Persists across sessions. Use this to turn a plan step into a tracked goal.", tp, new String[]{"text"}));
        }
        tools.add(buildTool("list_tasks", "The player's RuneAssist goals/tasks checklist (active vs done), re-evaluated live so AUTO tasks reflect current progress. Use for 'what are my goals / tasks'."));
        {
            JsonObject cp = new JsonObject();
            cp.add("id", numProp("The task id (from list_tasks)."));
            tools.add(buildToolWithSchema("complete_task", "Mark a task done (for manual tasks, or to tick one off early).", cp, new String[]{"id"}));
        }
        {
            JsonObject rp2 = new JsonObject();
            rp2.add("id", numProp("The task id (from list_tasks)."));
            tools.add(buildToolWithSchema("remove_task", "Remove a task from the checklist.", rp2, new String[]{"id"}));
        }
        {
            JsonObject props = new JsonObject();
            JsonObject onlyRem = new JsonObject(); onlyRem.addProperty("type", "boolean"); onlyRem.addProperty("description", "If true, omit already-completed quests and return only what's left of the route.");
            props.add("only_remaining", onlyRem);
            tools.add(buildToolWithSchema("get_optimal_quest_route", "The OSRS Wiki Optimal Quest Guide ordering as a planning prior, annotated with your live quest state: how far you've progressed, the next route quest you can start now (next_startable_now), and upcoming ones still blocked (next_blocked, with reasons). Use it to anchor a quest plan on the community route, then adapt to the player's goal. Not account-specific ordering -- it's a recommendation.", props, new String[]{}));
        }
        {
            JsonObject props = new JsonObject();
            props.add("query", strProp("Search term, e.g. 'Zulrah strategy' or 'fairy ring codes'."));
            props.add("limit", numProp("Max results (default 6, max 20)."));
            tools.add(buildToolWithSchema("wiki_search", "Full-text search the OSRS Wiki for pages by term, returning titles + snippets. Use for narrative/how-to content the structured buckets don't hold (strategy, mechanics, walkthroughs). Then read a page with wiki_get_page.", props, new String[]{"query"}));
        }
        {
            JsonObject props = new JsonObject();
            props.add("title", strProp("Exact page title (from wiki_search), e.g. 'Zulrah/Strategies'."));
            props.add("max_chars", numProp("Max characters to return (default 6000, max 20000)."));
            tools.add(buildToolWithSchema("wiki_get_page", "Get the readable plain-text content of a wiki page by title -- prose, strategy, walkthroughs. Tables/infoboxes are stripped (use wiki_bucket_* for structured stats). Pair with wiki_search to find the title.", props, new String[]{"title"}));
        }
        {
            JsonObject props = new JsonObject();
            props.add("username", strProp("RSN to look up. Omit to use the logged-in player."));
            tools.add(buildToolWithSchema("get_wom_profile", "Wise Old Man overview for a player (wiseoldman.net): total level/XP, EHP, EHB, time-to-max, combat level, and per-skill levels/xp/rank. Progress data our live/wiki tools don't have. Omit username to use the logged-in account.", props, new String[]{}));
        }
        {
            JsonObject props = new JsonObject();
            props.add("username", strProp("RSN to look up. Omit to use the logged-in player."));
            props.add("period", strProp("Time window: five_min, day, week (default), month, or year."));
            tools.add(buildToolWithSchema("get_wom_gains", "Wise Old Man GAINS over a period: XP gained per skill, boss KC gained, activity score gained (non-zero only, sorted). Shows what the player has ACTUALLY been doing lately -- use it to ground 'what next' advice in real recent activity. Omit username to use the logged-in account.", props, new String[]{}));
        }
        tools.add(buildTool("get_inventory_setups", "List the player's saved Inventory Setups (gear/inventory presets) by name, via the Inventory Setups plugin. Use to know their intended loadouts (e.g. a 'Zulrah' setup) before advising on gear. Returns setups_available and the names."));
        {
            JsonObject props = new JsonObject();
            props.add("name", strProp("Setup name to open (from get_inventory_setups)."));
            JsonObject clr = new JsonObject(); clr.addProperty("type", "boolean"); clr.addProperty("description", "If true, clear the active setup instead of opening one.");
            props.add("clear", clr);
            tools.add(buildToolWithSchema("view_inventory_setup", "Open one of the player's Inventory Setups in-game and filter the bank to it (Inventory Setups plugin required). Read-only: it shows the setup, it does not move or equip items. Use to help the player gear up for an activity.", props, new String[]{}));
        }
        tools.add(buildTool("get_tcg_unlocks", "Read the player's OSRS TCG collection from the TCG plugin (owned card names, owned item ids, owned NPC ids). In OSRS TCG, items/teleports/monsters stay locked until their card is pulled -- use this to only recommend unlocked content and to flag what they'd need to pull. Returns tcg_available=false if the TCG plugin isn't running."));
        {
            JsonObject props = new JsonObject();
            props.add("name", strProp("A named destination (from get_destinations) -- e.g. 'Catherby herb patch', 'Duradel', 'Grand Exchange'. Alternative to x/y."));
            props.add("x", numProp("Destination world X coordinate (if not using name)."));
            props.add("y", numProp("Destination world Y coordinate (if not using name)."));
            props.add("plane", numProp("Destination plane/level (0-3, default 0)."));
            JsonObject clr = new JsonObject(); clr.addProperty("type", "boolean"); clr.addProperty("description", "If true, clear the current drawn path instead of setting one.");
            props.add("clear", clr);
            tools.add(buildToolWithSchema("path_to", "Draw an in-game route using the Shortest Path plugin (requires it installed + enabled). Only draws a path -- it never moves the character. Pass a named destination (name) or a coordinate (x/y). Use for 'guide me to X': a farm patch, slayer master, quest step. For places not in get_destinations, get coordinates from the wiki (infobox_location bucket). clear:true removes the route.", props, new String[]{}));
        }
        {
            JsonObject props = new JsonObject();
            props.add("category", strProp("Optional filter: herb_patch, bank, or slayer_master."));
            tools.add(buildToolWithSchema("get_destinations", "List named destinations (herb patches, banks, slayer masters) with coordinates that path_to accepts by name. Optionally filter by category.", props, new String[]{}));
        }
        tools.add(buildTool("get_farm_run", "Herb-run assistant from live patch tracking: which patches are ready/growing/empty, a recommendation, the kit to bring, and each patch's teleport hint + coordinates (use path_to with a patch's destination_name to route there). Herb patches only. States are cached from last visit."));
        {
            JsonObject props = new JsonObject();
            props.add("name", strProp("Name for the new setup."));
            JsonObject eq = new JsonObject(); eq.addProperty("type", "object"); eq.addProperty("description", "Equipment by slot -> item id. Slots: head, cape, amulet, weapon, body, shield, legs, hands, feet, ring, ammo.");
            props.add("equipment", eq);
            JsonObject inv = new JsonObject(); inv.addProperty("type", "array"); JsonObject invItem = new JsonObject(); invItem.addProperty("type", "integer"); inv.add("items", invItem);
            inv.addProperty("description", "Inventory item ids in order (up to 28). Use an object {\"id\":n,\"q\":n} for stack quantities.");
            props.add("inventory", inv);
            props.add("notes", strProp("Optional notes for the setup."));
            tools.add(buildToolWithSchema("export_inventory_setup", "Design a gear/inventory loadout and get an Inventory Setups IMPORT STRING for the player to paste into the plugin (Import button). Safe + additive -- never overwrites existing setups. Needs item IDs (get them from the wiki infobox_item.item_id or get_item_prices). Give it a name, equipment (slot->id), and inventory (list of ids).", props, new String[]{"name"}));
        }
        tools.add(buildTool("reload_planner_data", "Reload the planner's bundled data (quest_data.json, training_methods.json) from disk without restarting the client -- picks up a regenerated copy placed in the external override dir. Returns where each dataset was loaded from and its counts. Plugin code changes still require a restart."));
        tools.add(buildTool("get_slayer_task",   "Get current Slayer task: creature name, remaining count, location, points and streak."));
        tools.add(buildTool("get_clue_scroll",   "Check if the player has an active clue scroll in their inventory and which tier it is."));
        tools.add(buildTool("get_nearby_npcs",     "Get a list of NPCs currently visible to the player, sorted by combat level. Includes name, combat level, and approximate health."));
        tools.add(buildTool("get_world_info",      "Get the current world number and type (members, PvP, high risk, deadman, seasonal, skill total, etc.)."));
        tools.add(buildTool("get_prayers",         "Get currently active prayers and unlock status for special prayers (Preserve, Rigour, Augury, Chivalry, Piety)."));
        tools.add(buildTool("get_collection_log",  "Get the player's collection log progress: total unique items obtained, total possible, and a breakdown by category (bosses, raids, clues, minigames, other)."));
        tools.add(buildTool("get_bank_classified",   "Get bank items organised by category: equipment (with slot), food, potions, runes, ammo, materials, other. Includes Wiki examine text."));
        tools.add(buildTool("get_bank_summary",       "Get total bank value, item count and coin balance. Requires bank to have been opened this session."));
        tools.add(buildTool("get_bank_top_value",      "Get the top 100 items in the bank sorted by total GE value. Requires bank open."));
        tools.add(buildTool("get_bank_coins",           "Get coin totals across inventory and bank combined."));
        tools.add(buildTool("get_cache_index",       "List all available cache files with their last-updated timestamps. Use this to see what persistent data the plugin has stored (bank, equipment, quests, farming, seeds, character)."));
        tools.add(buildTool("read_cache",           "Read the full contents of a specific cache file by name (e.g. bank.md, equipment.md, quests.md, farming.md, seed_vault.md, character.md). Returns human-readable markdown."));
        tools.add(buildTool("get_bis_comparison",   "Compare current equipped gear against banked items slot-by-slot for a given combat style. Pass style as melee, ranged, or magic. Returns upgrades available in your bank with stat scores."));
        tools.add(buildTool("get_seed_vault",        "Get the contents of the player's seed vault: seeds, saplings, and other items with quantities and GE prices. Requires opening the seed vault first."));
        tools.add(buildTool("get_farming_patches",  "Get the state of all 9 herb patches: ready to harvest, growing (with time remaining), empty, or diseased/dead. Includes live GE price for harvestable herbs. Data persists between visits via Time Tracking plugin config."));
        tools.add(buildTool("get_drop_table",        "Get the drop table for any OSRS monster from the Wiki. Returns always drops, unique drops (rare), and regular drops -- each with GE price and expected GP per kill. Pass name as the monster name."));
        tools.add(buildTool("get_equipment_stats",  "Get full equipment stat bonuses for currently equipped gear: attack/defence/strength/prayer bonuses per slot, totals, and estimated max hit. Stats fetched live from OSRS Wiki."));
        tools.add(buildTool("get_npc_info",          "Fetch monster stats from the OSRS Wiki by name: combat level, hitpoints, defence bonuses, max hit, attack speed, weaknesses, immune to poison/venom. Pass name as the monster name."));
        tools.add(buildTool("get_combat_context",  "Get combat context: effective levels, attack style, spec energy, active prayers, potion detection, and current target NPC."));
        tools.add(buildTool("get_boss_kc",          "Get boss kill counts: game-tracked slayer boss KCs and profile-stored KCs from ChatCommands plugin."));
        tools.add(buildTool("get_price_trends",  "Get price trend data for specific items: current price, 5m and 1h averages, trade volume, and rising/falling/stable direction. Pass item_ids array."));
        tools.add(buildTool("get_item_prices",          "Get live Wiki GE prices for specific item IDs. Pass item_ids as an array of integers."));
        {
            JsonObject flipProps = new JsonObject();
            flipProps.add("capital", numProp("Optional GP budget; suggestions are sized to it (fills suggested_qty, cost, projected_profit)."));
            flipProps.add("min_volume", numProp("Optional minimum 1h traded volume (default 500)."));
            flipProps.add("min_margin", numProp("Optional minimum post-tax margin in gp (default 20)."));
            flipProps.add("max_results", numProp("Optional number of suggestions to return (default 20)."));
            tools.add(buildToolWithSchema("get_flip_suggestions", "MARKET-WIDE flip candidates ranked by margin-after-tax x liquidity (penalised for thin/one-sided/wide-spread risk), from live GE prices + 1h volume. Pass capital to size quantities to your budget. Returns per item: buy/sell price, post-tax margin, margin %, 1h volume, GE limit, suggested_qty, cost, projected_profit, an estimated buy-limit fill time and risk flags. No bank needed. GE tax is 2% (cap 5M); fill time is a volume estimate, not a guarantee. Ironman can only buy bonds; UIM cannot use the GE.", flipProps, new String[]{}));
        }
        tools.add(buildTool("get_money_making_context", "Get location, stats, coins and slayer task for money making method recommendations."));
        tools.add(buildTool("get_installed_plugins", "Get all installed RuneLite plugins (both built-in and Plugin Hub) with their enabled state. Use this to suggest relevant Plugin Hub plugins."));
        tools.add(buildTool("get_ge_offers",          "Get all active Grand Exchange offers including item, quantity, price and state."));
        tools.add(buildTool("get_session_summary",
            "What the player has done since logging in this session: XP gained per skill "
            + "(sorted), total XP gained, levels gained, and minutes played. Great for a "
            + "'what did I get done' recap. Returns an error until the session baseline is "
            + "captured (first tick after login)."));

        // --- Wiki Bucket gateway (structured game data; runs off the game thread) ---
        tools.add(buildTool("wiki_list_buckets", "List the OSRS Wiki's structured-data tables (buckets) -- e.g. combat_achievement, infobox_monster, infobox_item, money_making_guide, dropsline, recipe. Start here to discover what game data is queryable, then use wiki_bucket_schema and wiki_bucket_query."));
        {
            JsonObject props = new JsonObject();
            props.add("bucket", strProp("Bucket table name, e.g. 'infobox_monster' (spaces or underscores both work)."));
            tools.add(buildToolWithSchema("wiki_bucket_schema", "Get the fields and types of one wiki bucket table, so you know what to select/filter. Every row also has an implicit page_name key.", props, new String[]{"bucket"}));
        }
        {
            JsonObject props = new JsonObject();
            props.add("bucket", strProp("Bucket table to query, e.g. 'infobox_item'."));
            JsonObject sel = new JsonObject(); sel.addProperty("type", "array"); JsonObject it = new JsonObject(); it.addProperty("type", "string"); sel.add("items", it);
            sel.addProperty("description", "Fields to return (always specify some; never fetch all).");
            props.add("select", sel);
            JsonObject where = new JsonObject(); where.addProperty("type", "object"); where.addProperty("description", "Equality filters, field -> value. Use page_name for the row's page, e.g. {\"page_name\":\"Abyssal whip\"}.");
            props.add("where", where);
            props.add("limit", numProp("Max rows (default 50, max 500)."));
            props.add("order", strProp("Optional field to order by."));
            props.add("raw", strProp("Advanced: a full Bucket Lua query string (e.g. \"bucket('x').select('a').limit(5).run()\"). If set, the other fields are ignored."));
            tools.add(buildToolWithSchema("wiki_bucket_query", "Query a wiki bucket for structured game data. Provide bucket + select (+ optional where/limit/order), or a raw Lua query. Read-only. For the PLAYER'S OWN data use the in-game tools instead; this is for general game knowledge.", props, new String[]{}));
        }
        {
            JsonObject props = new JsonObject();
            props.add("tier", strProp("Optional tier filter: Easy, Medium, Hard, Elite, Master or Grandmaster."));
            props.add("monster", strProp("Optional boss/monster name filter, e.g. 'Zulrah'."));
            tools.add(buildToolWithSchema("get_combat_achievements", "Get Combat Achievement tasks from the wiki (all 600+, or filtered by tier and/or monster). Returns name, monster, tier, type and task text. Note: these are task definitions, not your completion state.", props, new String[]{}));
        }

        result.add("tools", tools);
        return result;
    }

    private Map<String, Object> dispatchTool(String toolName, JsonObject args)
    {
        switch (toolName)
        {
            case "get_player_stats": if (!config.shareStats())     return privacyError("stats");     return playerDataService.buildStats();
            case "get_equipment":    if (!config.shareEquipment()) return privacyError("equipment"); Map<String,Object> eq = new LinkedHashMap<>(); eq.put("equipment", playerDataService.buildEquipment()); return eq;
            case "get_inventory":    if (!config.shareInventory()) return privacyError("inventory"); Map<String,Object> inv = new LinkedHashMap<>(); inv.put("inventory", playerDataService.buildInventory()); return inv;
            case "get_location":     if (!config.shareLocation())  return privacyError("location");  return playerDataService.buildLocation();
            case "get_all":          return playerDataService.buildSnapshot();
            case "get_quest_states":  return playerDataService.buildQuestStates();
            case "get_diary_states":  return playerDataService.buildDiaryStates();
            case "get_diary_requirements": return playerDataService.buildDiaryRequirements();
            case "get_next_goals":    return playerDataService.buildNextGoals();
            case "get_quest_rewards": return questPlanService.buildQuestRewards();
            case "project_plan":      return questPlanService.buildProjectPlan(jsonToMap(args));
            case "get_training_methods": return questPlanService.buildTrainingMethods(jsonToMap(args));
            case "get_dailies":          return questPlanService.buildDailies();
            case "get_optimal_quest_route": return questPlanService.buildOptimalQuestRoute(jsonToMap(args));
            case "reload_planner_data": return questPlanService.reloadData();
            case "list_tasks":         return taskService.list();
            case "add_task":
            {
                String text   = strArg(args, "text", null);
                String metric = strArg(args, "metric", null);
                int    target = args.has("target") && args.get("target").isJsonPrimitive() ? args.get("target").getAsInt() : 0;
                return taskService.add(text, metric, target);
            }
            case "complete_task":      return taskService.complete(args.has("id") && args.get("id").isJsonPrimitive() ? args.get("id").getAsLong() : -1);
            case "remove_task":        return taskService.remove(args.has("id") && args.get("id").isJsonPrimitive() ? args.get("id").getAsLong() : -1);
            case "get_slayer_task":   return playerDataService.buildSlayerTask();
            case "get_clue_scroll":   return playerDataService.buildClueScroll();
            case "get_nearby_npcs":       return playerDataService.buildNearbyNpcs();
            case "get_world_info":         return playerDataService.buildWorldInfo();
            case "get_prayers":           return playerDataService.buildPrayers();
            case "get_collection_log":    return playerDataService.buildCollectionLog();
            case "get_bank_classified":    return playerDataService.buildBankClassified();
            case "get_bank_summary":       return playerDataService.buildBankSummary();
            case "get_bank_top_value":     return playerDataService.buildBankTopValue();
            case "get_bank_coins":          return playerDataService.buildBankCoins();
            case "get_cache_index":         return playerDataService.buildCacheIndex();
            case "read_cache":              {
                String filename = args != null && args.has("file") ? args.get("file").getAsString() : "";
                return playerDataService.readCacheFile(filename);
            }
            case "get_bis_comparison":      {
                String style = args != null && args.has("style") ? args.get("style").getAsString() : "melee";
                return playerDataService.buildBisComparison(style);
            }
            case "get_seed_vault":          return playerDataService.buildSeedVault();
            case "get_farming_patches":    return playerDataService.buildFarmingPatches();
            case "get_farm_run":           return playerDataService.buildFarmRun();
            case "export_inventory_setup":
            {
                Map<String, Object> equip = args.has("equipment") && args.get("equipment").isJsonObject()
                    ? jsonToMap(args.getAsJsonObject("equipment")) : new LinkedHashMap<>();
                java.util.List<Object> invList = new java.util.ArrayList<>();
                if (args.has("inventory") && args.get("inventory").isJsonArray())
                    for (JsonElement e : args.getAsJsonArray("inventory"))
                        invList.add(e.isJsonObject() ? jsonToMap(e.getAsJsonObject()) : (Object) e.getAsInt());
                return interopService.exportInventorySetup(strArg(args, "name", null), equip, invList, strArg(args, "notes", null));
            }
            case "get_drop_table":          {
                String npcName = args != null && args.has("name") ? args.get("name").getAsString() : "";
                return playerDataService.buildDropTable(npcName);
            }
            case "get_equipment_stats":    return playerDataService.buildEquipmentStats();
            case "get_npc_info":             {
                String npcName = args != null && args.has("name") ? args.get("name").getAsString() : "";
                return playerDataService.buildNpcInfo(npcName);
            }
            case "get_combat_context":     return playerDataService.buildCombatContext();
            case "get_boss_kc":             return playerDataService.buildBossKc();
            // get_price_trends / get_item_prices are handled off-thread in dispatchNetworkTool
            // get_flip_suggestions is market-wide + client-free -> handled in dispatchNetworkTool
            case "get_money_making_context":return playerDataService.buildMoneyMakingContext();
            case "get_installed_plugins": return playerDataService.buildInstalledPlugins();
            case "get_ge_offers":          { Map<String,Object> ge = new LinkedHashMap<>(); ge.put("offers", playerDataService.buildGeOffers()); return ge; }
            case "get_session_summary":    return playerDataService.buildSessionSummary();
            default: Map<String,Object> err = new LinkedHashMap<>(); err.put("error", "Unknown tool: " + toolName); return err;
        }
    }

    /** Run a supplier on the client thread, blocking (with the standard 5s budget) for its result. */
    private <T> T onClientThread(java.util.function.Supplier<T> supplier) throws Exception
    {
        CompletableFuture<T> future = new CompletableFuture<>();
        clientThread.invokeLater(() ->
        {
            try   { future.complete(supplier.get()); }
            catch (Exception e) { future.completeExceptionally(e); }
        });
        return future.get(5, TimeUnit.SECONDS);
    }

    private Map<String, Object> dispatchHybridTool(String toolName, JsonObject args) throws Exception
    {
        switch (toolName)
        {
            case "get_equipment_stats":
            {
                PlayerDataService.EquipSnapshot snap = onClientThread(playerDataService::snapshotEquipmentStats);
                return playerDataService.buildEquipmentStatsOffThread(snap);
            }
            case "get_bis_comparison":
            {
                String style = strArg(args, "style", "melee");
                PlayerDataService.BisSnapshot snap = onClientThread(() -> playerDataService.snapshotBisComparison(style));
                return playerDataService.buildBisComparisonOffThread(snap);
            }
            default:
                Map<String, Object> err = new LinkedHashMap<>(); err.put("error", "Unknown hybrid tool: " + toolName); return err;
        }
    }

    /** Username from the 'username' arg, else the logged-in RSN (resolved on the client thread). */
    private String resolveUsername(JsonObject args)
    {
        String u = strArg(args, "username", null);
        if (u != null && !u.isBlank()) return u;
        try { return onClientThread(playerDataService::currentUsername); }
        catch (Exception e) { return null; }
    }

    private Map<String, Object> dispatchNetworkTool(String toolName, JsonObject args)
    {
        switch (toolName)
        {
            case "wiki_list_buckets":
                return wikiBucketService.listBuckets();
            case "wiki_bucket_schema":
                return wikiBucketService.bucketSchema(strArg(args, "bucket", null));
            case "wiki_bucket_query":
            {
                List<String> select = new ArrayList<>();
                if (args.has("select") && args.get("select").isJsonArray())
                    for (JsonElement e : args.getAsJsonArray("select")) select.add(e.getAsString());
                Map<String, String> where = new LinkedHashMap<>();
                if (args.has("where") && args.get("where").isJsonObject())
                    for (Map.Entry<String, JsonElement> en : args.getAsJsonObject("where").entrySet())
                        where.put(en.getKey(), en.getValue().getAsString());
                Integer limit = args.has("limit") && args.get("limit").isJsonPrimitive() ? args.get("limit").getAsInt() : null;
                return wikiBucketService.bucketQuery(
                    strArg(args, "bucket", null), select, where, limit,
                    strArg(args, "order", null), strArg(args, "raw", null));
            }
            case "get_combat_achievements":
                return wikiBucketService.combatAchievements(strArg(args, "tier", null), strArg(args, "monster", null));
            case "get_drop_table":
                return playerDataService.buildDropTable(strArg(args, "name", ""));
            case "get_npc_info":
                return playerDataService.buildNpcInfo(strArg(args, "name", ""));
            case "get_flip_suggestions":
            {
                long capital = args.has("capital") && args.get("capital").isJsonPrimitive() ? args.get("capital").getAsLong() : 0;
                int minVol   = args.has("min_volume") && args.get("min_volume").isJsonPrimitive() ? args.get("min_volume").getAsInt() : 0;
                int minMrg   = args.has("min_margin") && args.get("min_margin").isJsonPrimitive() ? args.get("min_margin").getAsInt() : 0;
                int top      = args.has("max_results") && args.get("max_results").isJsonPrimitive() ? args.get("max_results").getAsInt() : 0;
                return playerDataService.buildFlipSuggestions(capital, minVol, minMrg, top);
            }
            case "get_item_prices":
                return playerDataService.buildItemPrices(intListArg(args, "item_ids"));
            case "get_price_trends":
                return playerDataService.buildPriceTrends(intListArg(args, "item_ids"));
            case "wiki_search":
            {
                Integer lim = args.has("limit") && args.get("limit").isJsonPrimitive() ? args.get("limit").getAsInt() : null;
                return wikiBucketService.wikiSearch(strArg(args, "query", null), lim);
            }
            case "wiki_get_page":
            {
                Integer mc = args.has("max_chars") && args.get("max_chars").isJsonPrimitive() ? args.get("max_chars").getAsInt() : null;
                return wikiBucketService.wikiGetPage(strArg(args, "title", null), mc);
            }
            case "get_tcg_unlocks":
                return interopService.getTcgUnlocks();
            case "get_inventory_setups":
                return interopService.getInventorySetups();
            case "view_inventory_setup":
            {
                boolean clear = args.has("clear") && args.get("clear").isJsonPrimitive() && args.get("clear").getAsBoolean();
                return interopService.viewInventorySetup(strArg(args, "name", null), clear);
            }
            case "get_wom_profile":
                return wiseOldManService.getProfile(resolveUsername(args));
            case "get_wom_gains":
                return wiseOldManService.getGains(resolveUsername(args), strArg(args, "period", null));
            case "path_to":
            {
                boolean clear = args.has("clear") && args.get("clear").isJsonPrimitive() && args.get("clear").getAsBoolean();
                Integer x = args.has("x") && args.get("x").isJsonPrimitive() ? args.get("x").getAsInt() : null;
                Integer y = args.has("y") && args.get("y").isJsonPrimitive() ? args.get("y").getAsInt() : null;
                Integer plane = args.has("plane") && args.get("plane").isJsonPrimitive() ? args.get("plane").getAsInt() : null;
                String name = strArg(args, "name", null);
                if (!clear && (x == null || y == null) && name != null)
                {
                    int[] c = destinationService.resolve(name);
                    if (c == null) { Map<String,Object> e = new LinkedHashMap<>(); e.put("error", "Unknown destination '" + name + "'. Use get_destinations for names, or pass x/y."); return e; }
                    x = c[0]; y = c[1]; plane = c[2];
                }
                return interopService.pathTo(x, y, plane, clear);
            }
            case "get_destinations":
                return destinationService.list(strArg(args, "category", null));
            default:
                Map<String, Object> err = new LinkedHashMap<>(); err.put("error", "Unknown network tool: " + toolName); return err;
        }
    }

    private String strArg(JsonObject args, String key, String def)
    {
        return (args != null && args.has(key) && !args.get(key).isJsonNull()) ? args.get(key).getAsString() : def;
    }

    /** Parse an int-list argument tolerantly: a JSON array, a single number, or a stringified array / CSV. */
    private java.util.List<Integer> intListArg(JsonObject args, String key)
    {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return out;
        JsonElement el = args.get(key);
        try
        {
            if (el.isJsonArray())
            {
                for (JsonElement e : el.getAsJsonArray())
                    try { out.add(e.getAsInt()); } catch (Exception ignored) {}
            }
            else if (el.isJsonPrimitive())
            {
                String s = el.getAsString().trim();
                if (s.startsWith("["))
                    for (JsonElement e : gson.fromJson(s, com.google.gson.JsonArray.class))
                        try { out.add(e.getAsInt()); } catch (Exception ignored) {}
                else
                    for (String part : s.split("[,\\s]+"))
                        try { if (!part.isEmpty()) out.add(Integer.parseInt(part)); } catch (Exception ignored) {}
            }
        }
        catch (Exception ignored) {}
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonToMap(JsonObject args)
    {
        if (args == null) return new LinkedHashMap<>();
        Map<String, Object> m = gson.fromJson(args, Map.class);
        return m != null ? m : new LinkedHashMap<>();
    }

    private Map<String, Object> privacyError(String type)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", "Access to '" + type + "' is disabled in the OSRS MCP plugin settings.");
        return m;
    }

    private JsonObject strProp(String description)
    {
        JsonObject p = new JsonObject(); p.addProperty("type", "string"); p.addProperty("description", description); return p;
    }

    private JsonObject numProp(String description)
    {
        JsonObject p = new JsonObject(); p.addProperty("type", "integer"); p.addProperty("description", description); return p;
    }

    private JsonObject buildToolWithSchema(String name, String description, JsonObject properties, String[] required)
    {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        if (required != null && required.length > 0)
        {
            JsonArray req = new JsonArray();
            for (String r : required) req.add(r);
            schema.add("required", req);
        }
        tool.add("inputSchema", schema);
        return tool;
    }

    private JsonObject buildTool(String name, String description)
    {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        JsonObject schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", new JsonObject());
        tool.add("inputSchema", schema);
        return tool;
    }
}
