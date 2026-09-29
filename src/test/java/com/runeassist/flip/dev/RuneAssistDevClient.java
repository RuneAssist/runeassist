package com.runeassist.flip.dev;

import com.runeassist.flip.controller.RuneAssistPlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Starts a real RuneLite client with this checkout of the plugin loaded, the same
 * way the Plugin Hub template does. Run it with ./gradlew runClient and look at
 * the panel before any Hub submission (docs/pre-hub-checklist.md).
 */
public final class RuneAssistDevClient {
    private RuneAssistDevClient() {
    }

    public static void main(String[] args) throws Exception {
        ExternalPluginManager.loadBuiltin(RuneAssistPlugin.class);
        RuneLite.main(args);
    }
}
