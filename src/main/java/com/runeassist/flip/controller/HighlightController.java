package com.runeassist.flip.controller;

import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.model.*;
import com.runeassist.flip.ui.NpcHighlightOverlay;
import com.runeassist.flip.ui.WidgetHighlightOverlay;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

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
    private static final Rectangle CLOSE_BUTTON_HIGHLIGHT_BOUNDS = new Rectangle(2, 2, 19, 19);
    private static final String GE_CLERK_NAME = "Grand Exchange Clerk";
    private static final String BANKER_NAME = "Banker";
    private static final String BOB_BARTER_NAME = "Bob Barter";

    private final RuneAssistConfig config;
    private final SuggestionManager suggestionManager;
    private final PausedManager pausedManager;
    private final SuggestionPreferencesManager suggestionPreferencesManager;
    private final GrandExchange grandExchange;
    private final AccountStatusManager accountStatusManager;
    private final Client client;
    private final OfferManager offerManager;
    private final OverlayManager overlayManager;
    private final HighlightColorController highlightColorController;
    private final PluginManager pluginManager;
    private final ModelOutlineRenderer modelOutlineRenderer;

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
        if (!config.suggestionHighlights()) {
            log.debug("highlight redraw: suggestionHighlights config is OFF");
            return;
        }
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
        boolean isCollectNeeded = accountStatus != null
                && accountStatus.isCollectNeeded(suggestion, grandExchange.isSetupOfferOpen());
        boolean goToBank = sellFromBank && !isCollectNeeded;
        log.debug("highlight redraw: type={} item={} geOpen={} homeScreen={} slotOpen={} bankOpen={} goToBank={} accountStatusNull={}",
                suggestion.getType(), suggestion.getItemId(), grandExchange.isOpen(),
                grandExchange.isHomeScreenOpen(), grandExchange.isSlotOpen(), BankWidgets.isBankOpen(client), goToBank,
                accountStatus == null);
        if (goToBank && grandExchange.isOpen() && highlightGrandExchangeCloseButton(suggestion)) {
            return;
        }
        if (!goToBank && BankWidgets.isBankOpen(client) && !suggestion.isWaitSuggestion()
                && highlightBankCloseButton(suggestion)) {
            return;
        }
        if (goToBank && drawSellFromBankHighlight(suggestion)) {
            return;
        }
        if (!grandExchange.isOpen()) {
            if (!BankWidgets.isBankOpen(client)) {
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

    private void highlightDecant(Suggestion suggestion) {
        if (grandExchange.isOpen()) {
            highlightGrandExchangeCloseButton(suggestion);
            return;
        }
        if (BankWidgets.isBankOpen(client)) {
            highlightBankCloseButton(suggestion);
            return;
        }
        NPC bob = findClosestNpcByName(BOB_BARTER_NAME);
        if (bob == null) {
            return;
        }
        addNpcHighlight(bob, glowBlue(suggestion.isBuyDumpSuggestion()));
    }

    private void highlightNpcAtGrandExchange(Suggestion suggestion, AccountStatus accountStatus, boolean goToBank) {
        if (accountStatus == null) {
            return;
        }
        NPC clerk = findClosestNpcByName(GE_CLERK_NAME);
        if (clerk == null) {
            return;
        }
        NPC target;
        if (goToBank) {
            target = findClosestNpcByName(BANKER_NAME);
        } else if (drawHomeScreenHighLights(suggestion)) {
            target = clerk;
        } else {
            return;
        }
        if (target == null) {
            return;
        }
        addNpcHighlight(target, glowBlue(suggestion.isBuyDumpSuggestion()));
    }

    private Supplier<Color> glowBlue(boolean flash) {
        return () -> {
            Color base = highlightColorController.getBlueColor(flash);
            if (base == null) {
                return null;
            }
            return new Color(base.getRed(), base.getGreen(), base.getBlue(), Math.min(255, base.getAlpha() * 3));
        };
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
        if (suggestion.isAbortSuggestion()) {
            int slot = grandExchange.slotForSuggestion(suggestion);
            if (slot >= 0) {
                add(grandExchange.getSlotWidget(slot), redHighlight);
            }
            return true;
        }
        if (suggestion.isModifySuggestion()) {
            int slot = grandExchange.slotForSuggestion(suggestion);
            if (slot >= 0) {
                Widget slotWidget = grandExchange.getSlotWidget(slot);
                if (slotWidget != null && !slotWidget.isHidden()) {
                    add(slotWidget, amberHighlight);
                }
            }
            return true;
        }
        if (isScanningForDumpsSuggested(suggestion, accountStatus) || suggestion.isBuySuggestion()) {
            highlightCreateBuyOfferButton(accountStatus, blueHighlight);
            return true;
        }
        if (suggestion.isSellSuggestion() && accountStatus.hasSufficientInventoryForSellSuggestion(suggestion)) {
            Widget itemWidget = BankWidgets.geInventoryItem(client, suggestion.getItemId());
            log.debug("highlight sell branch: itemWidgetNull={} itemWidgetHidden={}",
                    itemWidget == null, itemWidget != null && itemWidget.isHidden());
            if (itemWidget != null && !itemWidget.isHidden()) {
                add(itemWidget, blueHighlight, new Rectangle(0, 0, 34, 32));
            }
            return true;
        }
        if (suggestion.isSellSuggestion()) {
            log.debug("highlight sell branch: hasSufficientInventoryForSellSuggestion=false for item={}",
                    suggestion.getItemId());
        }
        return false;
    }

    private boolean drawSellFromBankHighlight(Suggestion suggestion) {
        Supplier<Color> blueHighlight = () -> highlightColorController.getBlueColor(suggestion.isBuyDumpSuggestion());
        Widget bankItemWidget = BankWidgets.bankItem(client, suggestion.getItemId());
        if (bankItemWidget != null && !bankItemWidget.isHidden()) {
            add(bankItemWidget, blueHighlight, new Rectangle(0, 0, 34, 32));
            return true;
        }
        Widget portfolioTagButton = BankWidgets.portfolioTagButton(client, pluginManager, config.portfolioBankTag());
        if (portfolioTagButton != null) {
            add(portfolioTagButton, blueHighlight);
            return true;
        }
        return false;
    }

    private boolean highlightGrandExchangeCloseButton(Suggestion suggestion) {
        return highlightCloseButton(BankWidgets.geCloseButton(client), suggestion);
    }

    private boolean highlightBankCloseButton(Suggestion suggestion) {
        return highlightCloseButton(BankWidgets.bankCloseButton(client), suggestion);
    }

    private boolean highlightCloseButton(Widget closeButton, Suggestion suggestion) {
        if (closeButton == null || closeButton.isHidden()) {
            return false;
        }
        Supplier<Color> blueHighlight = () -> highlightColorController.getBlueColor(suggestion.isBuyDumpSuggestion());
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

        if (isCustomChoiceItem(s, offerTypeMatches, itemMatches) && !suggestion.isModifySuggestion()) {
            if (s.offerPrice == offerManager.getViewedSlotItemPrice()) {
                highlightConfirm(blueHighlight);
            } else {
                highlightPrice(blueHighlight);
            }
            return;
        }

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
        if (offerTypeMatches && s.currentItemId == -1 && s.searchOpen) {
            highlightItemInSearch(suggestion, blueHighlight);
        }
    }

    private boolean isCustomChoiceItem(GEOfferScreenSetupOfferState s, boolean offerTypeMatches, boolean itemMatches) {
        return s.currentItemId != -1 && ((!offerTypeMatches && s.offerType.equals("sell")) || (!s.searchOpen && !itemMatches))
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

    private void highlightItemSearchButton(Supplier<Color> colorSupplier) {
        Widget searchWidget = BankWidgets.geItemSearchButton(client);
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
        SwingUtilities.invokeLater(() -> clearOverlaysIfCurrentGeneration(clearGeneration));
    }

    private void clearOverlaysIfCurrentGeneration(int expectedGeneration) {
        if (generation.get() != expectedGeneration) {
            return;
        }
        highlightOverlays.forEach(overlayManager::remove);
        highlightOverlays.clear();
    }
}
