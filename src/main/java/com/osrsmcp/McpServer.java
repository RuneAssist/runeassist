package com.osrsmcp;


import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;

import javax.inject.Inject;
import com.osrsmcp.ConnectionMode;
import javax.inject.Singleton;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Singleton
public class McpServer
{
    private static final String MCP_VERSION    = "2024-11-05";
    private static final String SERVER_NAME    = "osrs-mcp";
    private static final String SERVER_VERSION = "1.0.0";

    // Domain guidance handed to the AI client on connect. Steers how it should
    // reason about the data these tools expose, so advice is OSRS-aware by default.
    private static final String INSTRUCTIONS =
        "This server exposes live Old School RuneScape (OSRS) data for the connected RuneLite account. "
        + "RESPONSE STYLE (important): keep it digestible and match depth to the question. Lead with the single biggest takeaway. For 'what next', give at MOST 2 concrete next moves, each from a DIFFERENT category (skilling / combat i.e. slayer or bossing / a single quest / a diary / money-making or flipping) -- pick the 2 with the best value now, then offer the rest ('want combat, money-making or diary ideas instead?'). Be realistic about scale: never present something gated behind a huge grind (e.g. a diary needing a skill dozens of levels above the player's current) as a near-term step -- name the grind and set it aside. For any SKILLING suggestion, ALWAYS give the method, its XP/hr and the rough hours to the target (use get_training_methods + the XP gap) and where to train (path_to can draw the route). For QUESTS, suggest ONE high-value quest, not a batch. Do NOT open with a giant multi-step plan; ASK 'want the full plan?' before dumping one, and even then lead with a 2-3 line TL;DR. A few lines or a small table beats paragraphs.\n"
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
        + "\nOTHER PLUGINS / ACTIVITIES: get_inventory_setups + view_inventory_setup (gear presets; open one in-game). export_inventory_setup designs a loadout the player imports. get_tcg_unlocks (OSRS TCG: only suggest unlocked content). path_to draws a route via Shortest Path -- pass a get_destinations name or coords (guide, never auto-move). get_farm_run for herb runs (states, kit, per-patch teleport + path_to coords).\n"
        + "- Rank suggestions by leverage: unlocks that gate multiple diaries/quests, skills within a level or two of a milestone, and content the player's gear and stats already support.\n"
        + "- For gear/BiS questions use get_equipment_stats and get_bis_comparison, and note when the player is not in combat gear (stats will read low).\n"
        + "- For money-making, prefer the player's measured context (get_money_making_context, get_boss_kc) and account for GE buy limits and the 1% GE tax on flips.\n"
        + "- Diary 'requirements met' (get_diary_requirements) means the player QUALIFIES for a task, not that it is done; cross-reference get_diary_states for tier completion.\n"
        + "- Be specific and quantitative: cite levels, XP remaining, GP values and item names from the tools rather than generic advice.\n"
        + "\nWIKI KNOWLEDGE (Bucket gateway): the player's own state comes from the in-game tools above; general game knowledge comes from the wiki. Routing:\n"
        + "- Combat achievements -> get_combat_achievements (filter by tier/monster).\n"
        + "- Monster stats / weakness / max hit / slayer level -> wiki_bucket_query('infobox_monster', ...) (fields: combat_level, hitpoints, max_hit, slayer_level, elemental_weakness, *_defence_bonus, attack_style, attack_speed).\n"
        + "- Item GE buy limit / alch value / weight -> wiki_bucket_query('infobox_item', ...) (item_id, buy_limit, high_alchemy_value, value, weight, tradeable).\n"
        + "- Equipment bonuses by slot -> wiki_bucket_query('infobox_bonuses', ...) keyed by where('page_name','<item>').\n"
        + "- BiS gear -> wiki_bucket_query('recommended_equipment', ...); money-making -> 'money_making_guide'; drops -> 'dropsline'; recipes -> 'recipe'.\n"
        + "- For anything else, discover with wiki_list_buckets then wiki_bucket_schema before querying.\n"
        + "- Narrative content (strategy, mechanics, walkthroughs) is NOT in the buckets -- use wiki_search then wiki_get_page for readable prose.\n"
        + "- Query hygiene: always pass 'select' and a small 'limit'; filter with 'where'; some buckets hold detail in a json/*_json field to parse. Never query the wiki for the player's character data.";

    @Inject private PlayerDataService playerDataService;
    @Inject private WikiBucketService wikiBucketService;
    @Inject private QuestPlanService questPlanService;
    @Inject private InteropService interopService;
    @Inject private WiseOldManService wiseOldManService;
    @Inject private DestinationService destinationService;
    @Inject private ClientThread clientThread;
    @Inject private OsrsMcpConfig config;

