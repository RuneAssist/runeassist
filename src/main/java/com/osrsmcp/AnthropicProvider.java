package com.osrsmcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;

/**
 * Anthropic Messages API client ({@code https://api.anthropic.com/v1/messages}).
 * Non-streaming only.
 */
public class AnthropicProvider implements LlmProvider
{
    private static final String URL = "https://api.anthropic.com/v1/messages";
    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient http;
    private final Gson gson;
    private final String apiKey;
    private final String model;

    public AnthropicProvider(OkHttpClient http, Gson gson, String apiKey, String model)
    {
        this.http = http;
        this.gson = gson;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String id()
    {
        return "anthropic";
    }

    @Override
    public Reply complete(String system, java.util.List<Msg> history, java.util.List<ToolSpec> tools) throws Exception
    {
        JsonObject req = new JsonObject();
        req.addProperty("model", model);
        req.addProperty("max_tokens", 4096);
        if (system != null && !system.isEmpty())
        {
            req.addProperty("system", system);
        }

        JsonArray messages = new JsonArray();
        if (history != null)
        {
            for (Msg m : history)
            {
                messages.add(toAnthropicMessage(m));
            }
        }
        req.add("messages", messages);

        if (tools != null && !tools.isEmpty())
        {
            JsonArray toolsArr = new JsonArray();
            for (ToolSpec t : tools)
            {
                JsonObject tool = new JsonObject();
                tool.addProperty("name", t.name);
                tool.addProperty("description", t.description);
                tool.add("input_schema", t.inputSchema);
                toolsArr.add(tool);
            }
            req.add("tools", toolsArr);
        }

        RequestBody body = RequestBody.create(JSON, gson.toJson(req));
        Request request = new Request.Builder()
            .url(URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body)
            .build();

        try (Response resp = http.newCall(request).execute())
        {
            String respBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful())
            {
                throw new IOException("HTTP " + resp.code() + ": " + respBody);
            }
            return parse(gson.fromJson(respBody, JsonObject.class));
        }
    }

    private JsonObject toAnthropicMessage(Msg m)
    {
        JsonObject json = new JsonObject();
        JsonArray content = new JsonArray();
        switch (m.role)
        {
            case USER:
                json.addProperty("role", "user");
                JsonObject userText = new JsonObject();
                userText.addProperty("type", "text");
                userText.addProperty("text", m.text != null ? m.text : "");
                content.add(userText);
                break;
            case ASSISTANT:
                json.addProperty("role", "assistant");
                if (m.text != null && !m.text.isEmpty())
                {
                    JsonObject textBlock = new JsonObject();
                    textBlock.addProperty("type", "text");
                    textBlock.addProperty("text", m.text);
                    content.add(textBlock);
                }
                if (m.toolCalls != null)
                {
                    for (ToolCall c : m.toolCalls)
                    {
                        JsonObject use = new JsonObject();
                        use.addProperty("type", "tool_use");
                        use.addProperty("id", c.id);
                        use.addProperty("name", c.name);
                        use.add("input", c.arguments != null ? c.arguments : new JsonObject());
                        content.add(use);
                    }
                }
                break;
            case TOOL:
                json.addProperty("role", "user");
                JsonObject result = new JsonObject();
                result.addProperty("type", "tool_result");
                result.addProperty("tool_use_id", m.toolResult.toolCallId);
                result.addProperty("content", m.toolResult.content);
                content.add(result);
                break;
        }
        json.add("content", content);
        return json;
    }

    private Reply parse(JsonObject obj)
    {
        Reply r = new Reply();
        r.toolCalls = new java.util.ArrayList<>();
        StringBuilder text = new StringBuilder();
        JsonArray content = obj.has("content") ? obj.getAsJsonArray("content") : new JsonArray();
        for (JsonElement e : content)
        {
            JsonObject item = e.getAsJsonObject();
            String type = item.has("type") ? item.get("type").getAsString() : "";
            if ("text".equals(type))
            {
                if (item.has("text") && !item.get("text").isJsonNull())
                {
                    text.append(item.get("text").getAsString());
                }
            }
            else if ("tool_use".equals(type))
            {
                ToolCall call = new ToolCall();
                call.id = item.has("id") ? item.get("id").getAsString() : "";
                call.name = item.has("name") ? item.get("name").getAsString() : "";
                call.arguments = item.has("input") && item.get("input").isJsonObject()
                    ? item.getAsJsonObject("input") : new JsonObject();
                r.toolCalls.add(call);
            }
        }
        r.text = text.toString();
        if (obj.has("stop_reason") && !obj.get("stop_reason").isJsonNull())
        {
            r.stopReason = obj.get("stop_reason").getAsString();
        }
        if (obj.has("usage") && obj.get("usage").isJsonObject())
        {
            JsonObject usage = obj.getAsJsonObject("usage");
            if (usage.has("input_tokens")) r.inputTokens = usage.get("input_tokens").getAsInt();
            if (usage.has("output_tokens")) r.outputTokens = usage.get("output_tokens").getAsInt();
        }
        return r;
    }
}
