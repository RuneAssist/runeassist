package com.osrsmcp;

import com.google.gson.Gson;
import okhttp3.OkHttpClient;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Builds the correct {@link LlmProvider} from the configured values.
 * Wired by Guice via {@link javax.inject.Singleton} + {@link Inject}.
 */
@Singleton
public class LlmProviderFactory
{
    @Inject private OkHttpClient http;
    @Inject private Gson gson;
    @Inject private OsrsMcpConfig config;

    public LlmProvider create()
    {
        switch (config.llmProvider())
        {
            case ANTHROPIC:
                return new AnthropicProvider(http, gson, config.anthropicKey(),
                    blankToDefault(config.llmModel(), "claude-sonnet-5"));
            case OPENAI:
                return new OpenAiProvider(http, gson, config.openAiKey(),
                    "https://api.openai.com/v1",
                    blankToDefault(config.llmModel(), "gpt-4o"), "openai");
            case DEEPSEEK:
                return new OpenAiProvider(http, gson, config.deepSeekKey(),
                    "https://api.deepseek.com/v1",
                    blankToDefault(config.llmModel(), "deepseek-chat"), "deepseek");
            case HOSTED:
                // Not functional until the hosted backend exists; present so the
                // paid tier is a one-line switch. If the hosted API later diverges
                // from OpenAI's shape, swap in a dedicated HostedProvider class here.
                return new OpenAiProvider(http, gson, config.hostedToken(),
                    config.hostedUrl(),                                   // e.g. https://api.yourservice.com/v1
                    blankToDefault(config.llmModel(), "osrs-companion"),  // server decides the real model
                    "hosted");
        }
        throw new IllegalStateException("Unknown provider");
    }

    private static String blankToDefault(String value, String def)
    {
        return (value == null || value.isBlank()) ? def : value;
    }
}
