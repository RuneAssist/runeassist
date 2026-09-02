package com.runeassist.flip;

import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.model.SuggestionType;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure selection logic that turns our market scorer output plus current account
 * state into a single Flipping Copilot {@link Suggestion}.
 *
 * <p>This is a pure function: no {@code Client}, no I/O, fully deterministic.
 * It replaces FC's server as the source of the {@code Suggestion} object that
 * drives FC's GE UI/overlays.</p>
 */
public final class LocalSuggestionEngine {

    private LocalSuggestionEngine() {
    }

    // offersBySlot entry layout: {itemId, buyIs1, price, sold, total, fillingIs1}
    private static final int O_ITEM_ID = 0;
    private static final int O_BUY_IS_1 = 1;
    private static final int O_PRICE = 2;
    private static final int O_SOLD = 3;
    private static final int O_TOTAL = 4;
    private static final int O_FILLING_IS_1 = 5;

    static final String WAIT_SLOTS_FULL =
        "All GE slots are full. Wait for a fill, or modify a mispriced offer.";
    static final String WAIT_LIMIT_EXHAUSTED =
        "4h buy-limit exhausted for the best candidates.";
    static final String WAIT_NO_MARGIN =
        "No clean margin — nothing passed the filters.";
    static final String WAIT_SKIPPED_BLOCKED =
        "Skipped or blocked every candidate.";
    static final String WAIT_NOT_ENOUGH_COINS =
        "Not enough coins for the next flip.";
    static final String WAIT_GENERIC =
        "No actionable flip right now.";

    /** Inputs for {@link #next(Input)}. All collections may be null. */
    public static final class Input {
        public List<Map<String, Object>> scoredFlips;
        public long[][] offersBySlot;
        public Map<Integer, long[]> held;
        public long coins;
        public int maxSlots = 8;
        public Set<Integer> skippedItemIds = Collections.emptySet();
        public Set<Integer> blockedItemIds = Collections.emptySet();
        /** User-skipped items: do not ABORT/MODIFY these live offers. */
        public Set<Integer> skipOfferItemIds = Collections.emptySet();
        /**
         * Item ids we suggested BUY/SELL for this session recently. Do not ABORT those
         * live offers on the next tick for dead-margin (list-then-abort loop).
         */
        public Set<Integer> protectAbortItemIds = Collections.emptySet();
        /** itemId -> remaining 4h GE buy-limit. Missing key = unknown (do not cap). 0 = exhausted. */
        public Map<Integer, Integer> remainingBuyLimit = Collections.emptyMap();
        public long minPredictedProfit;
    }

    /**
     * Choose the next suggestion.
     *
     * @param scoredFlips  our market flips, best first (each a Map of the documented keys)
     * @param offersBySlot length 8; each entry null (empty slot) or
     *                     {itemId, buyIs1, price, sold, total, fillingIs1}
     * @param held         itemId -> {qty, avgBuy} currently held
     * @param coins        available coins
     * @param maxSlots     maximum GE slots we may use
     * @return the first applicable Suggestion by priority (MODIFY/ABORT, SELL, BUY, WAIT)
     */
    public static Suggestion next(
            List<Map<String, Object>> scoredFlips,
            long[][] offersBySlot,
            Map<Integer, long[]> held,
            long coins,
            int maxSlots) {
        Input in = new Input();
        in.scoredFlips = scoredFlips;
        in.offersBySlot = offersBySlot;
        in.held = held;
        in.coins = coins;
        in.maxSlots = maxSlots;
        return next(in);
    }

