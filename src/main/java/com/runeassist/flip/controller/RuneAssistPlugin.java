package com.runeassist.flip.controller;

import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.model.*;
import com.runeassist.flip.rs.*;
import com.runeassist.flip.ui.*;
import com.runeassist.flip.ui.flipsdialog.FlipsDialogController;
import com.google.gson.Gson;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.*;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.*;

@Slf4j
// Self-wiring flipping plugin. Suggestions come from RuneAssistSuggestionSource
// (Ares /v1/suggestion compose + held-cost / held-decant).
@PluginDescriptor(
		name = "RuneAssist Flipping",
		description = "Grand Exchange flipping assistant with server compose suggestions, held-cost tracking, and Ares market data. Anonymous contribution is opt-in (Configuration -> Privacy).",
		tags = {"runeassist", "flipping", "ge", "grand exchange", "merch", "money making", "profit"}
)
// No @PluginDependency(BankTagsPlugin): sideloaded installs refuse to load with it.
// Bank Tags is resolved at runtime via BankTagsLookup when the portfolio tab is enabled.
public class RuneAssistPlugin extends Plugin {

	@Inject
	private RuneAssistConfig config;
	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	@Named("runeAssistExecutor")
	private ScheduledExecutorService executorService;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private Gson gson;
	@Inject
	private GrandExchange grandExchange;
	@Inject
	private GrandExchangeCollectHandler grandExchangeCollectHandler;
	@Inject
	private GrandExchangeOfferEventHandler offerEventHandler;
	@Inject
	private AccountStatusManager accountStatusManager;
	@Inject
	private SuggestionController suggestionController;
	@Inject
	private SuggestionManager suggestionManager;
	@Inject
	private WebHookController webHookController;
	@Inject
	private KeybindHandler keybindHandler;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private AccountLoginRS accountLoginRS;
	@Inject
	private HighlightController highlightController;
	@Inject
	private GameUiChangesHandler gameUiChangesHandler;
	@Inject
	private OsrsLoginManager osrsLoginManager;
	@Inject
	private FlipManager flipManager;
	@Inject
	private SessionManager sessionManager;
	@Inject
	private GrandExchangeUncollectedManager grandExchangeUncollectedManager;
	@Inject
	private TransactionManager transactionManager;
	@Inject
	private OfferManager offerManager;
	@Inject
	private TooltipController tooltipController;
  	@Inject
	private MenuHandler menuHandler;
	@Inject
	private FlipsDialogController flipsDialogController;
	@Inject
	private SlotProfitColorizer slotProfitColorizer;
	@Inject
	private GrandExchangeOpenRS grandExchangeOpenRS;
	@Inject
	private OsrsLoginRS osrsLoginRS;
	@Inject
	private RuneAssistConfigRS configRS;
	@Inject
	private InventorySlotTooltipOverlay inventorySlotTooltipOverlay;
	@Inject
	private InventoryPortfolioBadgeOverlay inventoryPortfolioBadgeOverlay;
	@Inject
	private PortfolioBankTabBadgeOverlay portfolioBankTabBadgeOverlay;
	@Inject
	private BankStateRS bankStateRS;

	@Inject
	private GeHistoryStateRS geHistoryStateRS;
	@Inject
	private PatchNotesController patchNotesController;
	@Inject
	private PortfolioBankTagController portfolioBankTagController;
	@Inject
	private PlayerLocationController playerLocationController;
	@Inject
	private com.runeassist.flip.HeldCostTracker heldCostTracker;
	@Inject
	private com.runeassist.flip.TelemetryService telemetry;
	@Inject
	private com.runeassist.flip.GeHistoryDump geHistoryDump;
	@Inject
	private com.runeassist.flip.GeHistoryHeldBackfill geHistoryHeldBackfill;
	@Inject
	private FlipHistorySyncService flipHistorySyncService;

	// We use our own ThreadPool since the default ScheduledExecutorService only has a single thread and we don't want to block it
	@Provides
	@Singleton
	@Named("runeAssistExecutor")
	public ScheduledExecutorService provideCustomExecutorService() {
		return Executors.newScheduledThreadPool(2);
	}

	@Provides
	@Singleton
	public ExecutorService provideExecutorService(@Named("runeAssistExecutor") ScheduledExecutorService scheduledExecutor) {
		return scheduledExecutor;
	}

