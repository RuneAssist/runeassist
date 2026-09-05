package com.runeassist.flip.controller;

import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.model.*;
import com.runeassist.flip.rs.BankStateRS;
import com.runeassist.flip.rs.PortfolioStateRS;
import com.runeassist.flip.ui.flipsdialog.FlipsDialogController;
import com.runeassist.flip.ui.graph.model.PriceLine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.api.*;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class MenuHandler {

    private static final int BANK_WIDGET_GROUP = 12;
    private static final int[] BANK_ITEM_CONTAINER_CHILDREN = {12, 13, 89};
    private static final int BANK_INVENTORY_WIDGET_GROUP = 15;
    private static final int BANK_INVENTORY_WIDGET_CHILD = 3;

    private final RuneAssistConfig config;
    private final Client client;
    private final OfferManager offerManager;
    private final GrandExchange grandExchange;
    private final SuggestionManager suggestionManager;
    private final FlipsDialogController flipsDialogController;
    private final ItemController itemController;
    private final PortfolioController portfolioController;
    private final PortfolioStateRS portfolioStateRS;
    private final BankStateRS bankStateRS;
    private final ClientThread clientThread;
    private final PlayerLocationController playerLocationController;
    private final ChatboxPanelManager chatboxPanelManager;
    private final com.runeassist.flip.HeldCostTracker heldCostTracker;
    private final com.runeassist.flip.AresMarketClient market;
    private final ExecutorService executorService;

    private static final String MENU_ADD = "Add-All to portfolio";
    private static final String MENU_ADD_X = "Add-X to portfolio";
    private static final String MENU_REMOVE = "Remove-All from portfolio";
    private static final String MENU_REMOVE_X = "Remove-X from portfolio";

    private enum MenuLocation { INVENTORY, BANK }


    public void injectPriceGraphMenuEntry(MenuEntryAdded event) {
        if (!playerLocationController.isNearGE()) {
            return;
        }

        if(!config.priceGraphMenuOptionEnabled()) {
            return;
        }
        if (event.getOption().equals("View offer")) {
            long slotWidgetId = event.getActionParam1();
            String menuTarget = event.getTarget();
            client.getMenu()
                    .createMenuEntry(-1)
                    .setOption("RuneAssist graph")
                    .setTarget(menuTarget)
                    .onClick((MenuEntry e) -> {
                        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
                        for (int i = 0; i < offers.length; i++) {
                            Widget slotWidget = client.getWidget(465, 7 + i);
                            if (slotWidget != null && slotWidget.getId() == slotWidgetId) {
                                int itemId = offers[i].getItemId();
                                PriceLine priceLine = buildPriceLine(offers[i]);
                                flipsDialogController.showPriceGraphTab(itemId, false, priceLine);
                                log.debug("matched widget to slot {}, item {}", i, offers[i].getItemId());
                            }
                        }
                    });
        } else if (shouldAddInventoryPriceGraphEntry(event)) {
            int inventorySlot = event.getActionParam0();
            int inventoryWidgetId = event.getActionParam1();
            Widget inventoryWidget = client.getWidget(inventoryWidgetId);
            if (inventoryWidget == null || inventorySlot < 0) {
                return;
            }
            Widget[] items = inventoryWidget.getDynamicChildren();
            if (items == null || inventorySlot >= items.length) {
                return;
            }
            Widget itemWidget = items[inventorySlot];
            if (itemWidget == null || itemWidget.getItemId() <= 0) {
                return;
            }
            int itemId = itemWidget.getItemId();
            if (!isGeTradableItem(itemId)) {
                return;
            }
            String menuTarget = resolveMenuTarget(event.getTarget(), itemId);
            int graphItemId = toUnnotedItemId(itemId);
            client.getMenu()
                    .createMenuEntry(-1)
                    .setOption("RuneAssist graph")
                    .setTarget(menuTarget)
                    .onClick((MenuEntry e) -> flipsDialogController.showPriceGraphTab(graphItemId, false, null));
        }
    }

    /**
     * RuneAssist fork: local replacement for FC's cloud "add/remove item portfolio" (which
     * called their {@code /profit-tracking/toggle-item-portfolio} endpoint — needs an FC
     * account JWT we never have, so it silently did nothing). This is for stock RuneAssist
     * never saw bought — "forgotten" items already sitting in the bank/inventory before the
     * plugin tracked them, or bought outside a tracked GE offer — so they start showing up in
     * sell suggestions and portfolio value like anything else. Adds a {@link
     * com.runeassist.flip.HeldCostTracker} lot at the current market buy quote as an
     * estimated cost basis (the real price paid is unknown for stock we never saw bought) —
     * same estimation caveat already used for decant-carried-over cost. No "remove" side:
     * unlike the old cloud feature, this only ever adds a tracked lot: to stop something
     * being suggested, use the existing per-item Skip/Block action on the resulting
     * suggestion instead of trying to un-track it here.
     */
    public void injectInventoryPortfolioMenuEntry(MenuEntryAdded event) {
        if (!playerLocationController.isNearGE()) {
            return;
        }

        InventoryMenuItem menuItem = getInventoryMenuItem(event);
        if (menuItem == null) {
            return;
        }

        int locationQty = getLocationQuantity(menuItem.unnotedItemId, menuItem.location);
        if (locationQty <= 0) {
            return;
        }

        // Menu entries are added in reverse display order (last added = top of menu)

        // Add X — custom amount, when more than 1 is available at this location
        if (locationQty > 1) {
            addPortfolioMenuEntry(MENU_ADD_X, menuItem,
                    e -> promptQuantityAndTrackHeld(menuItem, locationQty));
        }

        // Add-All — track everything present at the clicked location
        addPortfolioMenuEntry(MENU_ADD, menuItem, e -> trackHeld(menuItem, locationQty));
    }

    private void promptQuantityAndTrackHeld(InventoryMenuItem menuItem, int maxQty) {
        chatboxPanelManager.openTextInput("Enter quantity to add to portfolio:")
                .charValidator(c -> c >= '0' && c <= '9')
                .onDone((String input) -> {
                    if (input == null || input.isEmpty()) {
                        return;
                    }
                    try {
                        int qty = Integer.parseInt(input);
                        if (qty > 0) {
                            trackHeld(menuItem, Math.min(qty, maxQty));
                        }
                    } catch (NumberFormatException ignored) {
                    }
                })
                .build();
    }

    /** Registers a manual held lot. Blocks on HTTP (market quote) -- off the client thread. */
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
            heldCostTracker.addManualLot(displayName, itemId, qty, unitCost);
            suggestionManager.setSuggestionNeeded(true);
            log.info("added {} x item {} to local portfolio at estimated cost {} gp", qty, itemId, unitCost);
        });
    }

    private void addPortfolioMenuEntry(String option, InventoryMenuItem menuItem, Consumer<MenuEntry> onClick) {
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


    private boolean shouldAddInventoryPriceGraphEntry(MenuEntryAdded event) {
        if (!grandExchange.isOpen() || !event.getOption().equals("Examine")) {
            return false;
        }
        int widgetId = event.getActionParam1();
        Widget geInventoryWidget = client.getWidget(467, 0);
        if (geInventoryWidget != null && geInventoryWidget.getId() == widgetId) {
            return true;
        }
        Widget inventoryWidget = client.getWidget(149, 0);
        return inventoryWidget != null && inventoryWidget.getId() == widgetId;
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
        Widget bankInventoryWidget = client.getWidget(BANK_INVENTORY_WIDGET_GROUP, BANK_INVENTORY_WIDGET_CHILD);
        if (bankInventoryWidget != null && bankInventoryWidget.getId() == widgetId) {
            return MenuLocation.INVENTORY;
        }
        for (int childId : BANK_ITEM_CONTAINER_CHILDREN) {
            Widget bankItemWidget = client.getWidget(BANK_WIDGET_GROUP, childId);
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
        ItemComposition itemComposition = client.getItemDefinition(unnotedItemId);
        String itemName = itemComposition.getName();
        String menuTarget = resolveMenuTarget(event.getTarget(), unnotedItemId);
        return new InventoryMenuItem(unnotedItemId, itemName, menuTarget, location);
    }

    private static class InventoryMenuItem {
        private final int unnotedItemId;
        private final String itemName;
        private final String menuTarget;
        private final MenuLocation location;

        private InventoryMenuItem(int unnotedItemId, String itemName, String menuTarget, MenuLocation location) {
            this.unnotedItemId = unnotedItemId;
            this.itemName = itemName;
            this.menuTarget = menuTarget;
            this.location = location;
        }
    }

    private boolean isGeTradableItem(int itemId) {
        ItemComposition item = client.getItemDefinition(itemId);
        if (item.isGeTradeable()) {
            return true;
        }
        if (item.getNote() != -1) {
            int unnotedItemId = item.getLinkedNoteId();
            if (unnotedItemId > 0) {
                ItemComposition unnoted = client.getItemDefinition(unnotedItemId);
                return unnoted.isGeTradeable();
            }
        }
        return false;
    }

    private String resolveMenuTarget(String eventTarget, int itemId) {
        if (eventTarget != null && !eventTarget.isBlank()) {
            return eventTarget;
        }
        ItemComposition item = client.getItemDefinition(itemId);
        return "<col=ff9040>" + item.getName() + "</col>";
    }

    private int toUnnotedItemId(int itemId) {
        ItemComposition item = client.getItemDefinition(itemId);
        if (item.getNote() != -1 && item.getLinkedNoteId() > 0) {
            return item.getLinkedNoteId();
        }
        return itemId;
    }

    private PriceLine buildPriceLine(GrandExchangeOffer offer) {
        switch (offer.getState()) {
            case BOUGHT:
            case BUYING:
                return new PriceLine(
                        offer.getPrice(),
                        "offer buy price",
                        false
                );
            case SOLD :
            case SELLING:
                return new PriceLine(
                        offer.getPrice(),
                        "offer sell price",
                        true
                );
        }
        return null;
    }

    public void injectConfirmMenuEntry(MenuEntryAdded event) {
        if(!config.disableLeftClickConfirm()) {
            return;
        }

        if (!grandExchange.isOpen()) {
            return;
        }

        if(offerDetailsCorrect()) {
            return;
        }

        if(event.getOption().equals("Confirm") && grandExchange.isSlotOpen()) {
            log.debug("Adding deprioritized menu entry for offer");
            client.getMenu()
                    .createMenuEntry(-1)
                    .setOption("Nothing");

            event.getMenuEntry().setDeprioritized(true);
        }
    }

    public void injectSlotActionSwapMenuEntry(MenuEntryAdded event) {
        if (!config.slotActionSwap()) {
            return;
        }

        if (!grandExchange.isOpen() || grandExchange.isSlotOpen()) {
            return;
        }

        Suggestion suggestion = suggestionManager.getSuggestion();
        if (suggestion == null) {
            return;
        }

        if (!suggestion.isAbortSuggestion() && !suggestion.isModifySuggestion()) {
            return;
        }

        int slot = grandExchange.slotForSuggestion(suggestion);
        if (slot < 0) {
            return;
        }
        Widget slotWidget = grandExchange.getSlotWidget(slot);
        if (slotWidget == null || slotWidget.getId() != event.getActionParam1()) {
            return;
        }

        String target = suggestion.isAbortSuggestion() ? "Abort offer" : "Modify offer";
        if (! event.getOption().equals(target)) {
            event.getMenuEntry().setDeprioritized(true);
        }
    }

    private boolean offerDetailsCorrect() {
        Suggestion suggestion = suggestionManager.getSuggestion();
        if (suggestion == null) {
            return false;
        }
        String offerType = grandExchange.isOfferTypeSell() ? "sell" : "buy";
        int editorItem = editorItemId();
        if (editorItem == suggestion.getItemId() && offerType.equals(suggestion.offerType())) {
            return grandExchange.getOfferPrice() == suggestion.getPrice()
                    && grandExchange.getOfferQuantity() == suggestion.getQuantity();
        } else if (editorItem == offerManager.getViewedSlotItemId()
                && offerManager.getViewedSlotItemPrice() > 0) {
            return grandExchange.getOfferPrice() == offerManager.getViewedSlotItemPrice();
        }
        return false;
    }

    /** Modify opens Set up offer without search, so TRADINGPOST_SEARCH is often -1. */
    private int editorItemId() {
        int current = grandExchange.getCurrentItemId();
        if (current > 0) {
            return current;
        }
        return client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
    }
}