    public static Suggestion next(Input in) {
        List<Map<String, Object>> scoredFlips = in.scoredFlips;
        long[][] offersBySlot = in.offersBySlot;
        Map<Integer, long[]> held = in.held;
        long coins = in.coins;
        int maxSlots = in.maxSlots;
        Set<Integer> skipped = in.skippedItemIds != null ? in.skippedItemIds : Collections.emptySet();
        Set<Integer> blocked = in.blockedItemIds != null ? in.blockedItemIds : Collections.emptySet();
        Set<Integer> skipOffers = in.skipOfferItemIds != null ? in.skipOfferItemIds : Collections.emptySet();
        Set<Integer> protectAbort = in.protectAbortItemIds != null
            ? in.protectAbortItemIds : Collections.emptySet();
        Map<Integer, Integer> remainingLimit = in.remainingBuyLimit != null
            ? in.remainingBuyLimit : Collections.emptyMap();

        // 1) Live offers are owned until they fill, stall (sold==0 and dead), or Skip.
        //    Never abort a filling offer (sold > 0). Never abort an offer we just
        //    suggested listing. Do not abort a SELL for wiki/cost-basis "margin gone
        //    after tax" (that is the list-then-abort loop). MODIFY only when the
        //    price is clearly wrong — 1gp wiki jitter is not a reprice. User-skipped
        //    items are left alone (do not abort/modify them).
        if (offersBySlot != null) {
            for (int slot = 0; slot < offersBySlot.length; slot++) {
                long[] offer = offersBySlot[slot];
                if (offer == null || offer.length < 6) {
                    continue;
                }
                if (offer[O_FILLING_IS_1] != 1L) {
                    continue;
                }
                int remaining = (int) Math.max(0L, offer[O_TOTAL] - offer[O_SOLD]);
                if (remaining <= 0) {
                    continue;
                }
                int offerItemId = (int) offer[O_ITEM_ID];
                if (skipOffers.contains(offerItemId)) {
                    continue;
                }
                boolean buy = offer[O_BUY_IS_1] == 1L;
                boolean fillingProgress = offer[O_SOLD] > 0L;
                boolean recentlySuggested = protectAbort.contains(offerItemId);
                Map<String, Object> market = findMarketFlip(scoredFlips, offerItemId);
                Map<String, Object> sellRow = findFlip(scoredFlips, offerItemId);
                if (shouldAbortOffer(market, buy, fillingProgress, recentlySuggested)) {
                    String name = getString(market, "name");
                    if (name == null) {
                        name = getString(sellRow, "name");
                    }
                    Suggestion abort = build(SuggestionType.ABORT, slot, offerItemId,
                            offer[O_PRICE], remaining, name, null, null);
                    applyLimit(abort, market != null ? market : sellRow, remainingLimit);
                    applyWhy(abort, market != null ? market : sellRow, offer[O_PRICE]);
                    return abort;
                }

                // BUY reprice needs a market quote, not a cost-basis sell row.
                Map<String, Object> quote = buy ? market : (market != null ? market : sellRow);
                if (quote == null) {
                    continue;
                }
                long offerPrice = offer[O_PRICE];
                String name = getString(quote, "name");
                Double hours = getNullableDouble(quote, "est_fill_hours");
                Double profit = modifyProfit(quote, remaining);

                if (buy) {
                    long buyAt = getLong(quote, "buy_at");
                    if (clearlyMispriced(offerPrice, buyAt)) {
                        Suggestion s = build(SuggestionType.MODIFY_BUY, slot, offerItemId,
                                buyAt, remaining, name, profit, hours);
                        applyLimit(s, quote, remainingLimit);
                        applyWhy(s, quote, offerPrice);
                        return s;
                    }
                } else {
                    long sellAt = getLong(quote, "sell_at");
                    if (clearlyMispriced(offerPrice, sellAt)) {
                        Suggestion s = build(SuggestionType.MODIFY_SELL, slot, offerItemId,
                                sellAt, remaining, name, profit, hours);
                        applyLimit(s, quote, remainingLimit);
                        applyWhy(s, quote, offerPrice);
                        return s;
                    }
                }
            }
        }

        int freeSlot = firstFreeSlot(offersBySlot, maxSlots);
        int usedSlots = countUsedSlots(offersBySlot);
        boolean slotsFull = freeSlot < 0 || usedSlots >= maxSlots;

        // 2) SELL held stock: highest scored "sell" entry we hold and have no
        //    active offer for, if a usable free slot exists. Never list an item
        //    whose wiki/cost-basis margin is already dead — that offer would be
        //    aborted on the next tick (list-then-abort).
        if (scoredFlips != null && freeSlot >= 0 && held != null) {
            for (Map<String, Object> flip : scoredFlips) {
                if (flip == null) {
                    continue;
                }
                if (!"sell".equals(getString(flip, "side"))) {
                    continue;
                }
                int itemId = getInt(flip, "id");
                if (skipped.contains(itemId) || blocked.contains(itemId)) {
                    continue;
                }
                long[] heldEntry = held.get(itemId);
                if (heldEntry == null || heldEntry.length < 1 || heldEntry[0] <= 0L) {
                    continue;
                }
                if (hasActiveOffer(offersBySlot, itemId)) {
                    continue;
                }
                Map<String, Object> market = findMarketFlip(scoredFlips, itemId);
                if (isDeadMargin(flip) || isDeadMargin(market)) {
                    continue;
                }
                long sellAt = getLong(flip, "sell_at");
                long heldQty = heldEntry[0];
                long suggestedQty = getLong(flip, "suggested_qty");
                long qty = suggestedQty > 0 ? Math.min(suggestedQty, heldQty) : heldQty;
                if (qty <= 0) {
                    continue;
                }
                Double profit = scaledProfit(flip, qty);
                Suggestion s = build(SuggestionType.SELL, freeSlot, itemId, sellAt,
                        (int) qty, getString(flip, "name"), profit,
                        getNullableDouble(flip, "est_fill_hours"));
                applyLimit(s, flip, remainingLimit);
                applyWhy(s, flip, 0L);
                return s;
            }
        }

        // 3) BUY: highest scored non-sell entry we neither hold nor have an
        //    active offer for, if we have slot headroom and enough coins.
        boolean anyUnblocked = false;
        boolean anyWithLimitLeft = false;
        boolean anyAffordable = false;
        boolean sawLimitExhausted = false;
        boolean sawBlocked = false;
        boolean sawPriced = false;

        if (scoredFlips != null && !slotsFull) {
            for (Map<String, Object> flip : scoredFlips) {
                if (flip == null) {
                    continue;
                }
                if ("sell".equals(getString(flip, "side"))) {
                    continue;
                }
                int itemId = getInt(flip, "id");
                if (skipped.contains(itemId) || blocked.contains(itemId)) {
                    sawBlocked = true;
                    continue;
                }
                anyUnblocked = true;
                if (held != null && held.containsKey(itemId)) {
                    continue;
                }
                if (hasActiveOffer(offersBySlot, itemId)) {
                    continue;
                }
                if (isDeadMargin(flip)) {
                    continue;
                }
                Integer left = remainingLimit.get(itemId);
                if (left != null && left <= 0) {
                    sawLimitExhausted = true;
                    continue; // 4h buy-limit exhausted
                }
                anyWithLimitLeft = true;
                long buyAt = getLong(flip, "buy_at");
                if (buyAt <= 0) {
                    continue;
                }
                sawPriced = true;
                if (coins < buyAt) {
                    continue;
                }
                anyAffordable = true;
                long suggestedQty = getLong(flip, "suggested_qty");
                long affordable = coins / buyAt;
                long qty = suggestedQty > 0 ? Math.min(suggestedQty, affordable) : affordable;
                if (left != null) {
                    qty = Math.min(qty, left);
                }
                long geLimit = getLong(flip, "ge_limit");
                if (geLimit > 0) {
                    qty = Math.min(qty, geLimit);
                }
                if (qty <= 0) {
                    continue;
                }
                Double profit = scaledProfit(flip, qty);
                if (in.minPredictedProfit > 0 && (profit == null || profit < in.minPredictedProfit)) {
                    continue;
                }
                Suggestion s = build(SuggestionType.BUY, freeSlot, itemId, buyAt,
                        (int) qty, getString(flip, "name"), profit,
                        getNullableDouble(flip, "est_fill_hours"));
                applyLimit(s, flip, remainingLimit);
                applyWhy(s, flip, 0L);
                return s;
            }
        } else if (scoredFlips != null) {
            for (Map<String, Object> flip : scoredFlips) {
                if (flip == null || "sell".equals(getString(flip, "side"))) {
                    continue;
                }
                int itemId = getInt(flip, "id");
                if (skipped.contains(itemId) || blocked.contains(itemId)) {
                    sawBlocked = true;
                    continue;
                }
                anyUnblocked = true;
                Integer left = remainingLimit.get(itemId);
                if (left != null && left <= 0) {
                    sawLimitExhausted = true;
                    continue;
                }
                anyWithLimitLeft = true;
                long buyAt = getLong(flip, "buy_at");
                if (buyAt <= 0) {
                    continue;
                }
                sawPriced = true;
                if (coins >= buyAt) {
                    anyAffordable = true;
                }
            }
        }

        return wait(waitReason(slotsFull, anyUnblocked, anyWithLimitLeft, anyAffordable,
                sawLimitExhausted, sawBlocked, sawPriced), offersBySlot, maxSlots, scoredFlips);
    }

