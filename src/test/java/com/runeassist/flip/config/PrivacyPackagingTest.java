package com.runeassist.flip.config;

import com.runeassist.flip.controller.FlippingCopilotPlugin;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.plugins.PluginDescriptor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PrivacyPackagingTest {

    @Test
    public void shareTelemetryAndCloudSyncDefaultOff() {
        FlippingCopilotConfig cfg = new FlippingCopilotConfig() {
            @Override
            public String webhook() {
                return null;
            }
        };
        assertFalse(cfg.shareTelemetry());
        assertFalse(cfg.cloudSync());
    }

    @Test
    public void privacyTogglesHaveHubWarnings() throws Exception {
        ConfigItem telemetry = FlippingCopilotConfig.class.getMethod("shareTelemetry").getAnnotation(ConfigItem.class);
        ConfigItem cloudSync = FlippingCopilotConfig.class.getMethod("cloudSync").getAnnotation(ConfigItem.class);
        assertNotNull(telemetry);
        assertNotNull(cloudSync);
        assertTrue(telemetry.warning().toLowerCase().contains("ip address"));
        assertTrue(cloudSync.warning().toLowerCase().contains("ip address"));
        assertTrue(telemetry.warning().toLowerCase().contains("3rd-party")
                || telemetry.warning().toLowerCase().contains("3rd party"));
        assertTrue(cloudSync.warning().toLowerCase().contains("3rd-party")
                || cloudSync.warning().toLowerCase().contains("3rd party"));
    }

    @Test
    public void pluginDescriptorAndPropertiesWarnAndUseStandardBuild() throws Exception {
        PluginDescriptor descriptor = FlippingCopilotPlugin.class.getAnnotation(PluginDescriptor.class);
        assertNotNull(descriptor);
        assertFalse(descriptor.description().toLowerCase().contains("on by default"));

        Properties properties = new Properties();
        properties.load(Files.newInputStream(Paths.get("runelite-plugin.properties")));
        assertEquals("standard", properties.getProperty("build"));
        String warning = properties.getProperty("warning");
        assertNotNull(warning);
        assertTrue(warning.toLowerCase().contains("grand exchange"));
        assertTrue(warning.toLowerCase().contains("ip address"));
    }

    @Test
    public void leftoverCopilotHostIsAbsentFromSources() throws Exception {
        Path root = Paths.get("src");
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".properties"))
                    .forEach(p -> {
                        try {
                            String text = Files.readString(p, StandardCharsets.UTF_8);
                            String host = "api.flipping" + "copilot.com";
                            assertFalse(text.contains(host),
                                    "leftover host in " + p);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }
}