	private MainPanel mainPanel;
	private StatsPanelV2 statsPanel;
	private NavigationButton navButton;

	@Override
	protected void startUp() throws Exception {
		boolean hadExistingInstallation = Persistance.hasExistingInstallation();
		keybindHandler.register();
		overlayManager.add(inventorySlotTooltipOverlay);
		overlayManager.add(inventoryPortfolioBadgeOverlay);
		overlayManager.add(portfolioBankTabBadgeOverlay);
		portfolioBankTagController.startUp();
		highlightController.activate();
		Persistance.setUp(gson);
		// seems we need to delay instantiating the UI till here as otherwise the panels look different
		mainPanel = injector.getInstance(MainPanel.class);
		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/runeassist-flip.png");
		navButton = NavigationButton.builder()
				.tooltip("RuneAssist Flipping")
				.icon(icon)
				.priority(3)
				.panel(mainPanel)
				.build();
		clientToolbar.addNavigation(navButton);
		suggestionController.setRuneAssistPanel(mainPanel.runeAssistPanel);
		suggestionController.setMainPanel(mainPanel);
		suggestionController.setSuggestionPanel(mainPanel.runeAssistPanel.suggestionPanel);
		grandExchangeCollectHandler.setSuggestionPanel(mainPanel.runeAssistPanel.suggestionPanel);
		statsPanel = mainPanel.runeAssistPanel.statsPanel;

		// On the client thread, as every other refresh site already is: the status strip reads
		// the inventory through AccountStatusManager, and client.getItemContainer asserts it is
		// called from there. startUp runs on the Swing EDT, so refreshing straight from here
		// raised an AssertionError that aborted plugin startup outright.
		clientThread.invokeLater(mainPanel::refresh);
		SwingUtilities.invokeLater(() -> patchNotesController.maybeShowOnStartup(mainPanel, hadExistingInstallation));

		if(osrsLoginManager.getInvalidStateDisplayMessage() == null) {
			bindOsrsSession(osrsLoginManager.getPlayerDisplayName());
		}
		flipsDialogController.initDialog(SwingUtilities.getWindowAncestor(mainPanel));
		telemetry.onUploadSettingsChanged();
		flipHistorySyncService.start();
		executorService.scheduleAtFixedRate(() ->
			clientThread.invoke(() -> {
				boolean loginValid = osrsLoginManager.isValidLoginState();
				if (loginValid) {
					sessionManager.startOrResume();
					AccountStatus accStatus = accountStatusManager.getAccountStatus();
					boolean isFlipping = accStatus != null && accStatus.currentlyFlipping();
					long cashStack = accStatus == null ? 0 : accStatus.currentCashStack();
					sessionManager.updateSessionStats(isFlipping, cashStack);
					if (statsPanel != null) {
						statsPanel.refresh(false, true);
					}
				}
			})
		, 2000, 1000, TimeUnit.MILLISECONDS);
	}

	@Override
	protected void shutDown() throws Exception {
		overlayManager.remove(inventorySlotTooltipOverlay);
		overlayManager.remove(inventoryPortfolioBadgeOverlay);
		overlayManager.remove(portfolioBankTabBadgeOverlay);
		portfolioBankTagController.shutDown();
		offerManager.saveAll();
		highlightController.deactivateAndRemoveAll();
		clientThread.invokeLater(() -> slotProfitColorizer.resetAllSlots());
		clientToolbar.removeNavigation(navButton);
		String displayName = osrsLoginManager.getLastDisplayName();
		Integer accountId = accountLoginRS.get().getAccountId(displayName);
		if (accountId != null && accountId != -1) {
			webHookController.sendMessage(flipManager.calculateStats(sessionManager.getCachedSessionData().startTime, accountId), sessionManager.getCachedSessionData(), displayName, false);
		}
		keybindHandler.unregister();
		telemetry.shutdown();
		executorService.shutdownNow();
	}

