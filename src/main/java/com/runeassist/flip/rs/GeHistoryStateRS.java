package com.runeassist.flip.rs;

import com.runeassist.flip.model.GeHistoryRow;
import com.runeassist.flip.model.GeHistoryState;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Singleton
@Slf4j
public class GeHistoryStateRS extends ReactiveStateImpl<GeHistoryState> {

    public static final int GE_HISTORY_GROUP = 383;
    public static final int GE_HISTORY_LIST_CHILD = 3;

    private static final Pattern MULTI_ITEM_PATTERN = Pattern.compile(">= (.*) each");
    private static final Pattern SINGLE_ITEM_PATTERN = Pattern.compile(">(.*) coin");
    private static final Pattern ORIGINAL_PRICE_PATTERN = Pattern.compile("\\((.*) -");
    private static final Pattern RELATIVE_TIME_PATTERN = Pattern.compile(
            "(?i)(?:(\\d+)|an?)\\s*(second|minute|hour|day|week)s?\\s*ago");
    private static final Pattern EPOCH_MILLIS_PATTERN = Pattern.compile("\\b(1[0-9]{12})\\b");
    private static final Pattern EPOCH_SECONDS_PATTERN = Pattern.compile("\\b(1[0-9]{9})\\b");

    private final OsrsLoginRS osrsLoginRS;
    private boolean lastSeenVisible = false;
    private int sessionOpenedAt = 0;

    @Inject
    public GeHistoryStateRS(OsrsLoginRS osrsLoginRS) {
        super(GeHistoryState.empty());
        this.osrsLoginRS = osrsLoginRS;
        osrsLoginRS.registerListener(state -> {
            if (state == null || !state.loggedIn) {
                lastSeenVisible = false;
                set(GeHistoryState.empty());
                return;
            }
            Long loaded = get().getAccountHash();
            if (loaded != null && !Objects.equals(loaded, state.accountHash)) {
                lastSeenVisible = false;
                set(GeHistoryState.empty());
            }
        });
    }

    public void onGameTick(Client client) {
        if (!osrsLoginRS.get().loggedIn) {
            return;
        }
        Widget container = client.getWidget(GE_HISTORY_GROUP, GE_HISTORY_LIST_CHILD);
        boolean visible = container != null;
        if (visible && !lastSeenVisible) {
            sessionOpenedAt = (int) Instant.now().getEpochSecond();
        }
        lastSeenVisible = visible;
        if (!visible) {
            return;
        }
        List<GeHistoryRow> rows = parseRows(container);
        if (rows.isEmpty()) {
            return;
        }
        Long accountHash = osrsLoginRS.get().accountHash;
        GeHistoryState current = get();
        if (current.isLoaded()
                && current.getCapturedAt() == sessionOpenedAt
                && Objects.equals(current.getAccountHash(), accountHash)
                && rows.equals(current.getRows())) {
            return;
        }
        set(new GeHistoryState(true, Collections.unmodifiableList(rows), sessionOpenedAt, accountHash));
    }

    /** Parse completed GE history rows (newest first). Shared with the training-data dump. */
    public static List<GeHistoryRow> parseRows(Widget container) {
        Widget[] children = container.getDynamicChildren();
        if (children == null || children.length < 6) {
            return Collections.emptyList();
        }
        List<GeHistoryRow> rows = new ArrayList<>(children.length / 6);
        long now = System.currentTimeMillis();
        for (int i = 0; i + 5 < children.length; i += 6) {
            try {
                Widget nameW = children[i + 1];
                Widget stateW = children[i + 2];
                Widget extraW = children[i + 3];
                Widget itemW = children[i + 4];
                Widget priceW = children[i + 5];
                String stateText = stateW.getText();
                if (stateText == null || stateText.isEmpty()) {
                    continue;
                }
                int itemId = itemW.getItemId();
                int quantity = itemW.getItemQuantity();
                if (itemId <= 0 || quantity <= 0) {
                    continue;
                }
                long price = parsePrice(priceW.getText(), quantity);
                if (price <= 0) {
                    continue;
                }
                boolean isBuy = stateText.startsWith("Bought");
                String name = plain(nameW != null ? nameW.getText() : null);
                Long fillTs = parseFillTs(plain(extraW != null ? extraW.getText() : null), now);
                rows.add(new GeHistoryRow(itemId, quantity, price, isBuy, name, fillTs));
            } catch (Exception e) {
                log.debug("failed to parse GE history row at offset {}", i, e);
            }
        }
        return rows;
    }

    private static String plain(String s) {
        if (s == null) {
            return null;
        }
        String t = Text.removeTags(s).trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * The in-game GE history UI usually has no per-row clock. If a widget does expose
     * a relative "N hours ago" or an epoch, convert it; otherwise return null.
     */
    private static Long parseFillTs(String text, long nowMs) {
        if (text == null) {
            return null;
        }
        Matcher epochMs = EPOCH_MILLIS_PATTERN.matcher(text);
        if (epochMs.find()) {
            return Long.parseLong(epochMs.group(1));
        }
        Matcher epochS = EPOCH_SECONDS_PATTERN.matcher(text);
        if (epochS.find()) {
            return Long.parseLong(epochS.group(1)) * 1000L;
        }
        Matcher rel = RELATIVE_TIME_PATTERN.matcher(text);
        if (!rel.find()) {
            return null;
        }
        long n = rel.group(1) != null ? Long.parseLong(rel.group(1)) : 1L;
        String unit = rel.group(2).toLowerCase(Locale.ROOT);
        long ms;
        switch (unit) {
            case "second":
                ms = n * 1000L;
                break;
            case "minute":
                ms = n * 60_000L;
                break;
            case "hour":
                ms = n * 3_600_000L;
                break;
            case "day":
                ms = n * 86_400_000L;
                break;
            case "week":
                ms = n * 604_800_000L;
                break;
            default:
                return null;
        }
        return nowMs - ms;
    }

    private static long parsePrice(String text, int quantity) {
        if (text == null) {
            return 0;
        }
        Matcher m;
        boolean isTotalPrice = false;
        if (text.contains(")</col>")) {
            m = ORIGINAL_PRICE_PATTERN.matcher(text);
            isTotalPrice = true;
        } else if (text.contains("each")) {
            m = MULTI_ITEM_PATTERN.matcher(text);
        } else {
            m = SINGLE_ITEM_PATTERN.matcher(text);
        }
        if (!m.find()) {
            return 0;
        }
        StringBuilder s = new StringBuilder();
        for (char c : m.group(1).toCharArray()) {
            if (Character.isDigit(c)) {
                s.append(c);
            }
        }
        if (s.length() == 0) {
            return 0;
        }
        long price = Long.parseLong(s.toString());
        if (isTotalPrice && quantity > 0) {
            return price / quantity;
        }
        return price;
    }
}
