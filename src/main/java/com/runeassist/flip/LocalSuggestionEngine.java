package com.runeassist.flip;

import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.model.SuggestionType;
import com.runeassist.flip.util.Constants;

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

    // offersBySlot entry layout: {itemId, buyIs1, price, sold, total, fillingIs1, lastProgressMs?, listedMs?}
    private static final int O_ITEM_ID = 0;
    private static final int O_BUY_IS_1 = 1;
    private static final int O_PRICE = 2;
    private static final int O_SOLD = 3;
    private static final int O_TOTAL = 4;
    private static final int O_FILLING_IS_1 = 5;
    private static final int O_LAST_PROGRESS_MS = 6;
    private static final int O_LISTED_MS = 7;

    /**
     * Don't reprice an offer within this long of being listed unless it's mispriced by more
     * than {@link #MODIFY_GRACE_THRESHOLD_MULT}x the normal bar. Without this, a listing that
     * happens to sit a fraction of a percent off the live wiki quote (routine short-term
     * jitter, not a real mispricing) gets an immediate MODIFY suggestion seconds after being
     * placed — "just told me to list it, then immediately wanted to modify it."
     */
    static final long MODIFY_GRACE_MS = 90_000L;
    private static final long MODIFY_GRACE_THRESHOLD_MULT = 3L;

    static final String WHY_ABORT_STALE = "No fill progress — cancel this offer";
    /** Minimum stall before a filling (or loss-making) hold is abandoned. */
    static final long STALE_HOLD_MIN_MS = 2L * 60L * 60L * 1000L;

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
    static final String WAIT_NO_CANDIDATES =
        "Market scorer returned no flips.";
    static final String WAIT_ARES_DOWN =
        "Ares is down — local scorer found no flips.";
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
         * Item ids we suggested BUY/SELL/MODIFY for this session recently, or that
         * just listed. Do not ABORT those live offers for ~10 min (list-then-abort,
         * including leftover qty after a GE modify).
         */
        public Set<Integer> protectAbortItemIds = Collections.emptySet();
        /**
         * In-progress MODIFY: the user clicked the highlight / has the offer editor
         * open for this listing. Empty remaining slots must not outrank it with BUY/SELL
         * of another item (the cancelled-then-relist GE modify flow makes the slot look
         * empty). Same owned-offer idea as list-then-abort.
         */
        public int ownedModifySlot = -1;
        public int ownedModifyItemId = 0;
        public boolean ownedModifyBuy;
        public long ownedModifyTargetPrice;
        public int ownedModifyQuantity;
        public String ownedModifyName = "";
        public long ownedModifyOfferPrice;
        /** itemId -> remaining 4h GE buy-limit. Missing key = unknown (do not cap). 0 = exhausted. */
        public Map<Integer, Integer> remainingBuyLimit = Collections.emptyMap();
        public long minPredictedProfit;
        /** Offer-adjust timeframe in minutes (volume window). Used for stale-hold abort. */
        public int timeframeMinutes = 5;
        /** Clock for stale-hold / tests. 0 = use {@link System#currentTimeMillis()}. */
        public long nowMs;
    }

    /**
     * Choose the next suggestion.
     *
     * @param scoredFlips  our market flips, best first (each a Map of the documented keys)
     * @param offersBySlot length 8; each entry null (empty slot) or
     *                     {itemId, buyIs1, price, sold, total, fillingIs1, lastProgressMs?}
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

        // 0) In-progress MODIFY is owned: keep it even if the GE slot already went
        //    EMPTY (modify cancels first, then opens the editor). Do this before
        //    scanning other filling offers or empty-slot BUY/SELL.
        if (in.ownedModifyItemId > 0) {
            Suggestion owned = nextOwnedModify(in, scoredFlips, remainingLimit, skipped, blocked, skipOffers);
            if (owned != null) {
                return owned;
            }
        }

        // 1) Live offers are owned until they fill, stall, or Skip. Never abort an
        //    offer we just listed or modified (~10 min protect, including leftover
        //    qty after a GE cancel-relist). Filling (sold > 0) is not aborted for
        //    "margin gone after tax" unless the hold is stale. Do not SELL/BUY/MODIFY
        //    into a dead margin. User-skipped items are left alone.
        long nowMs = in.nowMs > 0L ? in.nowMs : System.currentTimeMillis();
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
                long lastProgressMs = offer.length > O_LAST_PROGRESS_MS
                    ? offer[O_LAST_PROGRESS_MS] : 0L;
                boolean stale = isStaleHold(lastProgressMs, in.timeframeMinutes, nowMs);
                Map<String, Object> market = findMarketFlip(scoredFlips, offerItemId);
                Map<String, Object> sellRow = findSellFlip(scoredFlips, offerItemId);
                if (shouldAbortOffer(market, buy, fillingProgress, recentlySuggested, stale)) {
                    String name = getString(market, "name");
                    if (name == null) {
                        name = getString(sellRow, "name");
                    }
                    Suggestion abort = build(SuggestionType.ABORT, slot, offerItemId,
                            offer[O_PRICE], remaining, name, null, null);
                    applyLimit(abort, market != null ? market : sellRow, remainingLimit);
                    applyWhy(abort, market != null ? market : sellRow, offer[O_PRICE], stale);
                    return abort;
                }

                // BUY reprice needs a market quote, not a cost-basis sell row.
                // SELL rows carry the actual held cost basis. A market row can still
                // have a positive spread while repricing below this player's break-even.
                Map<String, Object> quote = buy ? market : (sellRow != null ? sellRow : market);
                if (quote == null) {
                    continue;
                }
                // Never reprice into a book we'd abort (dead margin / leftover loop).
                if (isDeadMargin(quote) || isDeadMargin(market)) {
                    continue;
                }
                long offerPrice = offer[O_PRICE];
                String name = getString(quote, "name");
                Double hours = getNullableDouble(quote, "est_fill_hours");
                Double profit = modifyProfit(quote, remaining);
                if (buy && belowMinProfit(profit, in.minPredictedProfit)) {
                    continue;
                }
                long listedMs = offer.length > O_LISTED_MS ? offer[O_LISTED_MS] : 0L;
                boolean inModifyGrace = listedMs > 0L && nowMs - listedMs < MODIFY_GRACE_MS;

                if (buy) {
                    long buyAt = getLong(quote, "buy_at");
                    if (clearlyMispriced(offerPrice, buyAt, inModifyGrace)) {
                        Suggestion s = build(SuggestionType.MODIFY_BUY, slot, offerItemId,
                                buyAt, remaining, name, profit, hours);
                        applyLimit(s, quote, remainingLimit);
                        applyWhy(s, quote, offerPrice);
                        return s;
                    }
                } else {
                    long sellAt = getLong(quote, "sell_at");
                    if (clearlyMispriced(offerPrice, sellAt, inModifyGrace)) {
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
        boolean sawBelowMinProfit = false;
        boolean sawBuyRow = false;

        if (scoredFlips != null && !slotsFull) {
            for (Map<String, Object> flip : scoredFlips) {
                if (flip == null) {
                    continue;
                }
                if ("sell".equals(getString(flip, "side"))) {
                    continue;
                }
                sawBuyRow = true;
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
                    sawBelowMinProfit = true;
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
                sawBuyRow = true;
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
                sawLimitExhausted, sawBlocked, sawPriced, sawBelowMinProfit, sawBuyRow,
                in.minPredictedProfit, coins), offersBySlot, maxSlots, scoredFlips, held, nowMs);
    }

    /**
     * Keep the MODIFY the user is already acting on. The live slot is often EMPTY
     * because GE modify cancels first; other filling offers and free slots must not
     * replace it with BUY/SELL/MODIFY of a different item.
     *
     * <p>Returns {@code null} when this lock cannot be honored (skipped / blocked /
     * dead margin / slot now holds a different item) so empty-slot BUY can run.
     */
    private static Suggestion nextOwnedModify(Input in, List<Map<String, Object>> scoredFlips,
                                              Map<Integer, Integer> remainingLimit,
                                              Set<Integer> skipped, Set<Integer> blocked,
                                              Set<Integer> skipOffers) {
        int itemId = in.ownedModifyItemId;
        if (itemId <= 0) {
            return null;
        }
        if (skipped.contains(itemId) || blocked.contains(itemId) || skipOffers.contains(itemId)) {
            return null;
        }
        int slot = in.ownedModifySlot;
        boolean buy = in.ownedModifyBuy;
        int qty = Math.max(0, in.ownedModifyQuantity);
        long offerPrice = in.ownedModifyOfferPrice;
        long[][] offers = in.offersBySlot;
        boolean foundOnGe = false;
        if (offers != null) {
            int matched = -1;
            if (slot >= 0 && slot < offers.length && offers[slot] != null
                    && offers[slot].length > O_ITEM_ID
                    && (int) offers[slot][O_ITEM_ID] == itemId) {
                matched = slot;
            } else {
                for (int i = 0; i < offers.length; i++) {
                    long[] offer = offers[i];
                    if (offer != null && offer.length > O_ITEM_ID && (int) offer[O_ITEM_ID] == itemId) {
                        matched = i;
                        break;
                    }
                }
            }
            if (matched >= 0) {
                foundOnGe = true;
                long[] offer = offers[matched];
                slot = matched;
                buy = offer.length > O_BUY_IS_1 && offer[O_BUY_IS_1] == 1L;
                if (offer.length > O_PRICE) {
                    offerPrice = offer[O_PRICE];
                }
                if (offer.length > O_TOTAL) {
                    int remaining = (int) Math.max(0L, offer[O_TOTAL] - (offer.length > O_SOLD ? offer[O_SOLD] : 0L));
                    if (remaining > 0) {
                        qty = remaining;
                    }
                }
            } else if (slot >= 0 && slot < offers.length && offers[slot] != null
                    && offers[slot].length > O_ITEM_ID
                    && (int) offers[slot][O_ITEM_ID] > 0
                    && (int) offers[slot][O_ITEM_ID] != itemId) {
                // Live offer in the owned slot is a different item — lock is stale.
                return null;
            }
        }
        Map<String, Object> market = findMarketFlip(scoredFlips, itemId);
        Map<String, Object> sellRow = findSellFlip(scoredFlips, itemId);
        Map<String, Object> quote = buy ? market : (sellRow != null ? sellRow : market);
        if (isDeadMargin(quote) || isDeadMargin(market)) {
            return null;
        }
        long target = in.ownedModifyTargetPrice;
        if (quote != null) {
            long quoted = buy ? getLong(quote, "buy_at") : getLong(quote, "sell_at");
            if (quoted > 0) {
                target = quoted;
            }
        }
        if (target <= 0) {
            target = offerPrice;
        }
        if (target <= 0 && !foundOnGe) {
            return null;
        }
        if (qty <= 0) {
            qty = 1;
        }
        String name = in.ownedModifyName;
        if (name == null || name.isEmpty()) {
            name = getString(quote, "name");
        }
        Double hours = getNullableDouble(quote, "est_fill_hours");
        Double profit = modifyProfit(quote, qty);
        if (buy && belowMinProfit(profit, in.minPredictedProfit)) {
            return null;
        }
        Suggestion s = build(buy ? SuggestionType.MODIFY_BUY : SuggestionType.MODIFY_SELL,
                Math.max(slot, 0), itemId, target, qty, name, profit, hours);
        applyLimit(s, quote != null ? quote : sellRow, remainingLimit);
        applyWhy(s, quote != null ? quote : sellRow, offerPrice > 0 ? offerPrice : target);
        return s;
    }

    /**
     * First matching reason: slots, 4h limit, coins, skipped/blocked, profit floor,
     * empty scorer, then no margin. Never returns {@link #WAIT_GENERIC}.
     */
    private static String waitReason(boolean slotsFull, boolean anyUnblocked,
                                     boolean anyWithLimitLeft, boolean anyAffordable,
                                     boolean sawLimitExhausted, boolean sawBlocked,
                                     boolean sawPriced, boolean sawBelowMinProfit,
                                     boolean sawBuyRow, long minPredictedProfit, long coins) {
        if (slotsFull) {
            return WAIT_SLOTS_FULL;
        }
        if (!anyWithLimitLeft && sawLimitExhausted) {
            return WAIT_LIMIT_EXHAUSTED;
        }
        if ((sawPriced && !anyAffordable) || coins < Constants.MIN_GP_NEEDED_TO_FLIP) {
            return WAIT_NOT_ENOUGH_COINS;
        }
        if (!anyUnblocked && sawBlocked) {
            return WAIT_SKIPPED_BLOCKED;
        }
        if (sawBelowMinProfit && minPredictedProfit > 0) {
            return waitMinProfit(minPredictedProfit);
        }
        if (!sawBuyRow) {
            return WAIT_NO_CANDIDATES;
        }
        return WAIT_NO_MARGIN;
    }

    static String waitMinProfit(long minPredictedProfit) {
        return "No flips pass the " + compact(minPredictedProfit) + " gp profit floor.";
    }

    /** Fallback Wait with a specific reason + slot numbers. Never uses {@link #WAIT_GENERIC}. */
    static Suggestion waitFallback(String message, long[][] offersBySlot, int maxSlots) {
        return wait(message == null || message.isEmpty() ? WAIT_NO_MARGIN : message,
                offersBySlot, maxSlots, null, null, 0L);
    }

    private static Suggestion wait(String message, long[][] offersBySlot, int maxSlots,
                                   List<Map<String, Object>> scoredFlips, Map<Integer, long[]> held,
                                   long nowMs) {
        Suggestion wait = new Suggestion();
        wait.setType(SuggestionType.WAIT);
        wait.setBoxId(-1);
        wait.setName("");
        wait.setMessage(message);
        String why = waitWhy(offersBySlot, maxSlots, scoredFlips, held, nowMs);
        if (why != null && !why.isEmpty()) {
            wait.setWhy(why);
        }
        return wait;
    }

    /**
     * Slot count plus the furthest-along filling offer. When the board is full, also
     * the age of the stalest filling offer ({@code lastProgressMs}) so WAIT is actionable.
     */
    private static String waitWhy(long[][] offersBySlot, int maxSlots,
                                  List<Map<String, Object>> scoredFlips, Map<Integer, long[]> held,
                                  long nowMs) {
        int cap = maxSlots > 0 ? maxSlots : 8;
        int used = countUsedSlots(offersBySlot);
        StringBuilder sb = new StringBuilder();
        sb.append(used).append("/").append(cap).append(" slots");
        long[] best = null;
        long oldestProgress = Long.MAX_VALUE;
        boolean finishedCollectable = false;
        if (offersBySlot != null) {
            for (long[] offer : offersBySlot) {
                if (offer == null || offer.length < 6) {
                    continue;
                }
                int remaining = (int) Math.max(0L, offer[O_TOTAL] - offer[O_SOLD]);
                if (offer[O_FILLING_IS_1] != 1L) {
                    if ((int) offer[O_ITEM_ID] > 0 && remaining <= 0) {
                        finishedCollectable = true;
                    }
                    continue;
                }
                if (offer[O_SOLD] > 0L && offer[O_TOTAL] > 0L) {
                    if (best == null || offer[O_SOLD] > best[O_SOLD]) {
                        best = offer;
                    }
                }
                if (offer.length > O_LAST_PROGRESS_MS) {
                    long lastProgress = offer[O_LAST_PROGRESS_MS];
                    if (lastProgress > 0L && lastProgress < oldestProgress) {
                        oldestProgress = lastProgress;
                    }
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
        if (finishedCollectable && used >= cap) {
            sb.append(" · collect finished offer");
        }
        int heldKinds = countHeldKinds(held);
        if (heldKinds > 0 && used >= cap) {
            sb.append(" · ").append(heldKinds)
                    .append(heldKinds == 1 ? " held item" : " held items")
                    .append(" waiting for a slot");
        }
        if (used >= cap && oldestProgress != Long.MAX_VALUE) {
            long now = nowMs > 0L ? nowMs : System.currentTimeMillis();
            long ageMs = now - oldestProgress;
            if (ageMs >= 60_000L) {
                sb.append(" · stale ").append(compactAge(ageMs));
            }
        }
        return sb.toString();
    }

    /** Compact age for the WAIT why-line: {@code 18m}, {@code 2h}, {@code 2h 5m}, {@code 1d}. */
    static String compactAge(long ms) {
        long minutes = Math.max(1L, ms / 60_000L);
        if (minutes < 60L) {
            return minutes + "m";
        }
        long hours = minutes / 60L;
        long remMin = minutes % 60L;
        if (hours < 24L) {
            return remMin == 0L ? hours + "h" : hours + "h " + remMin + "m";
        }
        long days = hours / 24L;
        long remHours = hours % 24L;
        return remHours == 0L ? days + "d" : days + "d " + remHours + "h";
    }

    private static int countHeldKinds(Map<Integer, long[]> held) {
        if (held == null || held.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (long[] h : held.values()) {
            if (h != null && h.length > 0 && h[0] > 0L) {
                n++;
            }
        }
        return n;
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

    private static boolean belowMinProfit(Double profit, long minPredictedProfit) {
        return minPredictedProfit > 0 && (profit == null || profit < minPredictedProfit);
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
        applyWhy(s, flip, offerPrice, false);
    }

    private static void applyWhy(Suggestion s, Map<String, Object> flip, long offerPrice, boolean staleAbort) {
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
                why = whyAbort(flip, staleAbort);
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

    private static String whyAbort(Map<String, Object> market, boolean stale) {
        if (stale) {
            return WHY_ABORT_STALE;
        }
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

    /** Cost-basis sell row for an item, or null. */
    private static Map<String, Object> findSellFlip(List<Map<String, Object>> scoredFlips, int itemId) {
        if (scoredFlips == null) {
            return null;
        }
        for (Map<String, Object> flip : scoredFlips) {
            if (flip != null && getInt(flip, "id") == itemId
                    && "sell".equals(getString(flip, "side"))) {
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
     * Abort a stuck BUY for dead margin, an odd-named SELL, or any hold (buy or sell)
     * that has made no {@code quantitySold} progress for the stale stall — even if it
     * is filling and even if post-tax vs cost is negative. Never abort an offer we
     * just listed or modified ({@code recentlySuggested}, ~10 min). Filling with
     * {@code quantitySold > 0} is not aborted for "margin gone after tax" unless stale.
     */
    private static boolean shouldAbortOffer(Map<String, Object> market, boolean buy,
                                           boolean fillingProgress, boolean recentlySuggested,
                                           boolean stale) {
        if (recentlySuggested) {
            return false;
        }
        if (stale) {
            return true;
        }
        if (fillingProgress) {
            return false;
        }
        if (!buy) {
            return isOddDead(market);
        }
        return isDeadMargin(market);
    }

    /**
     * No {@code quantitySold} increase for {@code max(2h, 4 × volume-window minutes)}.
     * Unknown last-progress (0) is not treated as stale.
     */
    static boolean isStaleHold(long lastProgressMs, int timeframeMinutes, long nowMs) {
        if (lastProgressMs <= 0L || nowMs <= lastProgressMs) {
            return false;
        }
        return nowMs - lastProgressMs >= staleAfterMs(timeframeMinutes);
    }

    static long staleAfterMs(int timeframeMinutes) {
        long tfMin = Math.max(1, timeframeMinutes);
        return Math.max(STALE_HOLD_MIN_MS, 4L * tfMin * 60L * 1000L);
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
    /**
     * Reprice only when the live quote is clearly off the offer. Base threshold is 0.5% of
     * the quote (min 2gp) — normal short-term wiki jitter shouldn't trigger a reprice. Within
     * {@link #MODIFY_GRACE_MS} of listing, that threshold widens {@link
     * #MODIFY_GRACE_THRESHOLD_MULT}x (~1.5%) so a freshly-placed offer isn't immediately
     * flagged for a routine price move — only a genuinely bad initial price still corrects
     * quickly during the grace window.
     */
    private static boolean clearlyMispriced(long offerPrice, long quoted, boolean inModifyGrace) {
        if (quoted <= 0 || offerPrice == quoted) {
            return false;
        }
        long delta = Math.abs(offerPrice - quoted);
        long threshold = Math.max(2L, quoted / 200L);
        if (inModifyGrace) {
            threshold *= MODIFY_GRACE_THRESHOLD_MULT;
        }
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

    /**
     * True only while a live BUYING/SELLING offer still has remaining quantity.
     * A finished BOUGHT/SOLD box (awaiting collect) must not block selling the
     * just-filled stack into a free slot — otherwise the engine skips SELL and
     * emits another BUY (e.g. runes never highlighted to sell).
     */
    private static boolean hasActiveOffer(long[][] offersBySlot, int itemId) {
        if (offersBySlot == null) {
            return false;
        }
        for (long[] offer : offersBySlot) {
            if (offer == null || offer.length <= O_ITEM_ID) {
                continue;
            }
            if ((int) offer[O_ITEM_ID] != itemId) {
                continue;
            }
            boolean filling = offer.length > O_FILLING_IS_1 && offer[O_FILLING_IS_1] == 1L;
            int remaining = offer.length > O_TOTAL
                    ? (int) Math.max(0L, offer[O_TOTAL] - offer[O_SOLD])
                    : 0;
            if (filling && remaining > 0) {
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
