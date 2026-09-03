package com.runeassist.flip;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;

/**
 * Compile-only surface for {@link TelemetryService}'s optional MCP-fallback
 * contribution settings. The MCP plugin itself is not shipped in this tree.
 */
@ConfigGroup("osrsmcp")
public interface OsrsMcpConfig extends Config
{
    default boolean shareTelemetry() { return false; }

    default String telemetryEndpoint() { return ""; }

    default String telemetryToken() { return ""; }
}
