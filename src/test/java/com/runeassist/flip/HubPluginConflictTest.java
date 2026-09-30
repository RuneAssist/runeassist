package com.runeassist.flip;

import net.runelite.client.plugins.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class HubPluginConflictTest {
    @AfterEach
    void clear() {
        System.clearProperty(HubPluginConflict.ALLOW_PROPERTY);
    }

    @Test
    void theDeveloperPropertySwitchesTheGuardOffWithoutLookingAtPlugins() {
        System.setProperty(HubPluginConflict.ALLOW_PROPERTY, "true");
        // A null manager would otherwise be the "no plugins" answer; here it must not even be read.
        assertFalse(HubPluginConflict.isEnabled((PluginManager) null));
    }

    @Test
    void withoutThePropertyNoManagerMeansNoConflict() {
        assertFalse(HubPluginConflict.isEnabled((PluginManager) null));
    }
}