    /**
     * First matching reason: slots, 4h limit, coins, skipped/blocked, then no margin.
     * Never returns {@link #WAIT_GENERIC}.
     */
    private static String waitReason(boolean slotsFull, boolean anyUnblocked,
                                     boolean anyWithLimitLeft, boolean anyAffordable,
                                     boolean sawLimitExhausted, boolean sawBlocked,
                                     boolean sawPriced) {
        if (slotsFull) {
            return WAIT_SLOTS_FULL;
        }
        if (!anyWithLimitLeft && sawLimitExhausted) {
            return WAIT_LIMIT_EXHAUSTED;
        }
        if (sawPriced && !anyAffordable) {
            return WAIT_NOT_ENOUGH_COINS;
        }
        if (!anyUnblocked && sawBlocked) {
            return WAIT_SKIPPED_BLOCKED;
        }
        return WAIT_NO_MARGIN;
    }

    /** Fallback Wait with a specific reason + slot numbers. Never uses {@link #WAIT_GENERIC}. */
    static Suggestion waitFallback(String message, long[][] offersBySlot, int maxSlots) {
        return wait(message == null || message.isEmpty() ? WAIT_NO_MARGIN : message,
                offersBySlot, maxSlots, null);
    }

    private static Suggestion wait(String message, long[][] offersBySlot, int maxSlots,
                                   List<Map<String, Object>> scoredFlips) {
        Suggestion wait = new Suggestion();
        wait.setType(SuggestionType.WAIT);
        wait.setBoxId(-1);
        wait.setName("");
        wait.setMessage(message);
        String why = waitWhy(offersBySlot, maxSlots, scoredFlips);
        if (why != null && !why.isEmpty()) {
            wait.setWhy(why);
        }
        return wait;
    }

