package com.osrsmcp;

import com.google.gson.Gson;
import okhttp3.OkHttpClient;

import java.util.Collections;
import java.util.List;

/**
 * Headless verifier: proves auth + request/response parsing for all three
 * providers without any tool-calling. Reads keys from env vars and runs each
 * provider that has a key present, independently, so one bad key doesn't stop
 * the others. No RuneLite client or Swing.
 */
public class ProviderSmokeTest
{
    public static void main(String[] args)
    {
        OkHttpClient http = new OkHttpClient();
        Gson gson = new Gson();

        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        String openAiKey = System.getenv("OPENAI_API_KEY");
        String deepSeekKey = System.getenv("DEEPSEEK_API_KEY");

        if (isPresent(anthropicKey))
        {
            run(new AnthropicProvider(http, gson, anthropicKey, "claude-sonnet-4-5"));
        }
        if (isPresent(openAiKey))
        {
            run(new OpenAiProvider(http, gson, openAiKey, "https://api.openai.com/v1", "gpt-4o", "openai"));
        }
        if (isPresent(deepSeekKey))
        {
            run(new OpenAiProvider(http, gson, deepSeekKey, "https://api.deepseek.com/v1", "deepseek-chat", "deepseek"));
        }
    }

    private static boolean isPresent(String key)
    {
        return key != null && !key.isBlank();
    }

    private static void run(LlmProvider provider)
    {
        try
        {
            LlmProvider.Reply reply = provider.complete(
                "You are a calculator. Reply with only the number.",
                List.of(LlmProvider.Msg.user("What is 21 + 21?")),
                Collections.emptyList());
            String text = reply.text == null ? "" : reply.text;
            System.out.println(provider.id() + " -> text=[" + text + "]  in=" + reply.inputTokens + " out=" + reply.outputTokens);
        }
        catch (Exception e)
        {
            System.out.println(provider.id() + " FAILED: " + e.getMessage());
        }
    }
}
