package com.runeassist.flip.controller;

import com.runeassist.flip.model.OsrsLoginManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Grand Exchange History tab when the player opens it: one row per finished
 * offer with item, side, quantity and price, newest first, no clock. The rows go to the
 * server, which fills in any trade the plugin missed while it was off, so an item the
 * player already sold stops appearing as stock.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class GeHistoryReader {
    private static final int WIDGETS_PER_ROW = 6;
    private static final Pattern MULTI_ITEM_PATTERN = Pattern.compile(">= (.*) each");
    private static final Pattern SINGLE_ITEM_PATTERN = Pattern.compile(">(.*) coin");
    private static final Pattern ORIGINAL_PRICE_PATTERN = Pattern.compile("\\((.*) -");

    private final Client client;
    private final OsrsLoginManager osrsLoginManager;
    private final FlipHistorySyncService flipHistorySyncService;

    private String lastSent = "";

    /** One row of the tab. */
    public static final class Row {
        public final int itemId;
        public final int quantity;
        public final long price;
        public final boolean buy;

        Row(int itemId, int quantity, long price, boolean buy) {
            this.itemId = itemId;
            this.quantity = quantity;
            this.price = price;
            this.buy = buy;
        }
    }

    public void onGameTick() {
        if (!osrsLoginManager.isValidLoginState()) {
            lastSent = "";
            return;
        }
        Widget list = client.getWidget(InterfaceID.GeHistory.LIST);
        if (list == null || list.isHidden()) {
            return;
        }
        List<Row> rows = parseRows(list);
        if (rows.isEmpty()) {
            return;
        }
        String fingerprint = fingerprint(rows);
        if (fingerprint.equals(lastSent)) {
            return;
        }
        lastSent = fingerprint;
        flipHistorySyncService.sendGeHistory(rows);
    }

    static String fingerprint(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        for (Row r : rows) {
            sb.append(r.itemId).append(r.buy ? 'b' : 's').append(r.quantity).append('@').append(r.price).append(';');
        }
        return sb.toString();
    }

    /** Completed rows, newest first; rows that cannot be read are skipped. */
    static List<Row> parseRows(Widget container) {
        Widget[] children = container.getDynamicChildren();
        if (children == null || children.length < WIDGETS_PER_ROW) {
            return Collections.emptyList();
        }
        List<Row> rows = new ArrayList<>(children.length / WIDGETS_PER_ROW);
        for (int i = 0; i + WIDGETS_PER_ROW - 1 < children.length; i += WIDGETS_PER_ROW) {
            try {
                Widget stateW = children[i + 2];
                Widget itemW = children[i + 4];
                Widget priceW = children[i + 5];
                String state = stateW == null ? null : stateW.getText();
                if (state == null || state.isEmpty() || itemW == null || priceW == null) {
                    continue;
                }
                int itemId = itemW.getItemId();
                int quantity = itemW.getItemQuantity();
                long price = parsePrice(priceW.getText(), quantity);
                if (itemId <= 0 || quantity <= 0 || price <= 0) {
                    continue;
                }
                rows.add(new Row(itemId, quantity, price, Text.removeTags(state).startsWith("Bought")));
            } catch (RuntimeException e) {
                log.debug("unreadable GE history row at {}", i, e);
            }
        }
        return rows;
    }

    /** Price per item from the tab's price text: "= 1,234 each", "1,234 coins" or a struck-out total. */
    static long parsePrice(String text, int quantity) {
        if (text == null) {
            return 0L;
        }
        Matcher m;
        boolean total = false;
        if (text.contains(")</col>")) {
            m = ORIGINAL_PRICE_PATTERN.matcher(text);
            total = true;
        } else if (text.contains("each")) {
            m = MULTI_ITEM_PATTERN.matcher(text);
        } else {
            m = SINGLE_ITEM_PATTERN.matcher(text);
        }
        if (!m.find()) {
            return 0L;
        }
        String digits = m.group(1).replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 18) {
            return 0L;
        }
        long price = Long.parseLong(digits);
        return total && quantity > 0 ? price / quantity : price;
    }
}
