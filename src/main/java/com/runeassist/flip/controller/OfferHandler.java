package com.runeassist.flip.controller;

import com.runeassist.flip.model.*;
import com.runeassist.flip.rs.CopilotLoginRS;
import com.runeassist.flip.ui.OfferEditor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static net.runelite.api.VarPlayer.CURRENT_GE_ITEM;

@Slf4j
@Getter
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OfferHandler {

    private static final int GE_OFFER_INIT_STATE_CHILD_ID = 20;

    // dependencies
    private final Client client;
    private final ClientThread clientThread;
    private final SuggestionManager suggestionManager;
    private final ApiRequestHandler apiRequestHandler;
    private final OsrsLoginManager osrsLoginManager;
    private final OfferManager offerManager;
    private final HighlightController highlightController;
    private final CopilotLoginRS copilotLoginRS;
    private final com.runeassist.flip.FlipScorer flipScorer;

    // state
    private String viewedSlotPriceErrorText = null;

    public void fetchSlotItemPrice(boolean isViewingSlot, Supplier<OfferEditor> offerEditorSupplier) {
        if (isViewingSlot) {
            var currentItemId = client.getVarpValue(CURRENT_GE_ITEM);
            offerManager.setViewedSlotItemId(currentItemId);
            if (currentItemId == -1 || currentItemId == 0) return;

            var suggestion = suggestionManager.getSuggestion();
            if (suggestion != null && suggestion.getItemId() == currentItemId &&
                    Objects.equals(suggestion.offerType(), getOfferType())) {
                offerManager.setViewedSlotItemPrice(suggestion.getPrice());
                offerManager.setLastViewedSlotItemId(suggestion.getItemId());
                offerManager.setLastViewedSlotItemPrice(suggestion.getPrice());
                offerManager.setLastViewedSlotPriceTime((int) Instant.now().getEpochSecond());
                return;
            }

            // RuneAssist fork: price an item the suggestion engine didn't propose from our
            // own wiki-based FlipScorer instead of FC's (removed) backend. Blocks on HTTP,
            // so run it off the client thread and marshal the result back.
            viewedSlotPriceErrorText = "Loading price...";
            final int itemIdForQuote = currentItemId;
            new Thread(() -> {
                Map<String, Object> q;
                try { q = flipScorer.quote(itemIdForQuote); } catch (Exception e) { q = null; }
                final Map<String, Object> fq = q;
                clientThread.invoke(() -> {
                    if (fq == null) {
                        viewedSlotPriceErrorText = "No price data for this item.";
                        return;
                    }
                    viewedSlotPriceErrorText = null;
                    long price = isSelling()
                        ? ((Number) fq.get("sell_at")).longValue()
                        : ((Number) fq.get("buy_at")).longValue();
                    offerManager.setViewedSlotItemPrice(price);
                    offerManager.setLastViewedSlotItemId(offerManager.getViewedSlotItemId());
                    offerManager.setLastViewedSlotItemPrice(price);
                    offerManager.setLastViewedSlotPriceTime((int) Instant.now().getEpochSecond());

                    highlightController.redraw();
                    log.debug("fetched item {} price: {}", offerManager.getViewedSlotItemId(), price);

                    // todo: Usage of OfferEditor is messy. It mutates a widget so we need to get the original instance
                    //  of it which is created downstream on some other event handler path. This is why we use a supplier
                    //  but probably it should be an injected class of some kind. We should clean this up in the future
                    //  but for now just need it to work as currently broken.

                    OfferEditor flippingWidget = offerEditorSupplier.get();
                    if (flippingWidget != null) {
                        flippingWidget.showPrice(price);
                    }
                });
            }, "runeassist-item-quote").start();

        } else {
            // RuneAssist fork: do NOT clear viewedSlotItemId/Price here. TRADINGPOST_SEARCH
            // (isViewingSlot's source) goes back to -1 as soon as an item is picked from
            // search -- i.e. BEFORE the user reaches the quantity/price screen -- so clearing
            // here wiped out the just-fetched custom-item quote before the quick-set keybind
            // could ever use it (the on-screen hint text is a separate game widget that FC
            // set once and stays displayed, so it kept showing the stale price while the
            // keybind silently set nothing). The cached quote is naturally replaced the next
            // time isViewingSlot is true for a different item, so nothing goes stale here.
            viewedSlotPriceErrorText = null;
        }
        highlightController.redraw();
    }

    public boolean isSettingQuantity() {
        var chatboxTitleWidget = getChatboxTitleWidget();
        if (chatboxTitleWidget == null) return false;
        String chatInputText = chatboxTitleWidget.getText();
        return chatInputText.equals("How many do you wish to buy?") || chatInputText.equals("How many do you wish to sell?");
    }

    public boolean isSettingPrice() {
        var chatboxTitleWidget = getChatboxTitleWidget();
        if (chatboxTitleWidget == null) return false;
        String chatInputText = chatboxTitleWidget.getText();

        var offerTextWidget = getOfferTextWidget();
        if (offerTextWidget == null) return false;
        String offerText = offerTextWidget.getText();
        return chatInputText.equals("Set a price for each item:") && (offerText.equals("Buy offer") || offerText.equals("Sell offer"));
    }


    private Widget getChatboxTitleWidget() {
        return client.getWidget(ComponentID.CHATBOX_TITLE);
    }

    private Widget getOfferTextWidget() {
        var offerContainerWidget = client.getWidget(ComponentID.GRAND_EXCHANGE_OFFER_CONTAINER);
        if (offerContainerWidget == null) return null;
        return offerContainerWidget.getChild(GE_OFFER_INIT_STATE_CHILD_ID);
    }

    public boolean isSelling() {
        return client.getVarbitValue(Varbits.GE_OFFER_CREATION_TYPE) == 1;
    }

    public boolean isBuying() {
        return client.getVarbitValue(Varbits.GE_OFFER_CREATION_TYPE) == 0;
    }

    public String getOfferType() {
        if (isBuying()) {
            return "buy";
        } else if (isSelling()) {
            return "sell";
        } else {
            return null;
        }
    }

    public void setSuggestedAction(Suggestion suggestion) {
        var currentItemId = client.getVarpValue(CURRENT_GE_ITEM);

        if (isSettingQuantity()) {
            if (suggestion == null || currentItemId != suggestion.getItemId()) {
                return;
            }
            setChatboxValue(suggestion.getQuantity());
        } else if (isSettingPrice()) {
            long price = -1;
            if (suggestion == null || currentItemId != suggestion.getItemId()
                    || !Objects.equals(suggestion.offerType(), getOfferType())) {
                if (offerManager.getViewedSlotItemId() != currentItemId) {
                    return;
                }
                price = offerManager.getViewedSlotItemPrice();
            } else {
                price = suggestion.getPrice();
            }

            if (price == -1) return;

            setChatboxValue(price);
        }
    }

    public void setChatboxValue(long value) {
        var chatboxInputWidget = client.getWidget(ComponentID.CHATBOX_FULL_INPUT);
        if (chatboxInputWidget == null) return;
        chatboxInputWidget.setText(value + "*");
        client.setVarcStrValue(VarClientStr.INPUT_TEXT, String.valueOf(value));
    }
}