    // Tools that hit the wiki over HTTP and read no game state. Routed OFF the
    // client thread so a slow fetch never freezes the game (and isn't bound by
    // the 5s client-thread budget).
    private static final Set<String> NETWORK_TOOLS = new HashSet<>(Arrays.asList(
        "wiki_list_buckets", "wiki_bucket_schema", "wiki_bucket_query", "get_combat_achievements",
        "wiki_search", "wiki_get_page",
        // pure wiki-scrape tools: no live game state, so a slow page fetch must not
        // hold the client thread (they only take a name argument and hit HTTP).
        "get_drop_table", "get_npc_info",
        // inter-plugin messaging (post on client thread internally, then await a reply)
        "get_tcg_unlocks", "path_to", "get_inventory_setups", "view_inventory_setup", "get_destinations",
        // Wise Old Man web API (progress history / gains)
        "get_wom_profile", "get_wom_gains"));

    // Hybrid tools: read live game state, THEN do per-item HTTP. Handled in two
    // phases -- a quick client-thread snapshot, then the fetch off the client thread.
    private static final Set<String> HYBRID_TOOLS = new HashSet<>(Arrays.asList(
        "get_equipment_stats", "get_bis_comparison"));

    private HttpServer server;
    @Inject private Gson gson;

    // Lightweight activity tracking for the plugin panel.
    private final java.util.concurrent.atomic.AtomicLong requestCount = new java.util.concurrent.atomic.AtomicLong();
    private volatile String lastToolName = null;
    private volatile long lastToolAtMs = 0;
    private final java.util.Deque<Object[]> recentCalls = new java.util.concurrent.ConcurrentLinkedDeque<>();
    public long getRequestCount()   { return requestCount.get(); }
    public String getLastToolName() { return lastToolName; }
    public long getLastToolAtMs()   { return lastToolAtMs; }
    /** Recent tool calls, newest first, as {String name, Long atMs}. */
    public java.util.List<Object[]> getRecentCalls() { return new java.util.ArrayList<>(recentCalls); }

    private void recordCall(String tool)
    {
        requestCount.incrementAndGet();
        lastToolName = tool;
        lastToolAtMs = System.currentTimeMillis();
        recentCalls.addFirst(new Object[]{ tool, lastToolAtMs });
        while (recentCalls.size() > 8) recentCalls.removeLast();
    }
    private final Set<SseClient> sseClients = ConcurrentHashMap.newKeySet();

    public void start(int port) throws IOException
    {
        server = HttpServer.create(new InetSocketAddress((config.connectionMode() == ConnectionMode.LAN || config.connectionMode() == ConnectionMode.TAILSCALE) ? "0.0.0.0" : "127.0.0.1", port), 0);
        // Bounded thread pool -- max 20 threads prevents resource exhaustion under load
        server.setExecutor(Executors.newFixedThreadPool(20));
        server.createContext("/mcp",    this::handleMcp);
        server.createContext("/health", this::handleHealth);
        server.start();
        log.info("OSRS MCP MCP server started on http://127.0.0.1:{}/mcp", port);
    }

    public void stop()
    {
        if (server != null)
        {
            sseClients.forEach(SseClient::close);
            sseClients.clear();
            server.stop(1);
            log.info("OSRS MCP MCP server stopped");
        }
    }

