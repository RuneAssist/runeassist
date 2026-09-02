package com.runeassist.flip;

import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;

/**
 * Plugin Hub "Flipping Copilot" ({@code com.flippingcopilot.*}). This fork is
 * {@code com.runeassist.flip.controller.FlippingCopilotPlugin} and must not emit
 * competing GE suggestions when the Hub plugin is also enabled.
 */
public final class HubFlippingCopilot
{
    public static final String WAIT_MESSAGE = "Turn off Plugin Hub Flipping Copilot";

    private HubFlippingCopilot()
    {
    }

    public static boolean isHubPlugin(Plugin plugin)
    {
        if (plugin == null)
        {
            return false;
        }
        String className = plugin.getClass().getName();
        if (className.startsWith("com.flippingcopilot."))
        {
            return true;
        }
        if (className.startsWith("com.runeassist."))
        {
            return false;
        }
        PluginDescriptor d = plugin.getClass().getAnnotation(PluginDescriptor.class);
        return d != null && "Flipping Copilot".equals(d.name());
    }

    public static boolean isEnabled(PluginManager pluginManager)
    {
        if (pluginManager == null)
        {
            return false;
        }
        try
        {
            for (Plugin p : pluginManager.getPlugins())
            {
                if (isHubPlugin(p) && pluginManager.isPluginEnabled(p))
                {
                    return true;
                }
            }
        }
        catch (RuntimeException ignored)
        {
        }
        return false;
    }
}
