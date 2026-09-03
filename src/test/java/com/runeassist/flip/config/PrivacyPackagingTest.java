package com.runeassist.flip.config;

import com.runeassist.flip.controller.RuneAssistPlugin;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.plugins.PluginDescriptor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PrivacyPackagingTest {

    @Test
    public void shareTelemetryAndCloudSyncDefaultOff() {
        RuneAssistConfig cfg = new RuneAssistConfig() {
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
        ConfigItem telemetry = RuneAssistConfig.class.getMethod("shareTelemetry").getAnnotation(ConfigItem.class);
        ConfigItem cloudSync = RuneAssistConfig.class.getMethod("cloudSync").getAnnotation(ConfigItem.class);
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
        PluginDescriptor descriptor = RuneAssistPlugin.class.getAnnotation(PluginDescriptor.class);
        assertNotNull(descriptor);
        assertFalse(descriptor.description().toLowerCase().contains("on by default"));

        Properties properties = new Properties();
        properties.load(Files.newInputStream(Paths.get("runelite-plugin.properties")));
        assertEquals("standard", properties.getProperty("build"));
        assertEquals("com.runeassist.flip.controller.RuneAssistPlugin", properties.getProperty("plugins"));
        String warning = properties.getProperty("warning");
        assertNotNull(warning);
        assertTrue(warning.toLowerCase().contains("grand exchange"));
        assertTrue(warning.toLowerCase().contains("ip address"));
        assertTrue(descriptor.description().toLowerCase().contains("ares"));
        assertTrue(descriptor.description().toLowerCase().contains("held-cost"));
        assertEquals(descriptor.description(), properties.getProperty("description"));
    }

    @Test
    public void hubManifestWarningMatchesPluginProperties() throws Exception {
        Properties properties = new Properties();
        properties.load(Files.newInputStream(Paths.get("runelite-plugin.properties")));
        List<String> lines = Files.readAllLines(Paths.get("plugin-hub/plugins/runeassist-flipping"));
        String warningLine = lines.stream().filter(l -> l.startsWith("warning=")).findFirst().orElse(null);
        assertNotNull(warningLine);
        assertEquals("warning=" + properties.getProperty("warning"), warningLine);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("repository=https://github.com/RuneAssist/runeassist.git")));
        assertTrue(lines.stream().anyMatch(l -> l.equals("authors=RuneAssist")));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("commit=")));
    }

    @Test
    public void bsdAttributionFilesRemain() throws Exception {
        String license = Files.readString(Paths.get("LICENSE"), StandardCharsets.UTF_8);
        String third = Files.readString(Paths.get("THIRD_PARTY_LICENSES.md"), StandardCharsets.UTF_8);
        assertTrue(license.contains("BSD 2-Clause"));
        assertTrue(third.contains("Flipping Copilot"));
        assertTrue(third.contains("cbrewitt/flipping-copilot"));
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
