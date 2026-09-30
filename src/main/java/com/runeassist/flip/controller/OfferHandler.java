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


    private final Client client;
    private final ClientThread clientThread;
    private final SuggestionManager suggestionManager;
    private final OsrsLoginManager osrsLoginManager;
    private final OfferManager offerManager;
    private final HighlightController highlightController;
    private final AccountLoginRS accountLoginRS;
    private final com.runeassist.flip.AresMarketClient market;
    private final ExecutorService executorService;

    private String viewedSlotPriceErrorText = null;

    public void fetchSlotItemPrice(boolean isViewingSlot, Supplier<OfferEditor> offerEditorSupplier) {
        if (isViewingSlot) {
            var currentItemId = client.getVarpValue(CURRENT_GE_ITEM);
            if (currentItemId != offerManager.getViewedSlotItemId()) {
                offerManager.setViewedSlotItemPrice(-1);
            }
            offerManager.setViewedSlotItemId(currentItemId);
            if (currentItemId == -1 || currentItemId == 0) return;

            var suggestion = suggestionManager.getSuggestion();
            if (suggestion != null && suggestion.isModifySuggestion()
                    && suggestion.getItemId() != currentItemId) {
                viewedSlotPriceErrorText = null;
                highlightController.redraw();
                return;
            }
            if (suggestion != null && suggestion.getItemId() == currentItemId &&
                    Objects.equals(suggestion.offerType(), getOfferType())) {
                offerManager.setViewedSlotItemPrice(suggestion.getPrice());
                return;
            }

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
            viewedSlotPriceErrorText = null;
        }
        highlightController.redraw();
    }

    public boolean isSettingQuantity() {
        var chatboxTitleWidget = getChatboxTitleWidget();
        if (chatboxTitleWidget == null) return false;
        String chatInputText = plainText(chatboxTitleWidget.getText());
        return chatInputText.startsWith("How many do you wish to");
    }

    public boolean isSettingPrice() {
        var chatboxTitleWidget = getChatboxTitleWidget();
        if (chatboxTitleWidget == null) return false;
        String chatInputText = plainText(chatboxTitleWidget.getText());
        var offerContainerWidget = client.getWidget(ComponentID.GRAND_EXCHANGE_OFFER_CONTAINER);
        boolean setup = offerContainerWidget != null && !offerContainerWidget.isHidden();
        log.debug("chatbox prompt '{}' setup={}", chatInputText, setup);
        return setup && chatInputText.toLowerCase().contains("price");
    }

    static String plainText(String text) {
        return text == null ? "" : text.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }


    private Widget getChatboxTitleWidget() {
        return client.getWidget(ComponentID.CHATBOX_TITLE);
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
