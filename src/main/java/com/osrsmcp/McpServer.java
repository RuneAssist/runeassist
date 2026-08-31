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

    // Domain guidance handed to the AI client on connect is provided by
    // ToolRegistry (it moved there with the tool catalogue).

    @Inject private PlayerDataService playerDataService;
    @Inject private WikiBucketService wikiBucketService;
    @Inject private QuestPlanService questPlanService;
    @Inject private InteropService interopService;
    @Inject private WiseOldManService wiseOldManService;
    @Inject private DestinationService destinationService;
    @Inject private ClientThread clientThread;
    @Inject private OsrsMcpConfig config;

    // Tool routing sets (NETWORK_TOOLS / HYBRID_TOOLS) live in ToolRegistry.

    private HttpServer server;
    @Inject private Gson gson;
    @Inject private ToolRegistry toolRegistry;

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
            case "tools/list":              sendJsonRpcResult(exchange, id, toolRegistry.getToolsListResult()); break;
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
        try
        {
            toolResult = toolRegistry.callTool(toolName, arguments);
        }
        catch (java.util.concurrent.TimeoutException e) { sendJsonRpcError(exchange, id, -32603, "Timed out waiting for game thread"); return; }
        catch (Exception e) { sendJsonRpcError(exchange, id, -32603, "Internal error: " + e.getMessage()); return; }

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
        result.addProperty("instructions", toolRegistry.getInstructions());
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