    /** Slot count plus the furthest-along filling offer, so the south card is not blank. */
    private static String waitWhy(long[][] offersBySlot, int maxSlots,
                                  List<Map<String, Object>> scoredFlips) {
        int cap = maxSlots > 0 ? maxSlots : 8;
        int used = countUsedSlots(offersBySlot);
        StringBuilder sb = new StringBuilder();
        sb.append(used).append("/").append(cap).append(" slots");
        long[] best = null;
        if (offersBySlot != null) {
            for (long[] offer : offersBySlot) {
                if (offer == null || offer.length < 6 || offer[O_FILLING_IS_1] != 1L) {
                    continue;
                }
                if (offer[O_SOLD] <= 0L || offer[O_TOTAL] <= 0L) {
                    continue;
                }
                if (best == null || offer[O_SOLD] > best[O_SOLD]) {
                    best = offer;
                }
            }
        }
        if (best != null) {
            String name = null;
            Map<String, Object> row = findFlip(scoredFlips, (int) best[O_ITEM_ID]);
            if (row == null) {
                row = findMarketFlip(scoredFlips, (int) best[O_ITEM_ID]);
            }
            if (row != null) {
                name = getString(row, "name");
            }
            if (name == null || name.isEmpty()) {
                name = "offer";
            }
            sb.append(" · ").append(name).append(" ")
                    .append(compact(best[O_SOLD])).append("/")
                    .append(compact(best[O_TOTAL])).append(" filling");
        }
        return sb.toString();
    }

    private static Double modifyProfit(Map<String, Object> quote, int remaining) {
        if (remaining <= 0) {
            return null;
        }
        Double margin = getNullableDouble(quote, "margin_post_tax");
        if (margin != null) {
            return margin * remaining;
        }
        return scaledProfit(quote, remaining);
    }

