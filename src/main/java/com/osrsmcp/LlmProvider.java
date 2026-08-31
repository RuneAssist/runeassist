package com.osrsmcp;

import com.google.gson.JsonObject;

/**
 * Provider-agnostic LLM client. All callers share one neutral vocabulary for
 * messages, tool specs, tool calls and replies, regardless of the wire format
 * of the underlying provider (Anthropic Messages API, OpenAI/DeepSeek Chat
 * Completions API).
 */
public interface LlmProvider
{
    /** "anthropic" | "openai" | "deepseek" */
    String id();

    /**
     * Send a single non-streaming completion request.
     *
     * @param system  the system prompt
     * @param history the running conversation
     * @param tools   tool specs to advertise (may be empty)
     * @return one assistant turn
     */
    Reply complete(String system, java.util.List<Msg> history,
                   java.util.List<ToolSpec> tools) throws Exception;

    /** Roles in the neutral model. */
    enum Role { USER, ASSISTANT, TOOL }

    /**
     * One message. EXACTLY one of {@link #text}, {@link #toolCalls} or
     * {@link #toolResult} is meaningful:
     * <ul>
     *   <li>USER      with text</li>
     *   <li>ASSISTANT with text and/or toolCalls</li>
     *   <li>TOOL      with toolResult (the output of a tool the assistant called)</li>
     * </ul>
     */
    class Msg
    {
        public Role role;
        public String text;                        // nullable
        public java.util.List<ToolCall> toolCalls; // nullable/empty
        public ToolResult toolResult;              // nullable

        public static Msg user(String text)
        {
            Msg m = new Msg();
            m.role = Role.USER;
            m.text = text;
            return m;
        }

        public static Msg assistant(String text)
        {
            Msg m = new Msg();
            m.role = Role.ASSISTANT;
            m.text = text;
            return m;
        }

        public static Msg assistantToolCalls(java.util.List<ToolCall> toolCalls)
        {
            Msg m = new Msg();
            m.role = Role.ASSISTANT;
            m.toolCalls = toolCalls;
            return m;
        }

        public static Msg tool(ToolResult toolResult)
        {
            Msg m = new Msg();
            m.role = Role.TOOL;
            m.toolResult = toolResult;
            return m;
        }
    }

    /** A tool to advertise to the model. */
    class ToolSpec
    {
        public String name;
        public String description;
        public JsonObject inputSchema; // JSON Schema object
    }

    /** A tool call the model made. */
    class ToolCall
    {
        public String id;               // provider-issued call id
        public String name;
        public JsonObject arguments;    // parsed object
    }

    /** The output of a tool the assistant called. */
    class ToolResult
    {
        public String toolCallId; // must echo the ToolCall.id
        public String content;    // the tool's output as a JSON string
    }

    /** One assistant turn. */
    class Reply
    {
        public String text;                        // assistant text (may be null/empty)
        public java.util.List<ToolCall> toolCalls; // empty if none
        public String stopReason;                  // provider's stop/finish reason
        public int inputTokens;
        public int outputTokens;
    }
}
