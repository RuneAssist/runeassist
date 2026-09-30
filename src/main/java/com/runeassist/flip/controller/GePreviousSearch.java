package com.runeassist.flip.controller;

import com.runeassist.flip.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.Client;
import net.runelite.api.widgets.*;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class GePreviousSearch {

    private final SuggestionManager suggestionManager;
    private final SuggestionPreferencesManager suggestionPreferencesManager;
    private final AccountStatusManager accountStatusManager;
    private final GrandExchange grandExchange;
    private final HighlightController highlightController;
    private final Client client;


    public void showSuggestedItemInSearch() {
        Suggestion suggestion = suggestionManager.getSuggestion();
        if (suggestion == null) {
            return;
        }

        if (suggestion.getType() == SuggestionType.BUY) {
            if ((grandExchange.isPreviousSearchSet() || previousSearchItemExists()) && grandExchange.showLastSearchEnabled()) {
                setPreviousSearch(suggestion.getItemId(), suggestion.getName());
            } else {
                createPreviousSearchWidget(suggestion.getItemId(), suggestion.getName());
                createPreviousSearchItemNameWidget(suggestion.getName());
                createPreviousSearchItemWidget(suggestion.getItemId());
                createPreviousSearchTextWidget();
            }
            highlightController.redraw();
        }
    }


    private boolean previousSearchItemExists() {
        Widget searchResults = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
        if(searchResults == null || searchResults.getChildren() == null || searchResults.getChildren().length < 2) {
            return false;
        }
        for (int i = 0; i < searchResults.getChildren().length; i++) {
            Widget child = searchResults.getChild(i);
            if (child == null) {
                continue;
            }
            String text = child.getText();
            if (text == null) {
                continue;
            }
            if(text.startsWith("RuneAssist:") || text.startsWith("Waiting for dumps...")) {
                return true;
            }
        }
        return false;
    }

    private void setPreviousSearch(int itemId, String itemName) {
        Widget searchResults = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
        Widget previousSearch = searchResults.getChild(0);
        previousSearch.setHasListener(true);
        previousSearch.setOnOpListener(754, itemId, 84);
        previousSearch.setOnKeyListener(754, itemId, -2147483640);
        previousSearch.setName("<col=ff9040>" + itemName + "</col>");
        previousSearch.setAction(0, "Select");
        previousSearch.revalidate();

        Widget previousSearchText = searchResults.getChild(1);
        previousSearchText.setText("RuneAssist:");
        previousSearchText.setOriginalWidth(95);
        previousSearchText.setXTextAlignment(WidgetTextAlignment.LEFT);
        previousSearchText.revalidate();

        Widget itemNameWidget = searchResults.getChild(2);
        itemNameWidget.setText(itemName);
        itemNameWidget.revalidate();

        Widget item = searchResults.getChild(3);
        item.setItemId(itemId);
        item.revalidate();
    }

    private void createPreviousSearchWidget(int itemId, String itemName) {
        Widget parentWidget = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
        Widget widget = parentWidget.createChild(0, WidgetType.RECTANGLE);
        widget.setTextColor(0xFFFFFF);
        widget.setOpacity(255);
        widget.setName("<col=ff9040>" + itemName + "</col>");
        widget.setHasListener(true);
        widget.setFilled(true);
        widget.setOriginalX(114);
        widget.setOriginalY(0);
        widget.setOriginalWidth(256);
        widget.setOriginalHeight(32);
        widget.setOnOpListener(754, itemId, 84);
        widget.setOnKeyListener(754, itemId, -2147483640);
        widget.setHasListener(true);
        widget.setAction(0, "Select");
        // set opacity to 200 when mouse is hovering
        widget.setOnMouseOverListener((JavaScriptCallback) ev -> {
            widget.setOpacity(200);
        });
        // set opacity back to 255 when mouse is not hovering
        widget.setOnMouseLeaveListener((JavaScriptCallback) ev -> {
            widget.setOpacity(255);
        });

        widget.revalidate();
    }

    private void createPreviousSearchTextWidget() {
        Widget parentWidget = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
        Widget widget = parentWidget.createChild(1, WidgetType.TEXT);
        widget.setText("RuneAssist:");
        widget.setFontId(495);
        widget.setOriginalX(114);
        widget.setOriginalY(0);
        widget.setOriginalWidth(95);
        widget.setOriginalHeight(32);
        widget.setYTextAlignment(1);
        widget.revalidate();
    }

    private void createPreviousSearchItemNameWidget(String itemName) {
        Widget parentWidget = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
        Widget widget = parentWidget.createChild(2, WidgetType.TEXT);
        widget.setText(itemName);
        widget.setFontId(495);
        widget.setOriginalX(254);
        widget.setOriginalY(0);
        widget.setOriginalWidth(116);
        widget.setOriginalHeight(32);
        widget.setYTextAlignment(1);
        widget.revalidate();
    }

    private void createPreviousSearchItemWidget(int itemId) {
        Widget parentWidget = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
        Widget widget = parentWidget.createChild(3, WidgetType.GRAPHIC);
        widget.setItemId(itemId);
        widget.setItemQuantity(1);
        widget.setItemQuantityMode(0);
        widget.setRotationX(550);
        widget.setModelZoom(1031);
        widget.setBorderType(1);
        widget.setOriginalX(214);
        widget.setOriginalY(0);
        widget.setOriginalWidth(36);
        widget.setOriginalHeight(32);
        widget.revalidate();
    }


}
