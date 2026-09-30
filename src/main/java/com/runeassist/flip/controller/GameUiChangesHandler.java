package com.runeassist.flip.controller;

import com.runeassist.flip.config.RuneAssistConfig;
import com.runeassist.flip.model.*;
import com.runeassist.flip.rs.HeldItemSyncStateRS;
import com.runeassist.flip.ui.OfferEditor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.api.widgets.*;
import net.runelite.client.callback.ClientThread;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.gameval.InterfaceID;


@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class GameUiChangesHandler {
    private static final int SCRIPT_GE_COLLECT = 782;
    private static final int MESLAYER_MODE_ITEM_SEARCH = 14;
    /** Extra search logging in a development client (the launcher sets this flag). */
    private static final boolean DEV = Boolean.getBoolean(com.runeassist.flip.HubPluginConflict.ALLOW_PROPERTY);
    private static final int SCRIPT_GE_SLOT_REDRAW = 804;
    private static final String BANK_TAG_TAB_VIEW_OPTION = "View tag tab";
    // Bank tag tab widgets exist before bank item bounds settle.
    // Wait one full frame before resolving the highlight target.
    private static final int BANK_REBUILD_HIGHLIGHT_REDRAW_DELAY_FRAMES = 2;

    // dependencies
    private final ClientThread clientThread;
    private final Client client;
    private final GePreviousSearch gePreviousSearch;
    private final HighlightController highlightController;
    private final SuggestionManager suggestionManager;
    private final GrandExchange grandExchange;
    private final OfferManager offerManager;
    private final OfferHandler offerHandler;
    private final SlotProfitColorizer slotProfitColorizer;
    private final HeldItemSyncStateRS heldItemSyncStateRS;
    private final RuneAssistConfig config;
    private final AccountStatusManager accountStatusManager;
    private final net.runelite.client.plugins.PluginManager pluginManager;
    private final FlipHistorySyncService flipHistorySyncService;
    // state
    boolean quantityOrPriceChatboxOpen;
    boolean itemSearchChatboxOpen = false;
    int bankRebuildHighlightRedrawFramesRemaining = 0;
    long lastOfferPrice = 0L;
    @Getter
    OfferEditor flippingWidget = null;

    public void onVarClientIntChanged(VarClientIntChanged event) {
        if (event.getIndex() == VarClientID.CHAT_LASTREBUILD) {
            // this is triggered when a bank tag tab is opened/closed
            requestBankRebuildHighlightRedraw();
        }

        // The item search is chat input mode 14 (still, after the 30 Sep 2026 GE update; the
        // chatbox title widget keeps the previous prompt's text, so it cannot identify it).
        // The results list is built by the game after the mode changes, wiping anything
        // placed early, so the placing happens when its build script fires (onScriptPostFired).
        if (event.getIndex() == VarClientID.MESLAYERMODE
                && client.getVarcIntValue(VarClientID.MESLAYERMODE) == MESLAYER_MODE_ITEM_SEARCH) {
            itemSearchChatboxOpen = true;
            clientThread.invokeLater(this::showCardItemInSearchIfOpen);
        }

        if (quantityOrPriceChatboxOpen
                && event.getIndex() == VarClientID.MESLAYERMODE
                && client.getVarcIntValue(VarClientID.MESLAYERMODE) == 0
        ) {
            quantityOrPriceChatboxOpen = false;
            return;
        }

        if (itemSearchChatboxOpen
                && event.getIndex() == VarClientID.MESLAYERMODE
                && client.getVarcIntValue(VarClientID.MESLAYERMODE) == 0
        ) {
            clientThread.invokeLater(highlightController::redraw);
            itemSearchChatboxOpen = false;
            return;
        }

        if (itemSearchChatboxOpen) {
            return;
        }

        // Check that it was a chat input that got enabled while the offer setup is showing.
        // The quantity prompt is input mode 7; since the 30 Sep 2026 GE update the price
        // prompt takes figures beyond max cash and arrives in a mode of its own, so the
        // prompt text decides rather than the mode number.
        int mode = client.getVarcIntValue(VarClientID.MESLAYERMODE);
        if (event.getIndex() != VarClientID.MESLAYERMODE
                || mode == 0
                || client.getWidget(ComponentID.CHATBOX_TITLE) == null
                || client.getWidget(ComponentID.GRAND_EXCHANGE_OFFER_CONTAINER) == null) {
            return;
        }

        clientThread.invokeLater(() ->
        {
            if (!offerHandler.isSettingPrice() && !offerHandler.isSettingQuantity()) {
                log.debug("chatbox mode {} opened over the offer setup but is not a price or quantity prompt", mode);
                return;
            }
            quantityOrPriceChatboxOpen = true;
            // A competing Hub flipping plugin may inject its own "Press [X] to set to Y gp" text
            // into this exact same chatbox widget -- if both plugins are enabled at once (see
            // HubPluginConflict's own doc comment), creating ours too makes the two overlap
            // into unreadable garbled text rather than either one working. Go fully quiet here,
            // matching RuneAssistSuggestionSource's own suppression of its suggestion output.
            if (com.runeassist.flip.HubPluginConflict.isEnabled(pluginManager)) {
                return;
            }
            flippingWidget = new OfferEditor(offerManager, client.getWidget(ComponentID.CHATBOX_CONTAINER), offerHandler, client, config);
            Suggestion suggestion = suggestionManager.getSuggestion();
            if (suggestion != null) {
                flippingWidget.showSuggestion(suggestion);
            }
        });
    }

    /**
     * Client thread. Puts the card's item at the top of the item search while it is open
     * with nothing typed yet. Safe to call again: an existing row is updated, not doubled.
     */
    private void showCardItemInSearchIfOpen() {
        Widget results = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
        int mode = client.getVarcIntValue(VarClientID.MESLAYERMODE);
        String typed = client.getVarcStrValue(VarClientStr.INPUT_TEXT);
        if (DEV) {
            Widget[] kids = results == null ? null : results.getDynamicChildren();
            log.info("search check: mode={} results={} typed='{}' lastSearched={} card={}", mode,
                    results == null ? "null" : (results.isHidden() ? "hidden" : "kids=" + (kids == null ? -1 : kids.length)),
                    typed, client.getVarpValue(VarPlayerID.GE_LAST_SEARCHED),
                    suggestionManager.getSuggestion() == null ? null : suggestionManager.getSuggestion().getType());
        }
        if (mode != MESLAYER_MODE_ITEM_SEARCH || results == null || results.isHidden()
                || (typed != null && !typed.isEmpty())) {
            return;
        }
        try {
            gePreviousSearch.showSuggestedItemInSearch();
        } catch (RuntimeException e) {
            log.warn("could not place the card's item in the search", e);
        }
    }

    public void onVarClientStrChanged(VarClientStrChanged event) {
        if (event.getIndex() == VarClientID.MESLAYERINPUT && itemSearchChatboxOpen) {
            clientThread.invokeLater(highlightController::redraw);
        }
    }

    public void onWidgetLoaded(WidgetLoaded event) {
        if (event.getGroupId() == InterfaceID.GE_OFFERS) {
            if (!accountStatusManager.isOwnedModifyActive() || !grandExchange.isSlotOpen()) {
                suggestionManager.setSuggestionNeeded(true);
            }
            clientThread.invokeLater(slotProfitColorizer::updateAllSlots);
        }
        // 467 (GE_OFFERS_SIDE) loads after 465. Redrawing only on the main GE
        // window left SELL highlights looking at a null inventory widget (runes
        // never outlined even when the suggestion was already SELL). Collect /
        // inventory / bank-pin are the other GE-adjacent interfaces that change
        // which widgets are targetable.
        if (event.getGroupId() == InterfaceID.GE_HISTORY
                || event.getGroupId() == InterfaceID.GE_OFFERS
                || event.getGroupId() == InterfaceID.GE_OFFERS_SIDE
                || event.getGroupId() == InterfaceID.GE_COLLECT
                || event.getGroupId() == InterfaceID.INVENTORY
                || event.getGroupId() == InterfaceID.BANKPIN_KEYPAD) {
            clientThread.invokeLater(highlightController::redraw);
        }
        if (event.getGroupId() == InterfaceID.BANKMAIN) {
            requestBankRebuildHighlightRedraw();
        }
    }

    public void onWidgetClosed(WidgetClosed event) {
        if (event.getGroupId() == InterfaceID.GE_OFFERS) {
            clientThread.invokeLater(highlightController::removeAll);
            // GE_OFFERS reloads when opening the slot editor for modify. Clearing
            // the lock here dropped the MODIFY card (panel → "Getting the next
            // flip…", no price highlight). releaseStaleOwnedModify on tick
            // drops the lock when the editor is actually gone.
        }
        if (event.getGroupId() == InterfaceID.BANKMAIN) {
            clientThread.invokeLater(highlightController::redraw);
        }
    }

    public void onVarbitChanged(VarbitChanged event) {
        if (event.getVarbitId() == VarbitID.GE_SELECTEDSLOT) {
            int open = grandExchange.getOpenSlot();
            Suggestion suggestion = suggestionManager.getSuggestion();
            if (open >= 0 && suggestion != null && suggestion.isModifySuggestion()
                    && suggestion.actionedTick == -1
                    && slotIsForModify(open, suggestion)) {
                accountStatusManager.beginOwnedModify(suggestion, open);
            } else if (accountStatusManager.isOwnedModifyActive()
                    && (open < 0 || !slotIsForModify(open, suggestion))) {
                accountStatusManager.clearOwnedModify();
                suggestionManager.setSuggestionNeeded(true);
            }
        }

        if (event.getVarpId() == 375
                || event.getVarpId() == VarPlayerID.TRADINGPOST_SEARCH
                || event.getVarbitId() == VarbitID.GE_NEWOFFER_QUANTITY
                || event.getVarpId() == GrandExchange.OFFER_SETUP_PRICE_VARP
                || event.getVarbitId() == VarbitID.GE_SELECTEDSLOT) {
            clientThread.invokeLater(highlightController::redraw);
        }

        if (event.getVarpId() == VarPlayerID.TRADINGPOST_SEARCH) {
            clientThread.invokeLater(() -> offerHandler.fetchSlotItemPrice(event.getValue() > -1, this::getFlippingWidget));
        }
    }

    public void handleMenuOptionClicked(MenuOptionClicked event) {
        if (event.getMenuOption().equals("Confirm") && grandExchange.isSlotOpen()) {
            log.debug("offer confirmed tick {}", client.getTickCount());
            accountStatusManager.clearOwnedModify();
            heldItemSyncStateRS.delayForTicks(client.getTickCount(), 3);
            offerManager.setOfferJustPlaced(true);
            suggestionManager.setLastOfferSubmittedTick(client.getTickCount());
            suggestionManager.setSuggestionNeeded(true);
            Suggestion suggestion = suggestionManager.getSuggestion();
            if(suggestion != null) {
                suggestion.actionedTick = client.getTickCount();
                suggestionManager.recordOfferSubmission(suggestion, client.getTickCount());
                flipHistorySyncService.reportSuggestionOutcome(suggestion, "acted");
            }
        }
        if (BANK_TAG_TAB_VIEW_OPTION.equals(event.getMenuOption())) {
            requestBankRebuildHighlightRedraw();
        }
    }

    /** True when the open GE slot is this MODIFY: owned/clicked slot, editor item, */
    private boolean slotIsForModify(int open, Suggestion suggestion) {
        if (open < 0) {
            return false;
        }
        int currentItem = grandExchange.getCurrentItemId();
        AccountStatusManager.OwnedModify owned = accountStatusManager.getOwnedModify();
        if (owned != null && owned.itemId > 0) {
            if (ModifyStep.editorMatches(open, currentItem, owned.itemId, owned.slot)) {
                return true;
            }
            if (liveOfferItemId(open) == owned.itemId) {
                return true;
            }
        }
        if (suggestion == null || !suggestion.isModifySuggestion()) {
            return false;
        }
        if (ModifyStep.editorMatches(open, currentItem, suggestion.getItemId(), suggestion.getBoxId())) {
            return true;
        }
        return liveOfferItemId(open) == suggestion.getItemId();
    }

    private int liveOfferItemId(int slot) {
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null || slot < 0 || slot >= offers.length || offers[slot] == null) {
            return -1;
        }
        return offers[slot].getItemId();
    }


    public void onScriptPostFired(ScriptPostFired event) {
        if (event.getScriptId() == SCRIPT_GE_COLLECT || event.getScriptId() == SCRIPT_GE_SLOT_REDRAW) {
            clientThread.invokeLater(slotProfitColorizer::updateAllSlots);
        }
        if (event.getScriptId() == ScriptID.GE_ITEM_SEARCH) {
            showCardItemInSearchIfOpen();
        }
        if (event.getScriptId() == ScriptID.BANKMAIN_FINISHBUILDING) {
            requestBankRebuildHighlightRedraw();
        }
    }

    public void onBeforeRender(BeforeRender event) {
        if (bankRebuildHighlightRedrawFramesRemaining > 0) {
            bankRebuildHighlightRedrawFramesRemaining--;
            if (bankRebuildHighlightRedrawFramesRemaining == 0) {
                highlightController.redraw();
            }
        }
        if (grandExchange.isOpen()) {
            slotProfitColorizer.updateAllSlots();
            // The typed price no longer arrives as a varbit change; watch the price box instead.
            long offerPrice = grandExchange.getOfferPrice();
            if (offerPrice != lastOfferPrice) {
                lastOfferPrice = offerPrice;
                highlightController.redraw();
            }
        }
    }

    private void requestBankRebuildHighlightRedraw() {
        bankRebuildHighlightRedrawFramesRemaining = BANK_REBUILD_HIGHLIGHT_REDRAW_DELAY_FRAMES;
    }
}
