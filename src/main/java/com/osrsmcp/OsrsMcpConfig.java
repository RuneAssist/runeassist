package com.osrsmcp;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("osrsmcp")
public interface OsrsMcpConfig extends Config
{
    @ConfigSection(name = "Connection", description = "MCP server connection settings", position = 0)
    String connectionSection = "connection";

    @ConfigItem(keyName = "port", name = "Port",
        description = "Port the local MCP server listens on. Click Restart server after changing.",
        section = connectionSection, position = 0)
    default int port() { return 8282; }

    @ConfigItem(keyName = "connectionMode", name = "Connection mode",
        description = "<html><b>Local</b> — same machine only (default)<br>" +
                      "<b>LAN</b> — devices on the same subnet<br>" +
                      "<b>Tailscale</b> — any device with Tailscale installed (reliable, free, recommended for cross-network)<br>" +
                      "</html>",
        section = connectionSection, position = 1)
    default ConnectionMode connectionMode() { return ConnectionMode.LOCAL; }

    @ConfigItem(keyName = "authToken", name = "Auth Token",
        description = "Optional Bearer token for extra security. Recommended with LAN or Tailscale.",
        section = connectionSection, position = 2, secret = true)
    default String authToken() { return ""; }

    // ── PRIVACY ───────────────────────────────────────────────────────────────

    @ConfigSection(name = "Privacy", description = "Control what data the AI can access", position = 1)
    String privacySection = "privacy";

    @ConfigItem(keyName = "shareStats", name = "Share skill levels & XP",
        section = privacySection, position = 0,
        description = "Allow the AI to read your skill levels and experience")
    default boolean shareStats() { return true; }

    @ConfigItem(keyName = "shareEquipment", name = "Share equipped gear",
        section = privacySection, position = 1,
        description = "Allow the AI to see what items you have equipped")
    default boolean shareEquipment() { return true; }

    @ConfigItem(keyName = "shareInventory", name = "Share inventory",
        section = privacySection, position = 2,
        description = "Allow the AI to see your inventory contents")
    default boolean shareInventory() { return true; }

    @ConfigItem(keyName = "shareLocation", name = "Share location",
        section = privacySection, position = 3,
        description = "Allow the AI to see your current in-game location")
    default boolean shareLocation() { return true; }

    @ConfigItem(keyName = "shareUsername", name = "Share username",
        section = privacySection, position = 4,
        description = "Include your RSN in responses. Disable for privacy.")
    default boolean shareUsername() { return true; }

    @ConfigItem(keyName = "shareTelemetry", name = "Contribute anonymous data (opt-in)",
        section = privacySection, position = 5,
        description = "Off by default. When on, RuneAssist logs anonymised gameplay data " +
            "(XP gains, account snapshots, GE activity) to local files on your PC to improve " +
            "future suggestions. Your RSN is hashed; nothing is uploaded.")
    default boolean shareTelemetry() { return false; }

    // ── AI CHAT ──────────────────────────────────────────────────────────────

    @ConfigSection(name = "AI Chat", description = "LLM provider settings for in-game chat", position = 2)
    String aiChatSection = "aiChat";

    @ConfigItem(keyName = "llmProvider", name = "AI provider",
        description = "Which LLM provider to use for AI chat.",
        section = aiChatSection, position = 0)
    default LlmProviderType llmProvider() { return LlmProviderType.ANTHROPIC; }

    @ConfigItem(keyName = "anthropicKey", name = "Anthropic API key",
        description = "API key for Anthropic.",
        section = aiChatSection, position = 1, secret = true)
    default String anthropicKey() { return ""; }

    @ConfigItem(keyName = "openAiKey", name = "OpenAI API key",
        description = "API key for OpenAI.",
        section = aiChatSection, position = 2, secret = true)
    default String openAiKey() { return ""; }

    @ConfigItem(keyName = "deepSeekKey", name = "DeepSeek API key",
        description = "API key for DeepSeek.",
        section = aiChatSection, position = 3, secret = true)
    default String deepSeekKey() { return ""; }

    @ConfigItem(keyName = "llmModel", name = "Model override",
        description = "Leave blank to use the provider's default model.",
        section = aiChatSection, position = 4)
    default String llmModel() { return ""; }

    @ConfigItem(keyName = "hostedUrl", name = "Hosted service URL",
        description = "Paid hosted tier endpoint (OpenAI-compatible). Leave blank unless subscribed.",
        section = aiChatSection, position = 5)
    default String hostedUrl() { return ""; }

    @ConfigItem(keyName = "hostedToken", name = "Hosted account token",
        description = "Sign-in token for the hosted tier. Not a raw API key.",
        section = aiChatSection, position = 6, secret = true)
    default String hostedToken() { return ""; }
}
