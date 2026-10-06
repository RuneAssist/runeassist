package com.runeassist.flip;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;

@Slf4j
public final class HubPluginConflict
{
    public static final String WAIT_MESSAGE = "Turn off Plugin Hub Flipping Copilot";
    public static final String ALLOW_PROPERTY = "runeassist.allowCopilot";

    private HubPluginConflict()
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
        if (Boolean.getBoolean(ALLOW_PROPERTY))
        {
            return false;
        }
        if (pluginManager == null)
        {
            return false;
        }
        try
        {
            for (Plugin p : pluginManager.getPlugins())
            {
                if (isHubPlugin(p))
                {
                    boolean enabled = pluginManager.isPluginEnabled(p);
                    if (enabled)
                    {
                        return true;
                    }
                }
            }
        }
        catch (RuntimeException e)
        {
            log.warn("HubPluginConflict.isEnabled scan failed", e);
        }
        return false;
    }
}
