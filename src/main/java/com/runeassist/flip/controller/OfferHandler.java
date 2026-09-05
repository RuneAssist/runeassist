package com.runeassist.flip.controller;

import com.runeassist.flip.model.*;
import com.runeassist.flip.rs.AccountLoginRS;
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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
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
    private final OsrsLoginManager osrsLoginManager;
    private final OfferManager offerManager;
    private final HighlightController highlightController;
    private final AccountLoginRS accountLoginRS;
    private final com.runeassist.flip.AresMarketClient market;
    private final ExecutorService executorService;

    // state
    private String viewedSlotPriceErrorText = null;

    public void fetchSlotItemPrice(boolean isViewingSlot, Supplier<OfferEditor> offerEditorSupplier) {
        if (isViewingSlot) {
            var currentItemId = client.getVarpValue(CURRENT_GE_ITEM);
            offerManager.setViewedSlotItemId(currentItemId);
            if (currentItemId == -1 || currentItemId == 0) return;

            var suggestion = suggestionManager.getSuggestion();
            if (suggestion != null && suggestion.isModifySuggestion()
                    && suggestion.getItemId() != currentItemId) {
                // Offer editor is on a different item than the MODIFY card — do not
                // treat it as a custom quote (that looks like a new BUY/SELL).
                viewedSlotPriceErrorText = null;
                highlightController.redraw();
                return;
            }
            if (suggestion != null && suggestion.getItemId() == currentItemId &&
                    Objects.equals(suggestion.offerType(), getOfferType())) {
                offerManager.setViewedSlotItemPrice(suggestion.getPrice());
                return;
            }

            // Price an item the suggestion engine didn't propose via Ares /v1/market/quote.
            // Blocks on HTTP, so run off the client thread and marshal the result back.
            // Quote may include a wiki-feature hint in "message" (freshness / confidence) —
            // same offer-editor slot FC fills from ItemPrice.message, without a closed quant.
            viewedSlotPriceErrorText = "Loading price...";
            final int itemIdForQuote = currentItemId;
            executorService.execute(() -> {
                Map<String, Object> q;
                try { q = market.quote(itemIdForQuote); } catch (Exception e) { q = null; }
                final Map<String, Object> fq = q;
                clientThread.invoke(() -> {
                    if (fq == null) {
                        viewedSlotPriceErrorText = "No price data for this item.";
                        return;
                    }
                    Object hint = fq.get("message");
                    if (hint instanceof String && !((String) hint).isEmpty()) {
                        viewedSlotPriceErrorText = (String) hint;
                    } else {
                        viewedSlotPriceErrorText = null;
                    }
                    Number buyAt = fq.get("buy_at") instanceof Number ? (Number) fq.get("buy_at") : null;
                    Number sellAt = fq.get("sell_at") instanceof Number ? (Number) fq.get("sell_at") : null;
                    if (buyAt == null && sellAt == null) {
                        viewedSlotPriceErrorText = "No price data for this item.";
                        return;
                    }
                    long price = isSelling()
                        ? (sellAt != null ? sellAt.longValue() : buyAt.longValue())
                        : (buyAt != null ? buyAt.longValue() : sellAt.longValue());
                    if (price <= 0) {
                        viewedSlotPriceErrorText = "No price data for this item.";
                        return;
                    }
                    offerManager.setViewedSlotItemPrice(price);

                    highlightController.redraw();
                    log.debug("fetched item {} price: {}", offerManager.getViewedSlotItemId(), price);

                    // todo: Usage of OfferEditor is messy. It mutates a widget so we need to get the original instance
                    //  of it which is created downstream on some other event handler path. This is why we use a supplier
                    //  but probably it should be an injected class of some kind. We should clean this up in the future
                    //  but for now just need it to work as currently broken.

                    OfferEditor flippingWidget = offerEditorSupplier.get();
                    if (flippingWidget != null) {
                        String warn = viewedSlotPriceErrorText;
                        if (warn != null && (warn.startsWith("Loading") || warn.startsWith("No price"))) {
                            flippingWidget.showPrice(price);
                        } else {
                            flippingWidget.showPrice(price, warn);
                        }
                    }
                });
            });

        } else {
            // Do not clear viewedSlotItemId/Price here. TRADINGPOST_SEARCH goes back to -1 as soon
            // as an item is picked from search — before the quantity/price screen — so clearing
            // would wipe the custom-item quote before the quick-set keybind can use it. The cached
            // quote is replaced the next time isViewingSlot is true for a different item.
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
