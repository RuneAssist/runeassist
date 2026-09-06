package com.runeassist.flip.controller;

import com.runeassist.flip.AresMarketClient;
import com.runeassist.flip.HeldCostTracker;
import com.runeassist.flip.model.PortfolioItemCardData;
import com.runeassist.flip.model.SuggestionManager;
import com.runeassist.flip.rs.BankStateRS;
import com.runeassist.flip.rs.PortfolioStateRS;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.chatbox.ChatboxPanelManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Near-GE Examine menus: Add/Remove (+ X qty) for portfolio held lots. */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class PortfolioMenuEntries {

    private static final String MENU_ADD = "Add-All to portfolio";
    private static final String MENU_ADD_X = "Add-X to portfolio";
    private static final String MENU_REMOVE = "Remove-All from portfolio";
    private static final String MENU_REMOVE_X = "Remove-X from portfolio";

    enum MenuLocation { INVENTORY, BANK }

    private final Client client;
    private final ItemController itemController;
    private final PortfolioStateRS portfolioStateRS;
    private final BankStateRS bankStateRS;
    private final ChatboxPanelManager chatboxPanelManager;
    private final HeldCostTracker heldCostTracker;
    private final AresMarketClient market;
    private final FlipHistorySyncService flipHistorySyncService;
    private final SuggestionManager suggestionManager;
    private final ExecutorService executorService;

    public void injectExamineMenus(MenuEntryAdded event) {
        InventoryMenuItem menuItem = getInventoryMenuItem(event);
        if (menuItem == null) {
            return;
        }
        int locationQty = getLocationQuantity(menuItem.unnotedItemId, menuItem.location);
        if (locationQty <= 0) {
            return;
        }
        PortfolioQtys qtys = resolvePortfolioQtys(menuItem.unnotedItemId);
        MenuVisibility vis = menuVisibility(locationQty, qtys.portfolioQty, qtys.notInPortfolio, qtys.unknownItem);
        if (vis.showRemoveX) {
            addEntry(MENU_REMOVE_X, menuItem,
                    e -> promptQuantity(menuItem, "Enter quantity to remove:", qtys.portfolioQty, true));
        }
        if (vis.showRemove) {
            addEntry(MENU_REMOVE, menuItem, e -> untrackHeld(menuItem, locationQty));
        }
        if (vis.showAddX) {
            int addMax = qtys.notInPortfolio > 0 ? qtys.notInPortfolio : locationQty;
            addEntry(MENU_ADD_X, menuItem,
                    e -> promptQuantity(menuItem, "Enter quantity to add:", addMax, false));
        }
        if (vis.showAdd) {
            addEntry(MENU_ADD, menuItem, e -> trackHeld(menuItem, locationQty));
        }
    }

    static MenuVisibility menuVisibility(int locationQty, int portfolioQty, int notInPortfolio, boolean unknownItem) {
        boolean showAdd = locationQty > 0 && (unknownItem || portfolioQty <= 0 || notInPortfolio > 0);
        boolean showRemove = locationQty > 0 && portfolioQty > 0;
        boolean showAddX = notInPortfolio > 1;
        boolean showRemoveX = portfolioQty > 1;
        return new MenuVisibility(showAdd, showRemove, showAddX, showRemoveX);
    }

    static final class MenuVisibility {
        final boolean showAdd;
        final boolean showRemove;
        final boolean showAddX;
        final boolean showRemoveX;

        MenuVisibility(boolean showAdd, boolean showRemove, boolean showAddX, boolean showRemoveX) {
            this.showAdd = showAdd;
            this.showRemove = showRemove;
            this.showAddX = showAddX;
            this.showRemoveX = showRemoveX;
        }
    }

    private static final class PortfolioQtys {
        final int portfolioQty;
        final int notInPortfolio;
        final boolean unknownItem;

        PortfolioQtys(int portfolioQty, int notInPortfolio, boolean unknownItem) {
            this.portfolioQty = portfolioQty;
            this.notInPortfolio = notInPortfolio;
            this.unknownItem = unknownItem;
        }
    }

    private static final class InventoryMenuItem {
        final int unnotedItemId;
        final String menuTarget;
        final MenuLocation location;

        InventoryMenuItem(int unnotedItemId, String menuTarget, MenuLocation location) {
            this.unnotedItemId = unnotedItemId;
            this.menuTarget = menuTarget;
            this.location = location;
        }
    }

    private PortfolioQtys resolvePortfolioQtys(int itemId) {
        PortfolioItemCardData card = null;
        if (portfolioStateRS.get().isLoaded()) {
            card = portfolioStateRS.get().getItemCardDataByItemId().get(itemId);
        }
        if (card != null) {
            return new PortfolioQtys(card.getPortfolioQuantity(), card.getNotInPortfolioQuantity(), false);
        }
        Player localPlayer = client.getLocalPlayer();
        String displayName = localPlayer != null ? localPlayer.getName() : null;
        Map<Integer, long[]> held = heldCostTracker.held(displayName);
        long[] row = held == null ? null : held.get(itemId);
        int portfolioQty = row == null ? 0 : (int) Math.max(0L, row[0]);
        Map<Integer, Integer> inv = itemController.getRunliteInventory();
        int invQty = inv == null ? 0 : Math.max(0, inv.getOrDefault(itemId, 0));
        int bankQty = 0;
        if (bankStateRS.get().isLoaded()) {
            Map<Integer, Integer> bank = bankStateRS.get().getItems();
            bankQty = bank == null ? 0 : Math.max(0, bank.getOrDefault(itemId, 0));
        }
        int notInPortfolio = Math.max(0, invQty + bankQty - portfolioQty);
        boolean unknownItem = portfolioQty <= 0;
        if (unknownItem) {
            notInPortfolio = 0;
        }
        return new PortfolioQtys(portfolioQty, notInPortfolio, unknownItem);
    }

    private void promptQuantity(InventoryMenuItem menuItem, String prompt, int maxQty, boolean remove) {
        chatboxPanelManager.openTextInput(prompt)
                .charValidator(c -> c >= '0' && c <= '9')
                .onDone((String input) -> {
                    if (input == null || input.isEmpty()) {
                        return;
                    }
                    try {
                        int qty = Integer.parseInt(input);
                        if (qty > 0) {
                            int capped = maxQty > 0 ? Math.min(qty, maxQty) : qty;
                            if (remove) {
                                untrackHeld(menuItem, capped);
                            } else {
                                trackHeld(menuItem, capped);
                            }
                        }
                    } catch (NumberFormatException ignored) {
                    }
                })
                .build();
    }

    private void trackHeld(InventoryMenuItem menuItem, int qty) {
        if (qty <= 0) {
            return;
        }
        int itemId = menuItem.unnotedItemId;
        Player localPlayer = client.getLocalPlayer();
        String displayName = localPlayer != null ? localPlayer.getName() : null;
        executorService.execute(() -> {
            long unitCost = 0;
            try {
                Map<String, Object> quote = market.quote(itemId);
                if (quote != null && quote.get("buy_at") instanceof Number) {
                    unitCost = ((Number) quote.get("buy_at")).longValue();
                }
            } catch (Exception ex) {
                log.warn("market quote failed while adding item {} to portfolio", itemId, ex);
            }
            if (flipHistorySyncService != null && flipHistorySyncService.isLinked()
                    && flipHistorySyncService.toggleItemPortfolio(displayName, itemId, qty, unitCost, false)) {
                log.info("added {} x item {} to server portfolio at estimated cost {} gp", qty, itemId, unitCost);
            } else {
                heldCostTracker.addManualLot(displayName, itemId, qty, unitCost);
                suggestionManager.setSuggestionNeeded(true);
                log.info("added {} x item {} to local portfolio at estimated cost {} gp", qty, itemId, unitCost);
            }
        });
    }

    private void untrackHeld(InventoryMenuItem menuItem, int qty) {
        if (qty <= 0) {
            return;
        }
        int itemId = menuItem.unnotedItemId;
        Player localPlayer = client.getLocalPlayer();
        String displayName = localPlayer != null ? localPlayer.getName() : null;
        executorService.execute(() -> {
            if (flipHistorySyncService != null && flipHistorySyncService.isLinked()
                    && flipHistorySyncService.toggleItemPortfolio(displayName, itemId, qty, 0L, true)) {
                log.info("removed {} x item {} from server portfolio", qty, itemId);
            } else {
                int removed = heldCostTracker.removeLots(displayName, itemId, qty);
                suggestionManager.setSuggestionNeeded(true);
                log.info("removed {} x item {} from local portfolio", removed, itemId);
            }
        });
    }

    private void addEntry(String option, InventoryMenuItem menuItem, Consumer<MenuEntry> onClick) {
        client.getMenu()
                .createMenuEntry(-1)
                .setOption(option)
                .setTarget(menuItem.menuTarget)
                .onClick(onClick);
    }

    private int getLocationQuantity(int itemId, MenuLocation location) {
        if (location == MenuLocation.INVENTORY) {
            Map<Integer, Integer> inv = itemController.getRunliteInventory();
            return inv == null ? 0 : Math.max(0, inv.getOrDefault(itemId, 0));
        }
        if (location == MenuLocation.BANK) {
            if (!bankStateRS.get().isLoaded()) {
                return 0;
            }
            Map<Integer, Integer> bank = bankStateRS.get().getItems();
            return bank == null ? 0 : Math.max(0, bank.getOrDefault(itemId, 0));
        }
        return 0;
    }

    private MenuLocation getMenuLocation(int widgetId) {
        Widget geInventoryWidget = client.getWidget(467, 0);
        if (geInventoryWidget != null && geInventoryWidget.getId() == widgetId) {
            return MenuLocation.INVENTORY;
        }
        Widget inventoryWidget = client.getWidget(149, 0);
        if (inventoryWidget != null && inventoryWidget.getId() == widgetId) {
            return MenuLocation.INVENTORY;
        }
        Widget bankInventoryWidget = client.getWidget(
                BankWidgets.BANK_INVENTORY_WIDGET_GROUP, BankWidgets.BANK_INVENTORY_WIDGET_CHILD);
        if (bankInventoryWidget != null && bankInventoryWidget.getId() == widgetId) {
            return MenuLocation.INVENTORY;
        }
        for (int childId : BankWidgets.BANK_ITEM_CONTAINER_CHILDREN) {
            Widget bankItemWidget = client.getWidget(BankWidgets.BANK_WIDGET_GROUP, childId);
            if (bankItemWidget != null && bankItemWidget.getId() == widgetId) {
                return MenuLocation.BANK;
            }
        }
        return null;
    }

    private InventoryMenuItem getInventoryMenuItem(MenuEntryAdded event) {
        if (!"Examine".equals(event.getOption())) {
            return null;
        }
        int inventorySlot = event.getActionParam0();
        int inventoryWidgetId = event.getActionParam1();
        MenuLocation location = getMenuLocation(inventoryWidgetId);
        if (location == null) {
            return null;
        }
        Widget inventoryWidget = client.getWidget(inventoryWidgetId);
        if (inventoryWidget == null || inventorySlot < 0) {
            return null;
        }
        Widget[] items = inventoryWidget.getDynamicChildren();
        if (items == null || inventorySlot >= items.length) {
            return null;
        }
        Widget itemWidget = items[inventorySlot];
        if (itemWidget == null || itemWidget.getItemId() <= 0) {
            return null;
        }
        int unnotedItemId = itemController.toUnnotedItemId(itemWidget.getItemId());
        String menuTarget = resolveMenuTarget(event.getTarget(), unnotedItemId);
        return new InventoryMenuItem(unnotedItemId, menuTarget, location);
    }

    private String resolveMenuTarget(String eventTarget, int itemId) {
        if (eventTarget != null && !eventTarget.isBlank()) {
            return eventTarget;
        }
        ItemComposition item = client.getItemDefinition(itemId);
        return "<col=ff9040>" + item.getName() + "</col>";
    }
}
