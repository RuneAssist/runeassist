package com.osrsmcp;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
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
    @Inject private TelemetryService telemetry;
    @Inject private NudgeService nudgeService;
    @Inject private RuneAssistOverlay runeAssistOverlay;
    @Inject private GeOffersOverlay geOffersOverlay;
    @Inject private TaskService taskService;
    @Inject private net.runelite.client.ui.overlay.OverlayManager overlayManager;
    @Inject private SessionTracker sessionTracker;

    private NavigationButton navButton;
    private NavigationButton chatNavButton;

    // Telemetry: last seen XP per skill (for XP-gain deltas) and tick counter for periodic snapshots.
    private final java.util.Map<Skill, Long> lastXp = new java.util.HashMap<>();
    private int gameTickCounter = 0;
    private static final int SNAPSHOT_INTERVAL_TICKS = 3000;

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
        final BufferedImage chatIcon = ImageUtil.loadImageResource(getClass(), "runeassist.png");
        navButton = NavigationButton.builder()
            .tooltip("RuneAssist Server")
            .icon(icon)
            .priority(11)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

        chatNavButton = NavigationButton.builder()
            .tooltip("RuneAssist (AI chat)")
            .icon(chatIcon)
            .priority(10)
            .panel(chatPanel)
            .build();
        clientToolbar.addNavigation(chatNavButton);
        chatPanel.refresh();

        overlayManager.add(runeAssistOverlay);
        overlayManager.add(geOffersOverlay);
    }

    @Override
    protected void shutDown() throws Exception
    {
        panel.stopStatusUpdates();
        companionAgent.shutdown();
        telemetry.shutdown();
        overlayManager.remove(runeAssistOverlay);
        overlayManager.remove(geOffersOverlay);
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

        if (event.getGameState() == net.runelite.api.GameState.LOGGED_IN)
        {
            // Reset tracked XP so no bogus session-to-session delta is recorded, then
            // immediately capture a baseline snapshot on the client thread.
            lastXp.clear();
            captureAccountSnapshot();
            // Start a fresh session baseline for get_session_summary.
            sessionTracker.onLogin();
            nudgeService.onLogin();
        }
        else
        {
            // Leaving the game / hopping: drop tracked XP so a fresh session starts clean.
            lastXp.clear();
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        // Already on client thread -- write character cache directly
        writeCharacterCache();

        // Proactive nudge on a genuine level-up (milestone only; NudgeService gates it).
        nudgeService.onLevelUp(event.getSkill(), event.getLevel());

        // Auto-tick any goal whose skill/level target is now met; pop a nudge for each.
        for (String doneGoal : taskService.evaluate())
            nudgeService.onGoalComplete(doneGoal);

        // Telemetry: record XP gain delta on the client thread.
        Skill skill = event.getSkill();
        long now = event.getXp();
        long prev = lastXp.getOrDefault(skill, -1L);
        lastXp.put(skill, now);
        if (prev >= 0 && now > prev)
        {
            WorldPoint wp = localPlayerLocation();
            int x = 0, y = 0, plane = 0;
            if (wp != null) { x = wp.getX(); y = wp.getY(); plane = wp.getPlane(); }
            telemetry.logXpGain(rsn(), skill.getName(), now, now - prev,
                event.getLevel(), x, y, plane);
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        // Capture the per-session baseline once skills are reliable (first tick after login).
        sessionTracker.captureIfNeeded(client);

        // Every ~3000 ticks (~30 min) capture a periodic account snapshot.
        if (++gameTickCounter < SNAPSHOT_INTERVAL_TICKS) return;
        gameTickCounter = 0;
        captureAccountSnapshot();
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

    // ── TELEMETRY HELPERS ─────────────────────────────────────────────────────

    /** The local player's name (telemetry hashes it), or "anon" when not logged in. */
    private String rsn()
    {
        net.runelite.api.Player p = client.getLocalPlayer();
        return p != null ? p.getName() : "anon";
    }

    private WorldPoint localPlayerLocation()
    {
        net.runelite.api.Player p = client.getLocalPlayer();
        return p != null ? p.getWorldLocation() : null;
    }

    /** Gather a full account snapshot on the client thread and hand it to telemetry. */
    private void captureAccountSnapshot()
    {
        if (client.getGameState() != net.runelite.api.GameState.LOGGED_IN) return;
        net.runelite.api.Player player = client.getLocalPlayer();
        if (player == null) return;

        WorldPoint wp = localPlayerLocation();
        int x = 0, y = 0, plane = 0;
        if (wp != null) { x = wp.getX(); y = wp.getY(); plane = wp.getPlane(); }

        java.util.Map<String, long[]> skills = new java.util.LinkedHashMap<>();
        for (Skill skill : Skill.values())
        {
            if (skill == Skill.OVERALL) continue;
            skills.put(skill.getName(),
                new long[]{client.getRealSkillLevel(skill), client.getSkillExperience(skill)});
        }

        net.runelite.api.vars.AccountType at = client.getAccountType();
        String acctType = at != null ? at.name() : "normal";
        telemetry.logAccountSnapshot(rsn(), player.getCombatLevel(),
            client.getTotalLevel(), client.getVarpValue(VarPlayer.QUEST_POINTS),
            acctType, x, y, plane, skills);
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
        nudgeService.onChatMessage(event.getMessage());
    }

    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged e)
    {
        GrandExchangeOffer o = e.getOffer();
        if (o == null) return;
        telemetry.logGeOffer(rsn(), e.getSlot(), o.getState().name(), o.getItemId(),
            o.getPrice(), o.getTotalQuantity(), o.getQuantitySold(), o.getSpent());
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