    private static Suggestion build(SuggestionType type, int boxId, int itemId,
                                    long price, int quantity, String name, Double expectedProfit,
                                    Double expectedDurationHours) {
        Suggestion s = new Suggestion();
        s.setType(type);
        s.setBoxId(boxId);
        s.setItemId(itemId);
        s.setId(itemId); // skip uses this id
        s.setPrice(price);
        s.setQuantity(quantity);
        s.setName(name == null ? "" : name);
        if (expectedProfit != null) {
            s.setExpectedProfit(expectedProfit);
        }
        if (expectedDurationHours != null) {
            s.setExpectedDuration(expectedDurationHours * 3600.0); // panel expects seconds
        }
        return s;
    }

    private static void applyLimit(Suggestion s, Map<String, Object> flip,
                                   Map<Integer, Integer> remainingLimit) {
        if (s == null) {
            return;
        }
        int ge = getInt(flip, "ge_limit");
        s.setGeLimit(ge);
        int itemId = s.getItemId();
        if (remainingLimit != null && remainingLimit.containsKey(itemId)) {
            Integer left = remainingLimit.get(itemId);
            s.setRemainingLimit(left != null ? left : -1);
            s.setLimitKnown(ge > 0 && left != null && left >= 0);
        } else {
            s.setRemainingLimit(-1);
            s.setLimitKnown(false);
        }
    }

    /** Stamp a one-line why on BUY/SELL/MODIFY/ABORT. WAIT keeps {@code message}. */
    private static void applyWhy(Suggestion s, Map<String, Object> flip, long offerPrice) {
        if (s == null || s.getType() == null) {
            return;
        }
        String why;
        switch (s.getType()) {
            case BUY:
                why = whyBuy(s, flip);
                break;
            case SELL:
                why = whySell(s, flip);
                break;
            case MODIFY_BUY:
            case MODIFY_SELL:
                why = whyModify(s, offerPrice);
                break;
            case ABORT:
                why = whyAbort(flip);
                break;
            default:
                return;
        }
        if (why != null && !why.isEmpty()) {
            s.setWhy(why);
        }
    }

    private static String whyBuy(Suggestion s, Map<String, Object> flip) {
        List<String> parts = new ArrayList<>();
        String name = s.getName();
        String head = "Buy " + compact(s.getQuantity());
        if (name != null && !name.isEmpty()) {
            head += " " + name;
        }
        if (s.getPrice() > 0) {
            head += " at " + gp(s.getPrice());
        }
        parts.add(head);
        if (s.isLimitKnown() && s.getGeLimit() > 0 && s.getRemainingLimit() >= 0) {
            parts.add("limit " + compact(s.getRemainingLimit()) + "/" + compact(s.getGeLimit()) + " left");
        }
        String fill = fillPart(flip, s);
        if (fill != null) {
            parts.add(fill);
        }
        String flag = flagPart(flip);
        if (flag != null) {
            parts.add(flag);
        }
        return joinParts(parts);
    }

    private static String whySell(Suggestion s, Map<String, Object> flip) {
        List<String> parts = new ArrayList<>();
        String head = "Sell " + compact(s.getQuantity()) + " held";
        if (s.getPrice() > 0) {
            head += " at " + gp(s.getPrice());
        }
        head += " after tax";
        parts.add(head);
        long avgBuy = getLong(flip, "buy_at");
        if (avgBuy > 0 && flip != null && flip.containsKey("margin_post_tax")) {
            Object v = flip.get("margin_post_tax");
            if (v instanceof Number) {
                long vs = Math.round(((Number) v).doubleValue() * s.getQuantity());
                String sign = vs > 0 ? "+" : vs < 0 ? "-" : "";
                parts.add(sign + compact(Math.abs(vs)) + " gp vs avg buy");
            }
        }
        String fill = fillPart(flip, s);
        if (fill != null) {
            parts.add(fill);
        }
        return joinParts(parts);
    }

    private static String whyModify(Suggestion s, long offerPrice) {
        long target = s.getPrice();
        long delta = Math.abs(offerPrice - target);
        boolean buy = s.isBuySuggestion();
        String side = buy ? "Buy" : "Sell";
        String dir = offerPrice < target ? "below" : "above";
        String qty = s.getQuantity() > 0 ? " · " + compact(s.getQuantity()) + " left" : "";
        return side + " offer " + compact(delta) + "gp " + dir + " market — reprice to "
                + gp(target) + qty;
    }

