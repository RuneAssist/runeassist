package com.runeassist.flip.controller;

import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.model.BankState;
import com.runeassist.flip.model.PortfolioItemCardData;
import com.runeassist.flip.model.PortfolioState;
import com.runeassist.flip.rs.BankStateRS;
import com.runeassist.flip.rs.PortfolioStateRS;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.util.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntUnaryOperator;

/**
 * Local Bank Tags portfolio tab — FC parity without a server portfolio-tags endpoint.
 * <p>
 * Bank Tags is resolved at runtime (see {@link BankTagsLookup}) so this plugin can sideload
 * without {@code @PluginDependency(BankTagsPlugin.class)}.
 */
@Singleton
@Slf4j
public class PortfolioBankTagController {
    private static final String CONFIG_GROUP = "runeassistflip";
    private static final String CREATED_TAB_CONFIG_KEY = "portfolioBankTagTabCreated";
    private static final String TAG_NAME = "portfolio";
    private static final int TAB_ICON_ITEM_ID = ItemID.FRISD_TAXBAG_BULGING;
    private static final String LEGACY_TAB_ICON_ITEM_ID = String.valueOf(ItemController.PLATINUM_TOKENS_ITEM_ID);

    private final RuneAssistConfig config;
    private final ClientThread clientThread;
    private final ConfigManager configManager;
    private final PluginManager pluginManager;
    private final ItemManager itemManager;
    private final PortfolioStateRS portfolioStateRS;
    private final BankStateRS bankStateRS;

    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean syncQueued = new AtomicBoolean(false);
    private volatile Set<Integer> bankedPortfolioItemIds = Collections.emptySet();
    private Runnable removePortfolioListener;
    private Runnable removeBankListener;

    @Inject
    public PortfolioBankTagController(RuneAssistConfig config,
                                      ClientThread clientThread,
                                      ConfigManager configManager,
                                      PluginManager pluginManager,
                                      ItemManager itemManager,
                                      PortfolioStateRS portfolioStateRS,
                                      BankStateRS bankStateRS) {
        this.config = config;
        this.clientThread = clientThread;
        this.configManager = configManager;
        this.pluginManager = pluginManager;
        this.itemManager = itemManager;
        this.portfolioStateRS = portfolioStateRS;
        this.bankStateRS = bankStateRS;
    }

    public void startUp() {
        active.set(true);
        removePortfolioListener = portfolioStateRS.registerListener(state -> requestSync());
        removeBankListener = bankStateRS.registerListener(state -> requestSync());
        requestSync();
    }

    public void shutDown() {
        active.set(false);
        if (removePortfolioListener != null) {
            removePortfolioListener.run();
            removePortfolioListener = null;
        }
        if (removeBankListener != null) {
            removeBankListener.run();
            removeBankListener = null;
        }
        unregisterTag();
    }

    public void onConfigChanged() {
        requestSync();
    }

    /** Re-evaluate when Bank Tags itself is toggled on/off. */
    public void onBankTagsPluginChanged() {
        requestSync();
    }

    private void requestSync() {
        if (!active.get() || !syncQueued.compareAndSet(false, true)) {
            return;
        }

        // Plugin start-up runs on the AWT event thread when toggled from the plugin
        // panel. ItemManager.canonicalize(), used by sync(), requires the client thread.
        clientThread.invokeLater(() -> {
            syncQueued.set(false);
            if (active.get()) {
                sync();
            }
        });
    }

    private void sync() {
        BankTagsPlugin bankTagsPlugin = BankTagsLookup.findActive(pluginManager);
        TagManager tagManager = BankTagsLookup.tagManager(bankTagsPlugin);
        if (!config.portfolioBankTag() || bankTagsPlugin == null || tagManager == null) {
            bankedPortfolioItemIds = Collections.emptySet();
            if (!config.portfolioBankTag()) {
                removeAutoCreatedTab();
            }
            unregisterTag(tagManager);
            return;
        }

        registerTag(tagManager);
        ensureBankTagTab();
        updateBankedPortfolioItems(bankTagsPlugin);
    }

    private void registerTag(TagManager tagManager) {
        if (registered.compareAndSet(false, true)) {
            tagManager.registerTag(TAG_NAME, itemId -> bankedPortfolioItemIds.contains(canonicalize(itemId)));
            log.debug("registered dynamic Bank Tags tag '{}'", TAG_NAME);
        }
    }

    private void unregisterTag() {
        unregisterTag(BankTagsLookup.tagManager(BankTagsLookup.findActive(pluginManager)));
    }

