package com.osrsmcp;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.InventoryID;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

@Slf4j
@PluginDescriptor(
    name = "RuneAssist",
    description = "In-game AI companion: live account-aware advice, plus a local MCP server for external AI clients.",
    tags = {"claude", "ai", "stats", "helper", "assistant", "mcp", "companion", "runeassist"}
)
public class OsrsMcpPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private ClientToolbar clientToolbar;
    @Inject private OsrsMcpConfig config;
    @Inject private McpServer mcpServer;
    @Inject private OsrsMcpPanel panel;
    @Inject private TailscaleService tailscaleService;
    @Inject private ConfigManager configManager;
    @Inject private PlayerDataService playerDataService;
    @Inject private CacheWriter cacheWriter;
    @Inject private EquipmentStatsService equipmentStatsService;
    @Inject private InteropService interopService;
    @Inject private QuestPlanService questPlanService;
    @Inject private PluginManager pluginManager;
    @Inject private DailyTracker dailyTracker;
    @Inject private OsrsMcpChatPanel chatPanel;
    @Inject private CompanionAgent companionAgent;

    private NavigationButton navButton;
    private NavigationButton chatNavButton;

    @Override
    protected void startUp() throws Exception
    {
        panel.setRestartCallback(this::restartServer);
        playerDataService.loadPersistedItems();
        cacheWriter.init();
        panel.setTailscaleService(tailscaleService);
        panel.setConfigManager(configManager);
        panel.setReloadCallback(() -> questPlanService.reloadData());
        panel.setStatusSupplier(this::buildPanelStatus);
        startServer();

        final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
        navButton = NavigationButton.builder()
            .tooltip("RuneAssist Server")
            .icon(icon)
            .priority(11)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

        chatNavButton = NavigationButton.builder()
            .tooltip("RuneAssist (AI chat)")
            .icon(icon)
            .priority(10)
            .panel(chatPanel)
            .build();
        clientToolbar.addNavigation(chatNavButton);
        chatPanel.refresh();
    }

    @Override
    protected void shutDown() throws Exception
    {
        panel.stopStatusUpdates();
        companionAgent.shutdown();
        stopServer();
        clientToolbar.removeNavigation(navButton);
        clientToolbar.removeNavigation(chatNavButton);
    }

    /** Live status for the panel: quest-data source, request activity, interop-plugin availability. */
    private java.util.Map<String, Object> buildPanelStatus()
    {
        java.util.Map<String, Object> s = new java.util.LinkedHashMap<>();
        try
        {
            String gen = questPlanService.getQuestGenerated();
            if (gen != null && gen.length() >= 10) gen = gen.substring(0, 10);
            s.put("quest_data", questPlanService.getQuestSource() + " · " + gen + " (" + questPlanService.getQuestCount() + ")");
        }
        catch (Exception e) { s.put("quest_data", "--"); }

        s.put("request_count", mcpServer.getRequestCount());
        java.util.List<java.util.Map<String, Object>> recent = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        for (Object[] c : mcpServer.getRecentCalls())
        {
            java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("name", c[0]);
            r.put("age", (now - (Long) c[1]) / 1000);
            recent.add(r);
        }
        s.put("recent", recent);

        s.put("shortestpath",    isPluginEnabled("Shortest Path"));
        s.put("osrstcg",         isPluginEnabled("OSRS TCG"));
        s.put("inventorysetups", isPluginEnabled("Inventory Setups"));
        return s;
    }

    private boolean isPluginEnabled(String name)
    {
        try
        {
            for (Plugin p : pluginManager.getPlugins())
            {
                PluginDescriptor d = p.getClass().getAnnotation(PluginDescriptor.class);
                if (d != null && name.equals(d.name())) return pluginManager.isPluginEnabled(p);
            }
        }
        catch (Exception ignored) {}
        return false;
    }

    private void startServer()
    {
        ConnectionMode mode = config.connectionMode();

        try
        {
            mcpServer.start(config.port());
            String lanIp = null;
            if (mode == ConnectionMode.LAN)
                lanIp = getLanIp();
            else if (mode == ConnectionMode.TAILSCALE)
                lanIp = tailscaleService.getTailscaleIp();
            panel.setServerRunning(true, config.port(), mode, lanIp);
        }
        catch (IOException e)
        {
            log.error("OSRS MCP: Failed to start on port {}", config.port(), e);
            panel.setError("Port " + config.port() + " is in use. Change it in settings.");
            return;
        }


    }

    private void stopServer()
    {
        mcpServer.stop();
        panel.setServerRunning(false, 0, ConnectionMode.LOCAL, null);
    }

    private void restartServer()
    {
        log.info("OSRS MCP: Restarting server...");
        stopServer();
        try { Thread.sleep(300); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        startServer();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        panel.updateGameState(event.getGameState());
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        // Already on client thread -- write character cache directly
        writeCharacterCache();
    }

    private void writeCharacterCache()
    {
        if (client.getGameState() != net.runelite.api.GameState.LOGGED_IN) return;
        net.runelite.api.Player player = client.getLocalPlayer();
        if (player == null) return;
        java.util.Map<String, Integer> skills = new java.util.LinkedHashMap<>();
        for (net.runelite.api.Skill skill : net.runelite.api.Skill.values())
            if (skill != net.runelite.api.Skill.OVERALL)
                skills.put(skill.getName(), client.getRealSkillLevel(skill));
        net.runelite.api.coords.WorldPoint wp = player.getWorldLocation();
        String loc = wp != null ? wp.getX() + ", " + wp.getY() : null;
        String username = config.shareUsername() ? player.getName() : "hidden";
        net.runelite.api.vars.AccountType at = client.getAccountType();
        boolean ironman = at != net.runelite.api.vars.AccountType.NORMAL;
        cacheWriter.writeCharacter(username, player.getCombatLevel(), skills, loc, at.name().toLowerCase(), ironman);
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        playerDataService.onBankChanged(event);
        int id = event.getContainerId();
        if (id == InventoryID.BANK.getId())
        {
            // Copy items array before handing off to background thread
            net.runelite.api.Item[] items = event.getItemContainer().getItems().clone();
            java.util.concurrent.CompletableFuture.runAsync(() -> cacheWriter.writeBank(items));
        }
        else if (id == InventoryID.SEED_VAULT.getId())
        {
            net.runelite.api.Item[] items = event.getItemContainer().getItems().clone();
            java.util.concurrent.CompletableFuture.runAsync(() -> cacheWriter.writeSeedVault(items));
        }
        else if (id == InventoryID.EQUIPMENT.getId())
        {
            // Collect item names on client thread, then write to disk in background
            java.util.Map<String, String> slotToItem = new java.util.LinkedHashMap<>();
            String[] slotNames = {"head","cape","amulet","weapon","body","shield",
                                  "legs","hands","feet","ring","ammo"};
            net.runelite.api.Item[] items = event.getItemContainer().getItems();
            for (int i = 0; i < items.length && i < slotNames.length; i++)
            {
                if (items[i] == null || items[i].getId() <= 0) continue;
                String name = client.getItemDefinition(items[i].getId()).getName();
                if (name != null && !name.equals("null"))
                    slotToItem.put(slotNames[i], name);
            }
            if (!slotToItem.isEmpty())
            {
                java.util.Map<String, String> snapshot = new java.util.LinkedHashMap<>(slotToItem);
                java.util.concurrent.CompletableFuture.runAsync(
                    () -> cacheWriter.writeEquipment(snapshot, equipmentStatsService));
            }
        }
    }

    @Subscribe
    public void onChatMessage(net.runelite.api.events.ChatMessage event)
    {
        // Capture daily "waiting to be collected" login messages for live status.
        dailyTracker.onChatMessage(event.getMessage());
    }

    @Subscribe
    public void onPluginMessage(net.runelite.client.events.PluginMessage event)
    {
        // Cache inbound data from other plugins (OSRS TCG owned-cards replies/pushes).
        interopService.onPluginMessage(event);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!event.getGroup().equals("osrsmcp")) return;
        if (!event.getKey().equals("connectionMode")) return;

        // Update panel sections immediately when mode changes -- no restart needed
        panel.refreshSectionsForMode(config.connectionMode());
    }

    @Provides
    OsrsMcpConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(OsrsMcpConfig.class);
    }

    private String getLanIp()
    {
        try
        {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces()))
            {
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) continue;
                for (InetAddress addr : Collections.list(iface.getInetAddresses()))
                {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress())
                        return addr.getHostAddress();
                }
            }
        }
        catch (Exception e)
        {
            log.warn("OSRS MCP: Could not determine LAN IP", e);
        }
        return null;
    }
}
