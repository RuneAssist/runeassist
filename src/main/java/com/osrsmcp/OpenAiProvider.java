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
 * OpenAI Chat Completions API client. Serves BOTH OpenAI and DeepSeek — the only
 * difference is baseUrl + model + the id string. Non-streaming only.
 */
public class OpenAiProvider implements LlmProvider
{
    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient http;
    private final Gson gson;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String id;

    public OpenAiProvider(OkHttpClient http, Gson gson, String apiKey, String baseUrl, String model, String id)
    {
        this.http = http;
        this.gson = gson;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.id = id;
    }

    @Override
    public String id()
    {
        return id;
    }

    @Override
    public Reply complete(String system, java.util.List<Msg> history, java.util.List<ToolSpec> tools) throws Exception
    {
        JsonObject req = new JsonObject();
        req.addProperty("model", model);

        JsonArray messages = new JsonArray();
        if (system != null && !system.isEmpty())
        {
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", system);
            messages.add(sys);
        }
        if (history != null)
        {
            for (Msg m : history)
            {
                messages.add(toOpenAiMessage(m));
            }
        }
        req.add("messages", messages);

        if (tools != null && !tools.isEmpty())
        {
            JsonArray toolsArr = new JsonArray();
            for (ToolSpec t : tools)
            {
                JsonObject fn = new JsonObject();
                fn.addProperty("name", t.name);
                fn.addProperty("description", t.description);
                fn.add("parameters", t.inputSchema);
                JsonObject tool = new JsonObject();
                tool.addProperty("type", "function");
                tool.add("function", fn);
                toolsArr.add(tool);
            }
            req.add("tools", toolsArr);
        }

        RequestBody body = RequestBody.create(JSON, gson.toJson(req));
        Request request = new Request.Builder()
            .url(baseUrl + "/chat/completions")
            .addHeader("Authorization", "Bearer " + apiKey)
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

    private JsonObject toOpenAiMessage(Msg m)
    {
        JsonObject json = new JsonObject();
        switch (m.role)
        {
            case USER:
                json.addProperty("role", "user");
                json.addProperty("content", m.text != null ? m.text : "");
                break;
            case ASSISTANT:
                json.addProperty("role", "assistant");
                json.addProperty("content", m.text);
                if (m.toolCalls != null && !m.toolCalls.isEmpty())
                {
                    JsonArray calls = new JsonArray();
                    for (ToolCall c : m.toolCalls)
                    {
                        JsonObject call = new JsonObject();
                        call.addProperty("id", c.id);
                        call.addProperty("type", "function");
                        JsonObject fn = new JsonObject();
                        fn.addProperty("name", c.name);
                        fn.addProperty("arguments", gson.toJson(c.arguments != null ? c.arguments : new JsonObject()));
                        call.add("function", fn);
                        calls.add(call);
                    }
                    json.add("tool_calls", calls);
                }
                break;
            case TOOL:
                json.addProperty("role", "tool");
                json.addProperty("tool_call_id", m.toolResult.toolCallId);
                json.addProperty("content", m.toolResult.content);
                break;
        }
        return json;
    }

    private Reply parse(JsonObject obj)
    {
        Reply r = new Reply();
        r.toolCalls = new java.util.ArrayList<>();
        JsonArray choices = obj.has("choices") ? obj.getAsJsonArray("choices") : new JsonArray();
        if (choices.size() > 0)
        {
            JsonObject first = choices.get(0).getAsJsonObject();
            if (first.has("message") && first.get("message").isJsonObject())
            {
                JsonObject message = first.getAsJsonObject("message");
                if (message.has("content") && !message.get("content").isJsonNull())
                {
                    r.text = message.get("content").getAsString();
                }
                if (message.has("tool_calls") && message.get("tool_calls").isJsonArray())
                {
                    for (JsonElement e : message.getAsJsonArray("tool_calls"))
                    {
                        JsonObject tcObj = e.getAsJsonObject();
                        ToolCall call = new ToolCall();
                        call.id = tcObj.has("id") ? tcObj.get("id").getAsString() : "";
                        if (tcObj.has("function") && tcObj.get("function").isJsonObject())
                        {
                            JsonObject fn = tcObj.getAsJsonObject("function");
                            call.name = fn.has("name") ? fn.get("name").getAsString() : "";
                            if (fn.has("arguments") && !fn.get("arguments").isJsonNull())
                            {
                                // OpenAI/DeepSeek return function.arguments as a JSON *string*.
                                try
                                {
                                    call.arguments = gson.fromJson(fn.get("arguments").getAsString(), JsonObject.class);
                                }
                                catch (Exception ex)
                                {
                                    call.arguments = new JsonObject();
                                }
                            }
                            else
                            {
                                call.arguments = new JsonObject();
                            }
                        }
                        r.toolCalls.add(call);
                    }
                }
            }
            if (first.has("finish_reason") && !first.get("finish_reason").isJsonNull())
            {
                r.stopReason = first.get("finish_reason").getAsString();
            }
        }
        if (obj.has("usage") && obj.get("usage").isJsonObject())
        {
            JsonObject usage = obj.getAsJsonObject("usage");
            if (usage.has("prompt_tokens")) r.inputTokens = usage.get("prompt_tokens").getAsInt();
            if (usage.has("completion_tokens")) r.outputTokens = usage.get("completion_tokens").getAsInt();
        }
        return r;
    }
}
