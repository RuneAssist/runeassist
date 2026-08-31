package com.osrsmcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UI-agnostic conversation/agent loop. It ties together the {@link ToolRegistry}
 * (tool catalogue + dispatch) and an {@link LlmProvider} (LLM transport) to run a
 * single multi-step turn.
 *
 * <p>Threading: user messages are processed serially on a dedicated daemon worker
 * thread ("osrs-companion-agent"), never on the caller's thread, and never on the
 * RuneLite client thread or Swing EDT. {@link ToolRegistry#callTool} already hops
 * to the client thread internally where required, so this class calls it directly
 * from the worker and adds no extra threading of its own.
 *
 * <p>The {@link Listener} callbacks all fire on the worker thread. A UI
 * implementation MUST marshal them onto the Swing EDT itself (e.g. via
 * {@code SwingUtilities.invokeLater}); this class deliberately has NO Swing or
 * RuneLite client references so it stays headless and independently testable.
 */
@Slf4j
@Singleton
public class CompanionAgent
{
    @Inject private LlmProviderFactory providerFactory;
    @Inject private ToolRegistry toolRegistry;
    @Inject private Gson gson;

    private final List<LlmProvider.Msg> history = new ArrayList<>();

    /**
     * A single-thread executor so user messages are processed serially and never on
     * the caller's thread.
     */
    private final ExecutorService worker =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "osrs-companion-agent");
            t.setDaemon(true);
            return t;
        });

    /** Hard cap so a misbehaving model can't spin forever. */
    private static final int MAX_TOOL_ROUNDS = 8;

    /**
     * Callbacks for the UI. All methods fire on the worker thread; the UI
     * implementation is responsible for marshalling them onto the Swing EDT.
     */
    public interface Listener
    {
        /** Full assistant text for one turn (non-streaming). */
        void onAssistantText(String text);
        /** About to run a tool. */
        void onToolCall(String toolName);
        /** Tool finished. */
        void onToolResult(String toolName);
        /** Turn done, no more tool calls. */
        void onComplete(int inputTokens, int outputTokens);
        /** Fatal error this turn. */
        void onError(String message);
    }

    /** Returns immediately; the run happens on the worker thread. */
    public void sendUserMessage(String text, Listener listener)
    {
        worker.submit(() -> runTurn(text, listener));
    }

    /** "New chat". */
    public void reset()
    {
        synchronized (history) { history.clear(); }
    }

    public int historySize()
    {
        synchronized (history) { return history.size(); }
    }

    /** Called from plugin shutDown(). */
    public void shutdown()
    {
        worker.shutdownNow();
    }

    private void runTurn(String text, Listener listener)
    {
        try
        {
            LlmProvider provider = providerFactory.create(); // per-turn: picks up config changes
            List<LlmProvider.ToolSpec> tools = buildToolSpecs();
            String system = toolRegistry.getInstructions();
            synchronized (history) { history.add(LlmProvider.Msg.user(text)); }

            int rounds = 0;
            while (true)
            {
                // Snapshot under the lock, then make the (multi-second) network call
                // OUTSIDE it so reset()/historySize() from the EDT never block on I/O.
                List<LlmProvider.Msg> snapshot;
                synchronized (history) { snapshot = new ArrayList<>(history); }
                LlmProvider.Reply reply = provider.complete(system, snapshot, tools);
                // record the assistant turn (text + any tool calls) into history
                LlmProvider.Msg assistant = new LlmProvider.Msg();
                assistant.role = LlmProvider.Role.ASSISTANT;
                assistant.text = reply.text;
                assistant.toolCalls = reply.toolCalls;
                synchronized (history) { history.add(assistant); }

                if (reply.text != null && !reply.text.isEmpty())
                {
                    listener.onAssistantText(reply.text);
                }

                if (reply.toolCalls == null || reply.toolCalls.isEmpty())
                {
                    listener.onComplete(reply.inputTokens, reply.outputTokens);
                    return;
                }
                if (++rounds > MAX_TOOL_ROUNDS)
                {
                    listener.onError("Stopped: exceeded " + MAX_TOOL_ROUNDS + " tool rounds.");
                    return;
                }
                for (LlmProvider.ToolCall c : reply.toolCalls)
                {
                    listener.onToolCall(c.name);
                    String resultJson = runToolSafely(c.name, c.arguments);
                    LlmProvider.ToolResult tr = new LlmProvider.ToolResult();
                    tr.toolCallId = c.id;
                    tr.content = resultJson;
                    synchronized (history) { history.add(LlmProvider.Msg.tool(tr)); }
                    listener.onToolResult(c.name);
                }
                // loop: call the model again with the tool results appended
            }
        }
        catch (Exception e)
        {
            log.warn("OSRS MCP companion agent error", e);
            listener.onError(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    /**
     * Never throw out of a tool call — feed the error back to the model as JSON so it
     * can recover, rather than aborting the whole turn.
     */
    private String runToolSafely(String name, JsonObject args)
    {
        try
        {
            Map<String, Object> out = toolRegistry.callTool(name, args != null ? args : new JsonObject());
            return gson.toJson(out);
        }
        catch (Exception e)
        {
            JsonObject err = new JsonObject();
            err.addProperty("error", "tool '" + name + "' failed: " +
                (e.getMessage() != null ? e.getMessage() : e.toString()));
            return gson.toJson(err);
        }
    }

    /** Parse ToolRegistry.getToolsListResult() into provider ToolSpecs. */
    private List<LlmProvider.ToolSpec> buildToolSpecs()
    {
        List<LlmProvider.ToolSpec> specs = new ArrayList<>();
        JsonObject list = toolRegistry.getToolsListResult();
        if (list.has("tools") && list.get("tools").isJsonArray())
        {
            for (com.google.gson.JsonElement el : list.getAsJsonArray("tools"))
            {
                JsonObject t = el.getAsJsonObject();
                LlmProvider.ToolSpec s = new LlmProvider.ToolSpec();
                s.name = t.get("name").getAsString();
                s.description = t.has("description") ? t.get("description").getAsString() : "";
                s.inputSchema = t.has("inputSchema") && t.get("inputSchema").isJsonObject()
                    ? t.getAsJsonObject("inputSchema") : new JsonObject();
                specs.add(s);
            }
        }
        return specs;
    }
}
