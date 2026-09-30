package com.runeassist.flip.controller;

import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.plugins.banktags.TagManager;

public final class BankTagsLookup {
    private BankTagsLookup() {
    }

    public static BankTagsPlugin findActive(PluginManager pluginManager) {
        if (pluginManager == null) {
            return null;
        }
        for (Plugin plugin : pluginManager.getPlugins()) {
            if (plugin instanceof BankTagsPlugin && pluginManager.isPluginActive(plugin)) {
                return (BankTagsPlugin) plugin;
            }
        }
        return null;
    }

    public static TagManager tagManager(BankTagsPlugin bankTagsPlugin) {
        if (bankTagsPlugin == null || bankTagsPlugin.getInjector() == null) {
            return null;
        }
        try {
            return bankTagsPlugin.getInjector().getInstance(TagManager.class);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