	@Provides
	public RuneAssistConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(RuneAssistConfig.class);
	}

	//---------------------------- Event Handlers ----------------------------//
	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event) {
		offerEventHandler.onGrandExchangeOfferChanged(event);
		// RuneAssist: track cost basis of held stock so we can suggest profitable sells.
		net.runelite.api.GrandExchangeOffer o = event.getOffer();
		if (o != null) {
			net.runelite.api.Player p = client.getLocalPlayer();
			String rsn = p != null ? p.getName() : "anon";
			heldCostTracker.onOffer(rsn, event.getSlot(), o.getState(), o.getItemId(),
				o.getPrice(), o.getTotalQuantity(), o.getQuantitySold(), o.getSpent());
			telemetry.logGeOffer(rsn, event.getSlot(), o.getState().name(), o.getItemId(),
				o.getPrice(), o.getTotalQuantity(), o.getQuantitySold(), o.getSpent());
		}
		clientThread.invokeLater(() -> highlightController.redraw());
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		boolean inventoryChanged = event.getContainerId() == InventoryID.INV;
		boolean bankChanged = event.getContainerId() == InventoryID.BANK;

		if (bankChanged || (inventoryChanged && isBankOpen())) {
			bankStateRS.onGameTick();
			clientThread.invokeLater(() -> highlightController.redraw());
		}
		if (bankChanged && playerLocationController.isNearGE()) {
			suggestionManager.setSuggestionNeeded(true);
		}

        if (event.getContainerId() == InventoryID.INV && grandExchange.isOpen()) {
            if (!accountStatusManager.isOwnedModifyActive() || !grandExchange.isSlotOpen()) {
                suggestionManager.setSuggestionNeeded(true);
            }
            clientThread.invokeLater(() -> highlightController.redraw());
        }
	}

	private boolean isBankOpen() {
		Widget bank = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
		return bank != null && !bank.isHidden();
	}

	/** Wire session clock + local flip stats to the logged-in OSRS account (not FC email). */
	private void bindOsrsSession(String name) {
		if (name == null || name.isEmpty()) {
			flipManager.setIntervalAccount(null);
			return;
		}
		sessionManager.startOrResume();
		transactionManager.hydrateLocal(name);
		transactionManager.seedLiveOffers(name, client.getGrandExchangeOffers());
		flipHistorySyncService.onLogin(name);
		int accountId = LocalFlipLedger.accountIdFor(name);
		accountLoginRS.addAccountIfMissing(accountId, name);
		flipManager.setPluginUserId(LocalFlipLedger.LOCAL_USER_ID);
		flipManager.setIntervalAccount(accountId);
		if (statsPanel != null) {
			statsPanel.resetIntervalDropdownToSession();
		}
		flipManager.setIntervalStartTime(sessionManager.getCachedSessionData().startTime);
		if (statsPanel != null) {
			statsPanel.refresh(true, osrsLoginManager.isValidLoginState());
		}
		if (mainPanel != null) {
			mainPanel.refresh();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		bankStateRS.onGameTick();
		geHistoryStateRS.onGameTick(client);
		geHistoryDump.onGameTick();
		geHistoryHeldBackfill.maybeApply(geHistoryStateRS.get());
		grandExchangeOpenRS.set(grandExchange.isOpen());

		suggestionController.onGameTick();
		offerEventHandler.onGameTick();
		osrsLoginRS.set(osrsLoginRS.get().nextState(client));
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event) {
		gameUiChangesHandler.onBeforeRender(event);
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event) {
		int slot = grandExchange.getOpenSlot();
		grandExchangeCollectHandler.handleCollect(event, slot);
		gameUiChangesHandler.handleMenuOptionClicked(event);
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired e) {
		tooltipController.tooltip(e);
		gameUiChangesHandler.onScriptPostFired(e);
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event) {
		// RuneAssist fork: "Add to portfolio" now tracks locally (HeldCostTracker) instead of
		// calling FC's real /profit-tracking/toggle-item-portfolio endpoint, which needed an
		// FC account JWT we never have and silently did nothing.
		menuHandler.injectInventoryPortfolioMenuEntry(event);
		menuHandler.injectPriceGraphMenuEntry(event);
		menuHandler.injectConfirmMenuEntry(event);
		menuHandler.injectSlotActionSwapMenuEntry(event);
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		gameUiChangesHandler.onWidgetLoaded(event);
		if (event.getGroupId() == GeHistoryStateRS.GE_HISTORY_GROUP) {
			geHistoryDump.onHistoryWidgetLoaded();
			geHistoryStateRS.onGameTick(client);
			geHistoryHeldBackfill.maybeApply(geHistoryStateRS.get());
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event) {
		gameUiChangesHandler.onWidgetClosed(event);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		gameUiChangesHandler.onVarbitChanged(event);
	}

	@Subscribe
	public void onVarClientStrChanged(VarClientStrChanged event) {
		gameUiChangesHandler.onVarClientStrChanged(event);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		switch (event.getGameState())
		{
			case LOGIN_SCREEN:
				flipHistorySyncService.flushNow();
				sessionManager.reset();
				suggestionManager.reset();
				osrsLoginManager.reset();
				accountStatusManager.reset();
				grandExchangeUncollectedManager.reset();
				statsPanel.refresh(true, osrsLoginManager.isValidLoginState());
				osrsLoginRS.set(osrsLoginRS.get().nextState(client));
				mainPanel.refresh();
				break;
			case LOGGING_IN:
			case HOPPING:
			case CONNECTION_LOST:
				accountStatusManager.clearOwnedModify();
				osrsLoginManager.setLastLoginTick(client.getTickCount());
				osrsLoginRS.set(osrsLoginRS.get().nextState(client));
				break;
			case LOGGED_IN:
				geHistoryDump.onLogin();
				// we want to update the flips panel on login but unfortunately the display name
				// is not available immediately so schedule what we need to do here for in the future
				// todo: move to just using the accountHash which is available immediately to simply things
				clientThread.invokeLater(() -> {
					if (client.getGameState() != GameState.LOGGED_IN) {
						return true;
					}
					final String name = osrsLoginManager.getPlayerDisplayName();
					if(name == null) {
						return false;
					}
					bindOsrsSession(name);
					return true;
				});
		}
	}

	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged event) {
		gameUiChangesHandler.onVarClientIntChanged(event);
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown clientShutdownEvent) {
		log.debug("client shutdown event received");
		flipHistorySyncService.flushNow();
		offerManager.saveAll();
		String displayName = osrsLoginManager.getLastDisplayName();
		Integer accountId = accountLoginRS.get().getAccountId(displayName);
		if (accountId != null && accountId != -1) {
			webHookController.sendMessage(flipManager.calculateStats(sessionManager.getCachedSessionData().startTime, accountId), sessionManager.getCachedSessionData(), displayName, false);
		}
	}

	@Subscribe
	public void onPluginChanged(PluginChanged event) {
		if (event.getPlugin() instanceof BankTagsPlugin) {
			portfolioBankTagController.onBankTagsPluginChanged();
		}
		if (com.runeassist.flip.HubPluginConflict.isHubPlugin(event.getPlugin())) {
			suggestionManager.setSuggestionNeeded(true);
			if (mainPanel != null && mainPanel.runeAssistPanel != null) {
				clientThread.invokeLater(() -> suggestionController.getSuggestionAsync());
			}
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		if (event.getGroup().equals("runeassistflip")) {
			log.debug("runeassist config changed event received");
			configRS.forceSet(config);
			if (event.getKey().equals("profitAmountColor") || event.getKey().equals("lossAmountColor")) {
				mainPanel.runeAssistPanel.statsPanel.refresh(true, osrsLoginManager.isValidLoginState());
			}
			if (event.getKey().equals("suggestionHighlights")) {
				clientThread.invokeLater(() -> highlightController.redraw());
			}
			if (event.getKey().equals("slotPriceColorEnabled")) {
				handleSlotPriceColorConfigChange();
			}
			if (event.getKey().equals("slotPriceProfitableColor") || event.getKey().equals("slotPriceUnprofitableColor")) {
				clientThread.invokeLater(() -> {
					slotProfitColorizer.updateAllSlots();
					highlightController.redraw();
				});
			}
			if ("shareTelemetry".equals(event.getKey())
					|| "telemetryEndpoint".equals(event.getKey())
					|| "telemetryToken".equals(event.getKey())) {
				telemetry.onUploadSettingsChanged();
			}
			if (event.getKey().equals("portfolioBankTag")) {
				portfolioBankTagController.onConfigChanged();
			}
		}
	}

	private void handleSlotPriceColorConfigChange() {
		if (config.slotPriceColorEnabled()) {
			clientThread.invokeLater(() -> slotProfitColorizer.updateAllSlots());
		} else {
			clientThread.invokeLater(() -> slotProfitColorizer.resetAllSlots());
		}
	}
}