    private static String whyAbort(Map<String, Object> market) {
        String reason = deadReason(market);
        if (reason.endsWith(".")) {
            reason = reason.substring(0, reason.length() - 1);
        }
        return reason + " — cancel this offer";
    }

    private static String fillPart(Map<String, Object> flip, Suggestion s) {
        Double hours = getNullableDouble(flip, "est_fill_hours");
        if (hours == null && s.getExpectedDuration() != null && s.getExpectedDuration() > 0) {
            hours = s.getExpectedDuration() / 3600.0;
        }
        if (hours == null || hours <= 0) {
            return null;
        }
        if (hours < 1.0) {
            int min = (int) Math.round(hours * 60.0);
            if (min < 1) {
                min = 1;
            }
            return "~" + min + " min fill";
        }
        if (hours < 10.0) {
            String h = String.format(Locale.ENGLISH, "%.1f", hours).replace(".0", "");
            return "~" + h + " hr fill";
        }
        return "~" + Math.round(hours) + " hr fill";
    }

    /**
     * Liquidity flag from the scorer list only. Empty list (healthy book) is two-sided;
     * missing flags key is omitted so we do not invent a book shape.
     */
    private static String flagPart(Map<String, Object> flip) {
        if (flip == null || !flip.containsKey("flags")) {
            return null;
        }
        Object f = flip.get("flags");
        if (!(f instanceof List)) {
            return null;
        }
        if (hasFlag(flip, "one-sided")) {
            return "one-sided";
        }
        if (hasFlag(flip, "thin")) {
            return "thin";
        }
        if (hasFlag(flip, "wide-spread")) {
            return "wide-spread";
        }
        return "two-sided";
    }

