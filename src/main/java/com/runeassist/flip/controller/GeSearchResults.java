package com.runeassist.flip.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;

import javax.inject.Inject;
import javax.inject.Singleton;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class GeSearchResults {
    private static final int CHATBOX_GROUP = InterfaceID.Chatbox.MES_LAYER >>> 16;
    private static final int FIRST_CHILD = InterfaceID.Chatbox.MES_LAYER_SCROLLAREA & 0xFFFF;
    private static final int LAST_CHILD = FIRST_CHILD + 12;
    private static final int MIN_WIDTH = 100;

    private final Client client;
    private int foundChild = -1;

    public Widget layer() {
        if (foundChild >= 0) {
            Widget w = client.getWidget(CHATBOX_GROUP, foundChild);
            if (isResultsLayer(w)) {
                return w;
            }
            foundChild = -1;
        }
        Widget named = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
        if (isResultsLayer(named)) {
            return named;
        }
        Widget best = null;
        for (int child = FIRST_CHILD; child <= LAST_CHILD; child++) {
            Widget w = client.getWidget(CHATBOX_GROUP, child);
            if (isResultsLayer(w) && (best == null || w.getWidth() >= best.getWidth())) {
                best = w;
                foundChild = child;
            }
        }
        if (best != null) {
            log.info("GE search results layer is chatbox child {}", foundChild);
        }
        return best;
    }

    private static boolean isResultsLayer(Widget w) {
        if (w == null || w.isHidden() || w.getType() != WidgetType.LAYER || w.getWidth() < MIN_WIDTH) {
            return false;
        }
        Widget parent = w.getParent();
        if (parent == null || parent.getType() != WidgetType.LAYER) {
            return false;
        }
        int parentChild = parent.getId() & 0xFFFF;
        return (parent.getId() >>> 16) == CHATBOX_GROUP && parentChild >= FIRST_CHILD && parentChild <= LAST_CHILD;
    }
}
