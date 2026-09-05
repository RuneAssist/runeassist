package com.runeassist.flip.controller;

import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.model.*;
import com.runeassist.flip.ui.NpcHighlightOverlay;
import com.runeassist.flip.ui.WidgetHighlightOverlay;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;



@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class HighlightController {
    private static final int BANK_WIDGET_GROUP = 12;
    private static final int[] BANK_ITEM_CONTAINER_CHILDREN = {12, 13, 89};
    private static final String PORTFOLIO_BANK_TAG = "portfolio";
    private static final int BANK_TAG_TAB_CHILD_OFFSET = 4;
    private static final int BANK_CLOSE_BUTTON_INDEX = 11;
    private static final int GE_CLOSE_BUTTON_INDEX = 11;
    private static final Rectangle CLOSE_BUTTON_HIGHLIGHT_BOUNDS = new Rectangle(2, 2, 19, 19);
    private static final String GE_CLERK_NAME = "Grand Exchange Clerk";
    private static final String BANKER_NAME = "Banker";
    private static final String BOB_BARTER_NAME = "Bob Barter";

    // dependencies
    private final RuneAssistConfig config;
    private final SuggestionManager suggestionManager;
    private final com.runeassist.flip.model.PausedManager pausedManager;
    private final SuggestionPreferencesManager suggestionPreferencesManager;
    private final GrandExchange grandExchange;
    private final AccountStatusManager accountStatusManager;
    private final Client client;
    private final OfferManager offerManager;
    private final OverlayManager overlayManager;
    private final HighlightColorController highlightColorController;
    private final PluginManager pluginManager;
    // Bank Tags resolved at runtime via BankTagsLookup (no hard inject / PluginDependency).
    private final ModelOutlineRenderer modelOutlineRenderer;

    // state
    private final ArrayList<Overlay> highlightOverlays = new ArrayList<>();
    private volatile boolean active = true;
    private final AtomicInteger generation = new AtomicInteger(0);

    public void activate() {
        active = true;
        generation.incrementAndGet();
    }

    public void deactivateAndRemoveAll() {
        active = false;
        final int clearGeneration = generation.incrementAndGet();
        Runnable clearTask = () -> clearOverlaysIfCurrentGeneration(clearGeneration);
        if (SwingUtilities.isEventDispatchThread()) {
            clearTask.run();
            return;
        }
        SwingUtilities.invokeLater(clearTask);
    }

    public void redraw() {
        if (!active) {
            return;
        }
        removeAll();
        if (!active) {
            return;
        }
        if(!config.suggestionHighlights()) {
            log.debug("highlight redraw: suggestionHighlights config is OFF");
            return;
        }
        // removeAll() above has already cleared, so returning here leaves nothing drawn. Without
        // this the last suggestion's highlights stayed on the GE while the panel said paused.
        if (pausedManager.isPaused()) {
            log.debug("highlight redraw: skipped, suggestions paused");
            return;
        }
        if (offerManager.isOfferJustPlaced()) {
            log.debug("highlight redraw: skipped, offer just placed");
            return;
        }
        Suggestion suggestion = suggestionManager.getSuggestion();
        if (suggestion == null) {
            log.debug("highlight redraw: skipped, suggestion is null");
            return;
        }
        if (suggestion.isDecantSuggestion()) {
            highlightDecant(suggestion);
            return;
        }
        AccountStatus accountStatus = accountStatusManager.getAccountStatus();
        boolean sellFromBank = accountStatus != null && accountStatus.shouldSellFromBank(suggestion);
        // When the user must collect first (e.g. no free slot for the sell-from-bank offer),
        // sending them to the bank is wrong — they have to go to the GE clerk first.
        boolean isCollectNeeded = accountStatus != null && accountStatus.isCollectNeeded(suggestion, grandExchange.isSetupOfferOpen());
        boolean goToBank = sellFromBank && !isCollectNeeded;
        log.debug("highlight redraw: type={} item={} geOpen={} homeScreen={} slotOpen={} bankOpen={} goToBank={} accountStatusNull={}",
                suggestion.getType(), suggestion.getItemId(), grandExchange.isOpen(),
                grandExchange.isHomeScreenOpen(), grandExchange.isSlotOpen(), isBankOpen(), goToBank,
                accountStatus == null);
        if (goToBank && grandExchange.isOpen() && highlightGrandExchangeCloseButton(suggestion)) {
            return;
        }
        if (!goToBank && isBankOpen() && !suggestion.isWaitSuggestion() && highlightBankCloseButton(suggestion)) {
            return;
        }
        if (goToBank && drawSellFromBankHighlight(suggestion, accountStatus)) {
            return;
        }
        if(!grandExchange.isOpen()) {
            if (!isBankOpen()) {
                highlightNpcAtGrandExchange(suggestion, accountStatus, goToBank);
            }
            return;
        }
        if (grandExchange.isHomeScreenOpen()) {
            boolean drew = drawHomeScreenHighLights(suggestion);
            log.debug("highlight redraw: drawHomeScreenHighLights returned {}", drew);
        } else if (grandExchange.isSlotOpen()) {
            drawOfferScreenHighlights(suggestion);
        } else {
            log.debug("highlight redraw: GE open but neither home screen nor slot screen detected");
        }
    }

    /**
     * Bob Barter (the GE's decanting NPC, SW corner) is reached via a right-click world
     * interaction, not a widget -- so the only automatable help is: get any open GE/bank
     * interface out of the way (same close-button highlight already used elsewhere), then
     * outline Bob himself once he's visible. No further automation is possible -- decanting
     * itself is his NPC dialogue, which RuneLite plugins must not script.
     */
    private void highlightDecant(Suggestion suggestion) {
        if (grandExchange.isOpen()) {
            highlightGrandExchangeCloseButton(suggestion);
            return;
        }
        if (isBankOpen()) {
            highlightBankCloseButton(suggestion);
            return;
        }
        NPC bob = findClosestNpcByName(BOB_BARTER_NAME);
        if (bob == null) {
            return;
        }
        boolean flashHighlight = suggestion.isBuyDumpSuggestion();
        addNpcHighlight(bob, () -> {
            Color base = highlightColorController.getBlueColor(flashHighlight);
            if (base == null) {
                return null;
            }
            return new Color(base.getRed(), base.getGreen(), base.getBlue(), Math.min(255, base.getAlpha() * 3));
        });
    }

    private void highlightNpcAtGrandExchange(Suggestion suggestion, AccountStatus accountStatus, boolean goToBank) {
        if (accountStatus == null) {
            return;
        }
        // Player is considered at the GE only if a clerk is loaded nearby
        NPC clerk = findClosestNpcByName(GE_CLERK_NAME);
        if (clerk == null) {
            return;
        }
        NPC target;
        if (goToBank) {
            target = findClosestNpcByName(BANKER_NAME);
        } else if (drawHomeScreenHighLights(suggestion)) {
            // Reuses the existing home-screen decision tree as the actionability check;
            // widget lookups inside no-op while the GE is closed
            target = clerk;
        } else {
            return;
        }
        if (target == null) {
            return;
        }
        boolean flashHighlight = suggestion.isBuyDumpSuggestion();
        addNpcHighlight(target, () -> {
            Color base = highlightColorController.getBlueColor(flashHighlight);
            if (base == null) {
                return null;
            }
            // Feathered outlines need a higher alpha than filled widget overlays to read as a glow
            return new Color(base.getRed(), base.getGreen(), base.getBlue(), Math.min(255, base.getAlpha() * 3));
        });
    }

    private NPC findClosestNpcByName(String name) {
        Player player = client.getLocalPlayer();
        if (player == null) {
            return null;
        }
        WorldPoint playerLocation = player.getWorldLocation();
        if (playerLocation == null) {
            return null;
        }
        NPC closest = null;
        int closestDistance = Integer.MAX_VALUE;
        for (NPC npc : client.getNpcs()) {
            if (npc == null || !name.equals(npc.getName())) {
                continue;
            }
            WorldPoint npcLocation = npc.getWorldLocation();
            if (npcLocation == null) {
                continue;
            }
            int distance = npcLocation.distanceTo(playerLocation);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = npc;
            }
        }
        return closest;
    }

    private void addNpcHighlight(NPC npc, Supplier<Color> colorSupplier) {
        if (!active || npc == null) {
            return;
        }
        final int addGeneration = generation.get();
        SwingUtilities.invokeLater(() -> {
            if (!active || generation.get() != addGeneration) {
                return;
            }
            NpcHighlightOverlay overlay = new NpcHighlightOverlay(npc, colorSupplier, modelOutlineRenderer);
            highlightOverlays.add(overlay);
            overlayManager.add(overlay);
        });
    }

    private boolean drawHomeScreenHighLights(Suggestion suggestion) {
        boolean flashHighlight = suggestion.isBuyDumpSuggestion();
        Supplier<Color> blueHighlight = () -> highlightColorController.getBlueColor(flashHighlight);
        Supplier<Color> redHighlight = () -> highlightColorController.getRedColor(flashHighlight);
        Supplier<Color> amberHighlight = () -> highlightColorController.getAmberColor(flashHighlight);
        AccountStatus accountStatus = accountStatusManager.getAccountStatus();
        if (accountStatus.isCollectNeeded(suggestion, grandExchange.isSetupOfferOpen())) {
            Widget collectButton = grandExchange.getCollectButton();
            if (collectButton != null) {
                add(collectButton, blueHighlight, new Rectangle(2, 1, 81, 18));
            }
            return true;
        }
        else if (suggestion.isAbortSuggestion()) {
            int slot = grandExchange.slotForSuggestion(suggestion);
            if (slot >= 0) {
                add(grandExchange.getSlotWidget(slot), redHighlight);
            }
            return true;
        }
        else if (suggestion.isModifySuggestion()) {
            int slot = grandExchange.slotForSuggestion(suggestion);
            if (slot >= 0) {
                Widget slotWidget = grandExchange.getSlotWidget(slot);
                if (slotWidget != null && !slotWidget.isHidden()) {
                    add(slotWidget, amberHighlight);
                }
            }
            return true;
        }
        else if (isScanningForDumpsSuggested(suggestion, accountStatus)) {
            highlightCreateBuyOfferButton(accountStatus, blueHighlight);
            return true;
        }
        else if (suggestion.isBuySuggestion()) {
            highlightCreateBuyOfferButton(accountStatus, blueHighlight);
            return true;
        }
        else if (suggestion.isSellSuggestion() && accountStatus.hasSufficientInventoryForSellSuggestion(suggestion)) {
            Widget geInvGroup = client.getWidget(InterfaceID.GE_OFFERS_SIDE, 0);
            Widget itemWidget = getInventoryItemWidget(suggestion.getItemId());
            log.debug("highlight sell branch: geInvGroupNull={} itemWidgetNull={} itemWidgetHidden={}",
                    geInvGroup == null, itemWidget == null,
                    itemWidget != null && itemWidget.isHidden());
            if (itemWidget != null && !itemWidget.isHidden()) {
                add(itemWidget, blueHighlight, new Rectangle(0, 0, 34, 32));
            }
            return true;
        }
        else if (suggestion.isSellSuggestion()) {
            log.debug("highlight sell branch: hasSufficientInventoryForSellSuggestion=false for item={}",
                    suggestion.getItemId());
        }
        return false;
    }

    private boolean drawSellFromBankHighlight(Suggestion suggestion, AccountStatus accountStatus) {
        boolean flashHighlight = suggestion.isBuyDumpSuggestion();
        Supplier<Color> blueHighlight = () -> highlightColorController.getBlueColor(flashHighlight);

        Widget bankItemWidget = getBankItemWidget(suggestion.getItemId());
        if (bankItemWidget != null && !bankItemWidget.isHidden()) {
            add(bankItemWidget, blueHighlight, new Rectangle(0, 0, 34, 32));
            return true;
        }

        Widget portfolioTagButton = getPortfolioBankTagButton();
        if (portfolioTagButton != null) {
            add(portfolioTagButton, blueHighlight);
            return true;
        }

        return false;
    }

    private boolean highlightGrandExchangeCloseButton(Suggestion suggestion) {
        return highlightCloseButton(getGrandExchangeCloseButton(), suggestion);
    }

    private boolean highlightBankCloseButton(Suggestion suggestion) {
        return highlightCloseButton(getBankCloseButton(), suggestion);
    }

    private boolean highlightCloseButton(Widget closeButton, Suggestion suggestion) {
        if (closeButton == null || closeButton.isHidden()) {
            return false;
        }

        boolean flashHighlight = suggestion.isBuyDumpSuggestion();
        Supplier<Color> blueHighlight = () -> highlightColorController.getBlueColor(flashHighlight);
        add(closeButton, blueHighlight, new Rectangle(CLOSE_BUTTON_HIGHLIGHT_BOUNDS));
        return true;
    }

    private boolean isScanningForDumpsSuggested(Suggestion suggestion, AccountStatus accountStatus) {
        return suggestion.isWaitSuggestion()
                && accountStatus.emptySlotExists()
                && !accountStatus.moreGpNeeded()
                && suggestionPreferencesManager.isReceiveDumpSuggestions();
    }

    private void highlightCreateBuyOfferButton(AccountStatus accountStatus, Supplier<Color> colorSupplier) {
        int slotId = accountStatus.findEmptySlot();
        if (slotId == -1) {
            return;
        }
        Widget buyButton = grandExchange.getBuyButton(slotId);
        if (buyButton != null && !buyButton.isHidden()) {
            add(buyButton, colorSupplier, new Rectangle(0, 0, 45, 44));
        }
    }

    private void drawOfferScreenHighlights(Suggestion suggestion) {
        boolean flashHighlight = suggestion.isBuyDumpSuggestion();
        Supplier<Color> blueHighlight = () -> highlightColorController.getBlueColor(flashHighlight);
        GEOfferScreenSetupOfferState s = grandExchange.getOfferScreenSetupOfferState();
        if (s == null) {
            return;
        }

        if (s.offerDetailsCorrect(suggestion)) {
            highlightConfirm(blueHighlight);
            return;
        }

        boolean offerTypeMatches = Objects.equals(s.offerType, suggestion.offerType());
        boolean itemMatches = s.currentItemId == suggestion.getItemId();

        // Prioritise certain buy dump alert cases
        if (suggestion.isBuyDumpSuggestion()) {
            if (!offerTypeMatches || accountStatusManager.getAccountStatus().isCollectNeeded(suggestion, grandExchange.isSetupOfferOpen())) {
                highlightBackButton(blueHighlight);
            } else if (!s.searchOpen && s.currentItemId != -1 && !itemMatches) {
                highlightItemSearchButton(blueHighlight);
            } else if (itemMatches) {
                if (s.offerPrice != suggestion.getPrice()) {
                    highlightPrice(blueHighlight);
                }
                highlightQuantity(suggestion, s.offerQuantity, blueHighlight);
            } else if (s.searchOpen) {
                highlightItemInSearch(suggestion, blueHighlight);
            }
            return;
        }

        // Custom item choice case — not while a MODIFY card is active (wrong slot
        // would look like a new BUY/SELL of the editor item).
        if (isCustomChoiceItem(s, offerTypeMatches, itemMatches) && !suggestion.isModifySuggestion()) {
            if (s.offerPrice == offerManager.getViewedSlotItemPrice()) {
                highlightConfirm(blueHighlight);
            } else {
                highlightPrice(blueHighlight);
            }
            return;
        }

        // Standard suggestion case
        if (offerTypeMatches && itemMatches) {
            if (s.offerPrice != suggestion.getPrice()) {
                highlightPrice(blueHighlight);
            }
            highlightQuantity(suggestion, s.offerQuantity, blueHighlight);
            return;
        }
        if (suggestion.isAbortSuggestion()
                || ((suggestion.isSellSuggestion() || suggestion.isModifySuggestion()) && s.isEmptyBuyState())) {
            highlightBackButton(blueHighlight);
            return;
        }
        if(offerTypeMatches && s.currentItemId == -1 && s.searchOpen) {
            highlightItemInSearch(suggestion,blueHighlight);
            return;
        }

    }

    private boolean isCustomChoiceItem(GEOfferScreenSetupOfferState s, boolean offerTypeMatches, boolean itemMatches) {
        return s.currentItemId != -1 && ((!offerTypeMatches && s.offerType.equals("sell"))|| (!s.searchOpen && !itemMatches))
                && s.currentItemId == offerManager.getViewedSlotItemId()
                && offerManager.getViewedSlotItemPrice() > -1;
    }

    private void highlightItemInSearch(Suggestion suggestion, Supplier<Color> colorSupplier) {
        if (!client.getVarcStrValue(VarClientStr.INPUT_TEXT).isEmpty()) {
            return;
        }
        Widget searchResults = client.getWidget(ComponentID.CHATBOX_GE_SEARCH_RESULTS);
        if (searchResults == null) {
            return;
        }
        for (Widget widget : searchResults.getDynamicChildren()) {
            if (widget.getName().equals("<col=ff9040>" + suggestion.getName() + "</col>")) {
                add(widget, colorSupplier);
                return;
            }
        }
        Widget itemWidget = searchResults.getChild(3);
        if (itemWidget != null && itemWidget.getItemId() == suggestion.getItemId()) {
            add(itemWidget, colorSupplier);
        }
    }


    private void highlightPrice(Supplier<Color> colorSupplier) {
        Widget setPriceButton = grandExchange.getSetPriceButton();
        if (setPriceButton != null) {
            add(setPriceButton, colorSupplier, new Rectangle(1, 6, 33, 23));
        }
    }

    private void highlightQuantity(Suggestion suggestion, int offerQuantity, Supplier<Color> colorSupplier) {
        AccountStatus accountStatus = accountStatusManager.getAccountStatus();
        if (offerQuantity != suggestion.getQuantity()) {
            Widget setQuantityButton;
            if (accountStatus.getInventory().getTotalAmount(suggestion.getItemId()) == suggestion.getQuantity()
                && suggestion.isSellSuggestion()) {
                setQuantityButton = grandExchange.getSetQuantityAllButton();
            } else {
                setQuantityButton = grandExchange.getSetQuantityButton();
            }
            if (setQuantityButton != null) {
                add(setQuantityButton, colorSupplier, new Rectangle(1, 6, 33, 23));
            }
        }
    }

    private void highlightConfirm(Supplier<Color> colorSupplier) {
        Widget confirmButton = grandExchange.getConfirmButton();
        if (confirmButton != null) {
            add(confirmButton, colorSupplier, new Rectangle(1, 1, 150, 38));
        }
    }

    private Widget getItemSearchButtonWidget() {
        Widget setupContainer = client.getWidget(InterfaceID.GE_OFFERS, 26);
        if (setupContainer == null) {
            return null;
        }
        Widget[] children = setupContainer.getChildren();
        if (children != null && children.length > 0) {
            return children[0];
        }
        return null;
    }

    private void highlightItemSearchButton(Supplier<Color> colorSupplier) {
        Widget searchWidget = getItemSearchButtonWidget();
        if (searchWidget != null) {
            add(searchWidget, colorSupplier);
        }
    }

    private void highlightBackButton(Supplier<Color> colorSupplier) {
        Widget backButton = grandExchange.getBackButton();
        if (backButton != null) {
            add(backButton, colorSupplier);
        }
    }

    private void add(Widget widget, Supplier<Color> colorSupplier, Rectangle adjustedBounds) {
        if (!active || widget == null) {
            return;
        }
        final int addGeneration = generation.get();
        SwingUtilities.invokeLater(() -> {
            if (!active || generation.get() != addGeneration) {
                return;
            }
            WidgetHighlightOverlay overlay = new WidgetHighlightOverlay(widget, colorSupplier, adjustedBounds);
            highlightOverlays.add(overlay);
            overlayManager.add(overlay);
        });
    }

    private void add(Widget widget, Supplier<Color> colorSupplier) {
        if (widget == null) {
            return;
        }
        add(widget, colorSupplier, new Rectangle(0, 0, widget.getWidth(), widget.getHeight()));
    }

    public void removeAll() {
        final int clearGeneration = generation.incrementAndGet();
        SwingUtilities.invokeLater(() -> {
            clearOverlaysIfCurrentGeneration(clearGeneration);
        });
    }

    private void clearOverlaysIfCurrentGeneration(int expectedGeneration) {
        if (generation.get() != expectedGeneration) {
            return;
        }
        highlightOverlays.forEach(overlayManager::remove);
        highlightOverlays.clear();
    }

    private Widget getInventoryItemWidget(int unnotedItemId) {
        // GE inventory side (InterfaceID.GE_OFFERS_SIDE / 467). Regular inventory
        // (149,0) is shown when the GE is closed; we do not highlight that.
        Widget inventory = client.getWidget(InterfaceID.GE_OFFERS_SIDE, 0);
        if (inventory == null) {
            return null;
        }

        Widget[] children = inventory.getDynamicChildren();
        if (children == null) {
            return null;
        }

        Widget notedWidget = null;
        Widget unnotedWidget = null;

        for (Widget widget : children) {
            if (widget == null || widget.isHidden() || widget.getItemQuantity() <= 0) {
                continue;
            }

            int itemId = widget.getItemId();
            if (itemId <= 0) {
                continue;
            }
            if (matchesItemId(itemId, unnotedItemId)) {
                ItemComposition itemComposition = client.getItemDefinition(itemId);
                if (itemComposition.getNote() != -1) {
                    notedWidget = widget;
                } else {
                    unnotedWidget = widget;
                }
            }
        }
        return notedWidget != null ? notedWidget : unnotedWidget;
    }

    private Widget getBankItemWidget(int unnotedItemId) {
        for (int childId : BANK_ITEM_CONTAINER_CHILDREN) {
            Widget bankItems = client.getWidget(BANK_WIDGET_GROUP, childId);
            if (bankItems == null || bankItems.isHidden()) {
                continue;
            }

            Widget itemWidget = getVisibleItemWidget(bankItems, unnotedItemId);
            if (itemWidget != null) {
                return itemWidget;
            }
        }
        return null;
    }

    private boolean isBankOpen() {
        Widget bank = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
        return bank != null && !bank.isHidden();
    }

    private Widget getBankCloseButton() {
        Widget frame = client.getWidget(InterfaceID.Bankmain.FRAME);
        if (frame == null || frame.getDynamicChildren() == null) {
            return null;
        }
        Widget[] children = frame.getDynamicChildren();
        if (children.length <= BANK_CLOSE_BUTTON_INDEX) {
            return null;
        }
        return children[BANK_CLOSE_BUTTON_INDEX];
    }

    private Widget getGrandExchangeCloseButton() {
        Widget frame = client.getWidget(InterfaceID.GeOffers.FRAME);
        if (frame == null || frame.getDynamicChildren() == null) {
            return null;
        }
        Widget[] children = frame.getDynamicChildren();
        if (children.length <= GE_CLOSE_BUTTON_INDEX) {
            return null;
        }
        return children[GE_CLOSE_BUTTON_INDEX];
    }

    private Widget getPortfolioBankTagButton() {
        if (!config.portfolioBankTag()) {
            return null;
        }
        net.runelite.client.plugins.banktags.BankTagsPlugin bankTagsPlugin =
                BankTagsLookup.findActive(pluginManager);
        if (bankTagsPlugin == null || PORTFOLIO_BANK_TAG.equals(bankTagsPlugin.getActiveTag())) {
            return null;
        }

        Widget parent = client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER);
        if (parent == null || parent.isHidden() || parent.getChildren() == null) {
            return null;
        }

        Widget[] children = parent.getChildren();
        for (int i = BANK_TAG_TAB_CHILD_OFFSET; i < children.length; i += 2) {
            Widget button = children[i];
            if (button == null || button.isHidden()) {
                continue;
            }

            String widgetName = button.getName();
            if (widgetName == null) {
                continue;
            }

            if (PORTFOLIO_BANK_TAG.equals(Text.removeTags(widgetName))) {
                return button;
            }
        }
        return null;
    }


    private Widget getVisibleItemWidget(Widget itemContainer, int unnotedItemId) {
        Widget[] children = itemContainer.getDynamicChildren();
        if (children == null) {
            return null;
        }

        Rectangle containerBounds = itemContainer.getBounds();
        boolean clipToContainerBounds = hasUsableBounds(containerBounds);
        for (Widget widget : children) {
            if (widget == null || widget.isHidden() || widget.getItemQuantity() <= 0) {
                continue;
            }

            if (!matchesItemId(widget.getItemId(), unnotedItemId)) {
                continue;
            }

            Rectangle bounds = widget.getBounds();
            if (clipToContainerBounds && (bounds == null || !containerBounds.intersects(bounds))) {
                continue;
            }

            return widget;
        }
        return null;
    }

    private boolean hasUsableBounds(Rectangle bounds) {
        return bounds != null && bounds.width > 0 && bounds.height > 0;
    }

    private boolean matchesItemId(int itemId, int unnotedItemId) {
        if (itemId == unnotedItemId) {
            return true;
        }
        if (itemId <= 0) {
            return false;
        }

        ItemComposition itemComposition = client.getItemDefinition(itemId);
        return itemComposition.getNote() != -1 && itemComposition.getLinkedNoteId() == unnotedItemId;
    }
}