    private void handleMcp(HttpExchange exchange) throws IOException
    {
        addCorsHeaders(exchange);
        if ("OPTIONS".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(204, -1); return; }
        if (!isAuthorized(exchange)) { sendError(exchange, 401, "Unauthorized"); return; }
        switch (exchange.getRequestMethod())
        {
            case "POST": handlePost(exchange); break;
            case "GET":  handleSse(exchange);  break;
            default:     sendError(exchange, 405, "Method Not Allowed");
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException
    {
        addCorsHeaders(exchange);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "ok");
        status.put("server", SERVER_NAME);
        status.put("version", SERVER_VERSION);
        status.put("logged_in", playerDataService.isLoggedIn());
        sendJson(exchange, 200, status);
    }

    private void handlePost(HttpExchange exchange) throws IOException
    {
        String body;
        try (InputStream is = exchange.getRequestBody())
        {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonObject request;
        try { request = gson.fromJson(body, JsonObject.class); }
        catch (JsonSyntaxException e) { sendJsonRpcError(exchange, null, -32700, "Parse error"); return; }

        JsonElement idEl = request.get("id");
        String id = (idEl != null && !idEl.isJsonNull()) ? idEl.toString() : null;
        String method = request.has("method") ? request.get("method").getAsString() : "";

        switch (method)
        {
            case "initialize":              sendJsonRpcResult(exchange, id, buildInitializeResult()); break;
            case "notifications/initialized": exchange.sendResponseHeaders(202, -1); break;
            case "tools/list":              sendJsonRpcResult(exchange, id, buildToolsList()); break;
            case "tools/call":              handleToolCall(exchange, id, request); break;
            case "prompts/list":            sendJsonRpcResult(exchange, id, buildPromptsList()); break;
            case "prompts/get":             sendJsonRpcResult(exchange, id, buildPromptGet(request)); break;
            case "ping":                    sendJsonRpcResult(exchange, id, new JsonObject()); break;
            default: sendJsonRpcError(exchange, id, -32601, "Method not found: " + method);
        }
    }

    private void handleToolCall(HttpExchange exchange, String id, JsonObject request) throws IOException
    {
        JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
        String toolName = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject arguments = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();

        recordCall(toolName);

        Map<String, Object> toolResult;
        if (NETWORK_TOOLS.contains(toolName))
        {
            // Runs on this HTTP handler thread (pool of 20); no game state needed.
            try { toolResult = dispatchNetworkTool(toolName, arguments); }
            catch (Exception e) { sendJsonRpcError(exchange, id, -32603, "Internal error: " + e.getMessage()); return; }
        }
        else if (HYBRID_TOOLS.contains(toolName))
        {
            // Phase 1: snapshot live state on the client thread (fast, no HTTP).
            // Phase 2: fetch per-item wiki data here, off the client thread.
            try { toolResult = dispatchHybridTool(toolName, arguments); }
            catch (TimeoutException e) { sendJsonRpcError(exchange, id, -32603, "Timed out waiting for game thread"); return; }
            catch (Exception e)        { sendJsonRpcError(exchange, id, -32603, "Internal error: " + e.getMessage()); return; }
        }
        else
        {
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            clientThread.invokeLater(() ->
            {
                try   { future.complete(dispatchTool(toolName, arguments)); }
                catch (Exception e) { future.completeExceptionally(e); }
            });
            try { toolResult = future.get(5, TimeUnit.SECONDS); }
            catch (TimeoutException e) { sendJsonRpcError(exchange, id, -32603, "Timed out waiting for game thread"); return; }
            catch (Exception e)        { sendJsonRpcError(exchange, id, -32603, "Internal error: " + e.getMessage()); return; }
        }

        JsonObject result  = new JsonObject();
        JsonArray  content = new JsonArray();
        JsonObject block   = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", gson.toJson(toolResult));
        content.add(block);
        result.add("content", content);
        result.addProperty("isError", toolResult.containsKey("error"));
        sendJsonRpcResult(exchange, id, result);
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
            case "get_optimal_quest_route": return questPlanService.buildOptimalQuestRoute(jsonToMap(args));
            case "reload_planner_data": return questPlanService.reloadData();
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
            case "get_price_trends":        {
                java.util.List<Integer> ids = new java.util.ArrayList<>();
                if (args != null && args.has("item_ids"))
                    for (com.google.gson.JsonElement e : args.getAsJsonArray("item_ids"))
                        ids.add(e.getAsInt());
                return playerDataService.buildPriceTrends(ids);
            }
            case "get_item_prices":         {
                java.util.List<Integer> ids = new java.util.ArrayList<>();
                if (args != null && args.has("item_ids")) {
                    for (com.google.gson.JsonElement e : args.getAsJsonArray("item_ids"))
                        ids.add(e.getAsInt());
                }
                return playerDataService.buildItemPrices(ids);
            }
            case "get_flip_suggestions":    return playerDataService.buildFlipSuggestions();
            case "get_money_making_context":return playerDataService.buildMoneyMakingContext();
            case "get_installed_plugins": return playerDataService.buildInstalledPlugins();
            case "get_ge_offers":          { Map<String,Object> ge = new LinkedHashMap<>(); ge.put("offers", playerDataService.buildGeOffers()); return ge; }
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonToMap(JsonObject args)
    {
        if (args == null) return new LinkedHashMap<>();
        Map<String, Object> m = gson.fromJson(args, Map.class);
        return m != null ? m : new LinkedHashMap<>();
    }

    private void handleSse(HttpExchange exchange) throws IOException
    {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.getResponseHeaders().add("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        SseClient client = new SseClient(exchange.getResponseBody());
        sseClients.add(client);
        client.send("endpoint", "/mcp");
        try
        {
            while (!client.isClosed()) { Thread.sleep(15_000); client.send("ping", "{}"); }
        }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { sseClients.remove(client); client.close(); }
    }

    private JsonObject buildInitializeResult()
    {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", MCP_VERSION);
        JsonObject info = new JsonObject(); info.addProperty("name", SERVER_NAME); info.addProperty("version", SERVER_VERSION);
        result.add("serverInfo", info);
        JsonObject caps = new JsonObject(); caps.add("tools", new JsonObject()); caps.add("prompts", new JsonObject());
        result.add("capabilities", caps);
        result.addProperty("instructions", INSTRUCTIONS);
        return result;
    }

    private JsonObject buildPromptsList()
    {
        JsonObject result = new JsonObject();
        JsonArray prompts = new JsonArray();

        JsonObject analyze = new JsonObject();
        analyze.addProperty("name", "analyze_account");
        analyze.addProperty("description", "Full account review: stats, quests, diaries, gear and bank, ending with a ranked list of the most useful things to work on next.");
        prompts.add(analyze);

        JsonObject nextGoal = new JsonObject();
        nextGoal.addProperty("name", "whats_next");
        nextGoal.addProperty("description", "Quick answer to 'what should I work on next?' using next goals and diary requirements.");
        prompts.add(nextGoal);

        result.add("prompts", prompts);
        return result;
    }

    private JsonObject buildPromptGet(JsonObject request)
    {
        JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
        String name = params.has("name") ? params.get("name").getAsString() : "";

        String text;
        String description;
        switch (name)
        {
            case "whats_next":
                description = "What to work on next";
                text = "Call get_next_goals and get_diary_requirements, then tell me the 3-5 most useful things "
                     + "to work on next on this OSRS account. Be specific: name the exact skills, diary tasks and "
                     + "quests, with the levels or XP involved, and rank by leverage. Use get_quest_rewards to see "
                     + "what's startable now, project_plan to verify any 'do X then Y unlocks Z' claim exactly, and "
                     + "get_training_methods to turn XP into rough hours.";
                break;
            case "analyze_account":
            default:
                description = "Full account analysis";
                text = "Analyse my OSRS account. Call get_all first, then get_player_stats, get_quest_states, "
                     + "get_diary_states, get_diary_requirements, get_equipment_stats, get_bank_summary, "
                     + "get_next_goals and get_quest_rewards as needed (project_plan to verify anything multi-step, "
                     + "get_training_methods for XP/hr). Then keep it SHORT: a 2-3 line summary, then the 2 best next "
                     + "moves -- each from a DIFFERENT category (skilling / combat / a single quest / diary / "
                     + "money-making). One line each with concrete detail: for skilling give the method, XP/hr and "
                     + "rough hours; for a quest name ONE high-value quest. Be realistic -- don't put anything behind "
                     + "a massive grind in the shortlist. Then offer the other categories and ask which to plan in "
                     + "detail. Do NOT write a full multi-step plan yet. Respect my account type and GE limits/tax.";
                break;
        }

        JsonObject result = new JsonObject();
        result.addProperty("description", description);
        JsonArray messages = new JsonArray();
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);
        msg.add("content", content);
        messages.add(msg);
        result.add("messages", messages);
        return result;
    }

    private JsonObject buildToolsList()
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
        tools.add(buildTool("get_flip_suggestions",     "Get flip suggestions from bank items cross-referenced with live GE margins, filtered by coin budget."));
        tools.add(buildTool("get_money_making_context", "Get location, stats, coins and slayer task for money making method recommendations."));
        tools.add(buildTool("get_installed_plugins", "Get all installed RuneLite plugins (both built-in and Plugin Hub) with their enabled state. Use this to suggest relevant Plugin Hub plugins."));
        tools.add(buildTool("get_ge_offers",          "Get all active Grand Exchange offers including item, quantity, price and state."));

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

    private boolean isAuthorized(HttpExchange exchange)
    {
        String token = config.authToken();
        if (token == null || token.isBlank()) return true;
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return header != null && header.equals("Bearer " + token.trim());
    }

    private void sendJsonRpcResult(HttpExchange exchange, String id, JsonObject result) throws IOException
    {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (id != null) { try { response.addProperty("id", Integer.parseInt(id)); } catch (NumberFormatException e) { response.addProperty("id", id); } }
        response.add("result", result);
        sendJson(exchange, 200, response);
    }

    private void sendJsonRpcError(HttpExchange exchange, String id, int code, String message) throws IOException
    {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (id != null) { try { response.addProperty("id", Integer.parseInt(id)); } catch (NumberFormatException e) { response.addProperty("id", id); } }
        JsonObject error = new JsonObject(); error.addProperty("code", code); error.addProperty("message", message);
        response.add("error", error);
        sendJson(exchange, 200, response);
    }

    private void sendJson(HttpExchange exchange, int status, Object payload) throws IOException
    {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] bytes = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException
    {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private void addCorsHeaders(HttpExchange exchange)
    {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private Map<String, Object> privacyError(String type)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", "Access to '" + type + "' is disabled in the OSRS MCP plugin settings.");
        return m;
    }

    private static class SseClient
    {
        private final OutputStream out;
        private volatile boolean closed = false;
        SseClient(OutputStream out) { this.out = out; }
        void send(String event, String data)
        {
            try { out.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8)); out.flush(); }
            catch (IOException e) { closed = true; }
        }
        boolean isClosed() { return closed; }
        void close() { closed = true; try { out.close(); } catch (IOException ignored) {} }
    }
}
