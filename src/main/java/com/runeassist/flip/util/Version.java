package com.runeassist.flip.util;

/**
 * The plugin version, shown in Preferences, logged at start-up and sent with every
 * server request. Keep it equal to {@code version} in build.gradle and
 * runelite-plugin.properties; scripts/version-check.sh fails CI when they differ.
 */
public final class Version {
    public static final String VERSION = "1.1.0";
    public static final String USER_AGENT = "RuneAssist-flip/" + VERSION + " (github.com/RuneAssist/runeassist)";

    private Version() {
    }
}
