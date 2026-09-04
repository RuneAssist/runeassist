package com.runeassist.flip.config;

import com.runeassist.flip.controller.RuneAssistPlugin;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.plugins.PluginDescriptor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PrivacyPackagingTest {

    /**
     * Same text the hub-side descriptor ({@code plugin-hub/plugins/runeassist-flipping}) shows
     * before install. Deliberately NOT read from {@code runelite-plugin.properties} — the hub
     * packager rejects any key in that file other than displayName, author, description, tags,
     * plugins, version, build, support (see {@code Plugin.java} in plugin-hub-tooling).
     */
    // Coin stack is listed because /v1/flips sends `capital` on every suggestion cycle, with no
    // opt-in gate -- it is not covered by "offers" or "transactions", and it is the user's
    // wealth, so it belongs in the text shown before install.
    private static final String HUB_WARNING_TEXT =
            "This plugin submits your coin stack size, grand exchange offers, grand exchange "
                    + "transactions, and IP address to a 3rd party server not controlled or "
                    + "verified by the RuneLite Developers.";

    private static final Set<String> ALLOWED_PROPERTIES_KEYS = new HashSet<>(Arrays.asList(
            "displayName", "author", "description", "tags", "plugins", "version", "build", "support"));

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
    public void pluginDescriptorAndPropertiesUseStandardBuild() throws Exception {
        PluginDescriptor descriptor = RuneAssistPlugin.class.getAnnotation(PluginDescriptor.class);
        assertNotNull(descriptor);
        assertFalse(descriptor.description().toLowerCase().contains("on by default"));

        Properties properties = loadPropertiesAsPackagerWould();
        assertEquals("standard", properties.getProperty("build"));
        assertEquals("com.runeassist.flip.controller.RuneAssistPlugin", properties.getProperty("plugins"));
        assertTrue(descriptor.description().toLowerCase().contains("held-cost"));
        assertEquals(descriptor.description(), properties.getProperty("description"));
    }

    /**
     * The hub packager (plugin-hub-tooling {@code Plugin.java}) only allows displayName,
     * author, description, tags, plugins, version, build, support in this file — any other
     * key (e.g. a leftover {@code warning=}) fails the hub build.
     */
    @Test
    public void propertiesFileHasNoWarningKeyAndOnlyAllowedKeys() throws Exception {
        Properties properties = loadPropertiesAsPackagerWould();
        assertNull(properties.getProperty("warning"));
        for (String key : properties.stringPropertyNames()) {
            assertTrue(ALLOWED_PROPERTIES_KEYS.contains(key), "disallowed hub properties key: " + key);
        }
    }

    /**
     * The packager loads this file with {@code Properties.load(InputStream)}, which is
     * ISO-8859-1, not UTF-8 — any non-ASCII character (e.g. a UTF-8 "→" arrow) renders as
     * mojibake on the hub. Every value must be pure ASCII.
     */
    @Test
    public void propertiesValuesArePureAsciiForIso88591Loader() throws Exception {
        Properties properties = loadPropertiesAsPackagerWould();
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key);
            assertTrue(isPureAscii(value), key + " contains non-ASCII characters: " + value);
        }
    }

    @Test
    public void hubManifestWarningMatchesExpectedText() throws Exception {
        List<String> lines = Files.readAllLines(Paths.get("plugin-hub/plugins/runeassist-flipping"));
        String warningLine = lines.stream().filter(l -> l.startsWith("warning=")).findFirst().orElse(null);
        assertNotNull(warningLine);
        assertEquals("warning=" + HUB_WARNING_TEXT, warningLine);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("repository=https://github.com/RuneAssist/runeassist.git")));
        assertTrue(lines.stream().anyMatch(l -> l.equals("authors=RuneAssist")));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("commit=")));
    }

    private static Properties loadPropertiesAsPackagerWould() throws Exception {
        Properties properties = new Properties();
        try (java.io.InputStream in = Files.newInputStream(Paths.get("runelite-plugin.properties"))) {
            properties.load(in);
        }
        return properties;
    }

    private static boolean isPureAscii(String value) {
        return value != null && value.chars().allMatch(c -> c < 128);
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