    private void unregisterTag(TagManager tagManager) {
        bankedPortfolioItemIds = Collections.emptySet();
        if (!registered.compareAndSet(true, false)) {
            return;
        }
        if (tagManager != null) {
            tagManager.unregisterTag(TAG_NAME);
            log.debug("unregistered dynamic Bank Tags tag '{}'", TAG_NAME);
        }
    }

    private void ensureBankTagTab() {
        List<String> tabs = new ArrayList<>(Text.fromCSV(configValue(BankTagsPlugin.TAG_TABS_CONFIG)));
        if (!tabs.contains(TAG_NAME)) {
            tabs.add(TAG_NAME);
            configManager.setConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_TABS_CONFIG, Text.toCSV(tabs));
            configManager.setConfiguration(CONFIG_GROUP, CREATED_TAB_CONFIG_KEY, true);
        }

        String iconKey = BankTagsPlugin.TAG_ICON_PREFIX + TAG_NAME;
        String iconItemId = configManager.getConfiguration(BankTagsPlugin.CONFIG_GROUP, iconKey);
        if (iconItemId == null || LEGACY_TAB_ICON_ITEM_ID.equals(iconItemId)) {
            configManager.setConfiguration(BankTagsPlugin.CONFIG_GROUP, iconKey, TAB_ICON_ITEM_ID);
        }
    }

    private void removeAutoCreatedTab() {
        if (!Boolean.TRUE.equals(configManager.getConfiguration(CONFIG_GROUP, CREATED_TAB_CONFIG_KEY, Boolean.class))) {
            return;
        }

        List<String> tabs = new ArrayList<>(Text.fromCSV(configValue(BankTagsPlugin.TAG_TABS_CONFIG)));
        if (tabs.remove(TAG_NAME)) {
            configManager.setConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_TABS_CONFIG, Text.toCSV(tabs));
        }
        configManager.unsetConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_ICON_PREFIX + TAG_NAME);
        configManager.unsetConfiguration(CONFIG_GROUP, CREATED_TAB_CONFIG_KEY);
    }

    private String configValue(String key) {
        String value = configManager.getConfiguration(BankTagsPlugin.CONFIG_GROUP, key);
        return value == null ? "" : value;
    }

    private void updateBankedPortfolioItems(BankTagsPlugin bankTagsPlugin) {
        Set<Integer> itemIds = selectBankedPortfolioItemIds(
                portfolioStateRS.get(),
                bankStateRS.get(),
                this::canonicalize);
        setBankedPortfolioItemIds(itemIds, bankTagsPlugin);
    }

    /**
     * Portfolio items that both have banked portfolio quantity and are present in the
     * observed bank. Pure for unit tests; canonicalize is injected so ItemManager is optional.
     */
    static Set<Integer> selectBankedPortfolioItemIds(PortfolioState portfolioState,
                                                     BankState bankState,
                                                     IntUnaryOperator canonicalize) {
        if (portfolioState == null || !portfolioState.isLoaded() || bankState == null || !bankState.isLoaded()) {
            return Collections.emptySet();
        }

        Set<Integer> bankItems = new HashSet<>();
        Map<Integer, Integer> items = bankState.getItems();
        if (items != null) {
            items.forEach((itemId, quantity) -> {
                if (itemId != null && quantity != null && quantity > 0) {
                    bankItems.add(canonicalize.applyAsInt(itemId));
                }
            });
        }

        Set<Integer> itemIds = new HashSet<>();
        for (PortfolioItemCardData item : portfolioState.getItemCardDataByItemId().values()) {
            if (item != null && item.hasPortfolioQuantityInBank()) {
                int itemId = canonicalize.applyAsInt(item.getItemId());
                if (bankItems.contains(itemId)) {
                    itemIds.add(itemId);
                }
            }
        }
        return Collections.unmodifiableSet(itemIds);
    }

    private void setBankedPortfolioItemIds(Set<Integer> itemIds, BankTagsPlugin bankTagsPlugin) {
        Set<Integer> previous = bankedPortfolioItemIds;
        if (previous.equals(itemIds)) {
            return;
        }

        bankedPortfolioItemIds = itemIds;
        refreshActivePortfolioTag(bankTagsPlugin);
    }

    private void refreshActivePortfolioTag(BankTagsPlugin bankTagsPlugin) {
        if (bankTagsPlugin == null) {
            return;
        }
        if (TAG_NAME.equals(bankTagsPlugin.getActiveTag())) {
            bankTagsPlugin.openBankTag(TAG_NAME, BankTagsService.OPTION_ALLOW_MODIFICATIONS);
        }
    }

    private int canonicalize(int itemId) {
        return itemManager.canonicalize(Math.abs(itemId));
    }
}
