package com.runeassist.flip.controller;

import com.runeassist.flip.model.GEOfferScreenSetupOfferState;
import com.runeassist.flip.model.Suggestion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.*;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Arrays;

import static net.runelite.api.VarPlayer.CURRENT_GE_ITEM;
import static net.runelite.api.Varbits.GE_OFFER_CREATION_TYPE;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class GrandExchange {
    private final Client client;

    boolean isHomeScreenOpen() {
        return isOpen() && !isSlotOpen();
    }

    public boolean isSlotOpen() {
        return getOpenSlot() != -1;
    }

    String getOfferType() {
        return client.getVarbitValue(GE_OFFER_CREATION_TYPE) == 1 ? "sell" : "buy";
    }

    /** Item currently in the Set up offer editor; -1 when none. */
    public int getCurrentItemId() {
        return client.getVarpValue(CURRENT_GE_ITEM);
    }

    boolean isCollectButtonVisible() {
        Widget w = client.getWidget(InterfaceID.GE_OFFERS, 6);
        if (w == null) {
            return false;
        }
        Widget[] children = w.getChildren();
        if(children == null) {
            return false;
        }
        return Arrays.stream(children).anyMatch(c -> !c.isHidden() && "Collect".equals(c.getText()));
    }

    public int getOpenSlot() {
        return client.getVarbitValue(VarbitID.GE_SELECTEDSLOT) - 1;
    }

    Widget getSlotWidget(int slot) {
        return client.getWidget(InterfaceID.GE_OFFERS, 7 + slot);
    }

    /**
     * Slot to highlight / left-click-swap for a suggestion. Prefer the live filling
     * offer whose itemId matches the card, not a stale boxId (wrong-item editor).
     */
    int slotForSuggestion(Suggestion suggestion) {
        if (suggestion == null) {
            return -1;
        }
        return slotMatchingItem(suggestion.getItemId(), suggestion.getBoxId());
    }

    int slotMatchingItem(int itemId, int fallbackSlot) {
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null || itemId <= 0) {
            return fallbackSlot;
        }
        if (fallbackSlot >= 0 && fallbackSlot < offers.length && isFillingOffer(offers[fallbackSlot])
                && offers[fallbackSlot].getItemId() == itemId) {
            return fallbackSlot;
        }
        for (int i = 0; i < offers.length; i++) {
            GrandExchangeOffer o = offers[i];
            if (isFillingOffer(o) && o.getItemId() == itemId) {
                return i;
            }
        }
        return fallbackSlot;
    }

    boolean hasFillingOffer(int itemId) {
        return slotMatchingItem(itemId, -1) >= 0;
    }

    private static boolean isFillingOffer(GrandExchangeOffer o) {
        if (o == null) {
            return false;
        }
        GrandExchangeOfferState st = o.getState();
        return st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.SELLING;
    }

    Widget getBuyButton(int slot) {
        Widget slotWidget = getSlotWidget(slot);
        if (slotWidget == null) {
            return null;
        }
        return slotWidget.getChild(0);
    }

    Widget getCollectButton() {
        Widget topBar = client.getWidget(InterfaceID.GE_OFFERS, 6);
        if (topBar == null) {
            return null;
        }
        return topBar.getChild(2);
    }

    Widget getOfferContainerWidget() {
        return client.getWidget(InterfaceID.GE_OFFERS, 26);
    }

    Widget getConfirmButton() {
        Widget offerContainer = getOfferContainerWidget();
        if (offerContainer == null) {
            return null;
        }
        return offerContainer.getChild(58);
    }

    int getOfferQuantity() {
        return client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY);
    }

    int getOfferPrice() {
        return client.getVarbitValue(VarbitID.GE_NEWOFFER_PRICE);
    }

    boolean isOfferTypeSell() {
        return client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 1;
    }

    public boolean isOpen() {
        return client.getWidget(InterfaceID.GE_OFFERS, 7) != null;
    }

    public boolean isPreviousSearchSet() {
        return client.getVarpValue(VarPlayerID.GE_LAST_SEARCHED) != -1;
    }

    public boolean showLastSearchEnabled() {
        return client.getVarbitValue(VarbitID.DISABLE_LAST_SEARCHED) == 0;
    }

    public Widget getSetQuantityButton() {
        Widget offerContainer = getOfferContainerWidget();
        if (offerContainer == null) {
            return null;
        }
        return offerContainer.getChild(51);
    }

    public Widget getSetPriceButton() {
        Widget offerContainer = getOfferContainerWidget();
        if (offerContainer == null) {
            return null;
        }
        return offerContainer.getChild(54);
    }

    public Widget getSetQuantityAllButton() {
        Widget offerContainer = getOfferContainerWidget();
        if (offerContainer == null) {
            return null;
        }
        return offerContainer.getChild(50);
    }

    public GEOfferScreenSetupOfferState getOfferScreenSetupOfferState() {
        if (!isSlotOpen() || !isSetupOfferOpen()) {
            return null;
        }
        return new GEOfferScreenSetupOfferState(
                getOfferType(),
                client.getVarpValue(CURRENT_GE_ITEM),
                getOfferPrice(),
                getOfferQuantity(),
                isSearchOpen());
    }

    Widget getBackButton() {
        return client.getWidget(InterfaceID.GE_OFFERS, 4);
    }

    public boolean isSetupOfferOpen() {
        Widget confirmButton = getConfirmButton();
        return confirmButton != null && !confirmButton.isHidden();
    }

    private boolean isSearchOpen() {
        Widget searchResults = client.getWidget(ComponentID.CHATBOX_GE_SEARCH_RESULTS);
        return searchResults != null && !searchResults.isHidden();
    }
}