    private static String joinParts(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static String compact(long n) {
        long abs = Math.abs(n);
        String sign = n < 0 ? "-" : "";
        if (abs >= 1_000_000L) {
            if (abs % 1_000_000L == 0) {
                return sign + (abs / 1_000_000L) + "M";
            }
            return sign + String.format(Locale.ENGLISH, "%.1fM", abs / 1_000_000.0).replace(".0", "");
        }
        if (abs >= 10_000L) {
            if (abs % 1_000L == 0) {
                return sign + (abs / 1_000L) + "k";
            }
            return sign + String.format(Locale.ENGLISH, "%.1fk", abs / 1_000.0).replace(".0", "");
        }
        return sign + abs;
    }

    private static String gp(long n) {
        return NumberFormat.getIntegerInstance(Locale.ENGLISH).format(n);
    }

    private static Double scaledProfit(Map<String, Object> flip, long qty) {
        Double profit = getNullableDouble(flip, "projected_profit");
        long suggestedQty = getLong(flip, "suggested_qty");
        if (profit == null || suggestedQty <= 0 || qty == suggestedQty) {
            return profit;
        }
        return profit * qty / suggestedQty;
    }

    private static Map<String, Object> findFlip(List<Map<String, Object>> scoredFlips, int itemId) {
        if (scoredFlips == null) {
            return null;
        }
        for (Map<String, Object> flip : scoredFlips) {
            if (flip != null && getInt(flip, "id") == itemId) {
                return flip;
            }
        }
        return null;
    }

    /** Market row (not a cost-basis sell) for an item, or null. */
    private static Map<String, Object> findMarketFlip(List<Map<String, Object>> scoredFlips, int itemId) {
        if (scoredFlips == null) {
            return null;
        }
        for (Map<String, Object> flip : scoredFlips) {
            if (flip != null && getInt(flip, "id") == itemId
                    && !"sell".equals(getString(flip, "side"))) {
                return flip;
            }
        }
        return null;
    }

    /**
     * Abort only stuck BUY offers. Filling with {@code quantitySold > 0} is never
     * aborted. Offers we just suggested listing are never aborted on the next tick.
     * SELL offers are not aborted for wiki/cost-basis "margin gone after tax" —
     * that is the list-then-abort loop (ruby necklace, black chinchompa). Odd /
     * untradeable names can still cancel a sell. wide-spread is noise.
     */
    private static boolean shouldAbortOffer(Map<String, Object> market, boolean buy,
                                           boolean fillingProgress, boolean recentlySuggested) {
        if (fillingProgress || recentlySuggested) {
            return false;
        }
        if (!buy) {
            return isOddDead(market);
        }
        return isDeadMargin(market);
    }

    /** Untradeable / odd-named item — the only remaining reason to abort a live sell. */
    private static boolean isOddDead(Map<String, Object> market) {
        return market != null && Boolean.TRUE.equals(market.get("dead")) && hasFlag(market, "odd");
    }

    /**
     * Reprice only when the live quote is clearly off the offer — not 1gp wiki jitter.
     * Threshold is 0.5% of the quote, at least 2gp. Does not invent a price; quoted
     * must already be a positive wiki average.
     */
    private static boolean clearlyMispriced(long offerPrice, long quoted) {
        if (quoted <= 0 || offerPrice == quoted) {
            return false;
        }
        long delta = Math.abs(offerPrice - quoted);
        long threshold = Math.max(2L, quoted / 200L);
        return delta >= threshold;
    }

    /**
     * Same dead-margin predicate for "do not list" and "abort a stuck buy".
     * Non-positive post-tax margin (wiki spread or cost-basis vs avg buy),
     * evaluateItem {@code dead}, or spread vs 1h-average risk cap. Not
     * wide-spread / odd / thin / one-sided / missing data / not-in-top-12.
     */
    private static boolean isDeadMargin(Map<String, Object> market) {
        if (market == null) {
            return false;
        }
        if (Boolean.TRUE.equals(market.get("dead"))) {
            return true;
        }
        if (nonPositiveMargin(market)) {
            return true;
        }
        return hasFlag(market, "spread-blowout");
    }

    private static boolean nonPositiveMargin(Map<String, Object> market) {
        if (market == null || !market.containsKey("margin_post_tax")) {
            return false;
        }
        Object v = market.get("margin_post_tax");
        if (v instanceof Number) {
            return ((Number) v).doubleValue() <= 0.0;
        }
        return false;
    }

    private static String deadReason(Map<String, Object> market) {
        String fromMap = getString(market, "dead_reason");
        if (fromMap != null && !fromMap.isEmpty()) {
            return fromMap;
        }
        if (hasFlag(market, "odd")) {
            return "Odd / untradeable name.";
        }
        if (nonPositiveMargin(market)) {
            return "Margin gone after tax.";
        }
        if (hasFlag(market, "spread-blowout")) {
            return "Spread blew out vs 1h average.";
        }
        return "This flip is no longer viable.";
    }

    private static boolean hasFlag(Map<String, Object> flip, String flag) {
        if (flip == null) {
            return false;
        }
        Object f = flip.get("flags");
        if (!(f instanceof List)) {
            return false;
        }
        for (Object o : (List<?>) f) {
            if (flag.equals(String.valueOf(o))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasActiveOffer(long[][] offersBySlot, int itemId) {
        if (offersBySlot == null) {
            return false;
        }
        for (long[] offer : offersBySlot) {
            if (offer != null && offer.length > O_ITEM_ID && (int) offer[O_ITEM_ID] == itemId) {
                return true;
            }
        }
        return false;
    }

    private static int firstFreeSlot(long[][] offersBySlot, int maxSlots) {
        if (offersBySlot == null) {
            return -1;
        }
        int limit = Math.min(maxSlots, offersBySlot.length);
        for (int i = 0; i < limit; i++) {
            if (offersBySlot[i] == null) {
                return i;
            }
        }
        return -1;
    }

    private static int countUsedSlots(long[][] offersBySlot) {
        if (offersBySlot == null) {
            return 0;
        }
        int count = 0;
        for (long[] offer : offersBySlot) {
            if (offer != null) {
                count++;
            }
        }
        return count;
    }

    private static long getLong(Map<String, Object> map, String key) {
        if (map == null) {
            return 0L;
        }
        Object v = map.get(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return (long) Double.parseDouble((String) v);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static int getInt(Map<String, Object> map, String key) {
        return (int) getLong(map, key);
    }

    private static Double getNullableDouble(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        return null;
    }

    private static String getString(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        return v instanceof String ? (String) v : null;
    }
}
