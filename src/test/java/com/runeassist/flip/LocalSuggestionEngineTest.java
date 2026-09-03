package com.runeassist.flip;

import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.model.SuggestionType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-progress MODIFY must stay owned when the GE slot has already gone EMPTY
 * (modify cancels first) and other slots are free or also mispriced.
 */
public class LocalSuggestionEngineTest {

    private static final int RUBY_NECKLACE = 1660;
    private static final int DRAGON_ARROWS = 11228;

    @Test
    public void ownedModifyBeatsEmptySlotBuyOfAnotherItem() {
        LocalSuggestionEngine.Input in = baseInput();
        in.offersBySlot = new long[8][];
        // Slot 2 was the ruby necklace sell — already cancelled for modify.
        in.ownedModifySlot = 2;
        in.ownedModifyItemId = RUBY_NECKLACE;
        in.ownedModifyBuy = false;
        in.ownedModifyTargetPrice = 2025;
        in.ownedModifyQuantity = 1001;
        in.ownedModifyName = "Ruby necklace";
        in.ownedModifyOfferPrice = 2100;
        in.coins = 50_000_000L;

        Suggestion s = LocalSuggestionEngine.next(in);
        assertEquals(SuggestionType.MODIFY_SELL, s.getType());
        assertEquals(RUBY_NECKLACE, s.getItemId());
        assertEquals(2, s.getBoxId());
    }

    @Test
    public void ownedModifyBeatsOtherFillingOfferModify() {
        LocalSuggestionEngine.Input in = baseInput();
        in.offersBySlot = new long[8][];
        // Dragon arrows still filling and clearly mispriced (would win slot-order MODIFY).
        in.offersBySlot[0] = new long[]{DRAGON_ARROWS, 1, 1858, 10, 5847, 1};
        in.ownedModifySlot = 2;
        in.ownedModifyItemId = RUBY_NECKLACE;
        in.ownedModifyBuy = false;
        in.ownedModifyTargetPrice = 2025;
        in.ownedModifyQuantity = 1001;
        in.ownedModifyName = "Ruby necklace";
        in.ownedModifyOfferPrice = 2100;
        in.coins = 50_000_000L;

        Suggestion s = LocalSuggestionEngine.next(in);
        assertEquals(SuggestionType.MODIFY_SELL, s.getType());
        assertEquals(RUBY_NECKLACE, s.getItemId());
        assertEquals(2, s.getBoxId());
    }

    @Test
    public void withoutOwnedModifyEmptySlotEmitsBuy() {
        LocalSuggestionEngine.Input in = baseInput();
        in.offersBySlot = new long[8][];
        in.coins = 50_000_000L;

        Suggestion s = LocalSuggestionEngine.next(in);
        assertEquals(SuggestionType.BUY, s.getType());
        assertEquals(DRAGON_ARROWS, s.getItemId());
    }

    @Test
    public void ownedModifyUsesLiveSlotWhenOfferStillPresent() {
        LocalSuggestionEngine.Input in = baseInput();
        in.offersBySlot = new long[8][];
        in.offersBySlot[5] = new long[]{RUBY_NECKLACE, 0, 2100, 0, 1001, 1};
        in.ownedModifySlot = 2; // stale boxId
        in.ownedModifyItemId = RUBY_NECKLACE;
        in.ownedModifyBuy = false;
        in.ownedModifyTargetPrice = 2025;
        in.ownedModifyQuantity = 1001;
        in.ownedModifyName = "Ruby necklace";
        in.ownedModifyOfferPrice = 2100;

        Suggestion s = LocalSuggestionEngine.next(in);
        assertEquals(SuggestionType.MODIFY_SELL, s.getType());
        assertEquals(RUBY_NECKLACE, s.getItemId());
        assertEquals(5, s.getBoxId());
        assertTrue(s.getWhy() != null && s.getWhy().contains("reprice"));
    }

    @Test
    public void defaultMinProfitFloorSkipsSmallBuy() {
        LocalSuggestionEngine.Input in = baseInput();
        in.offersBySlot = new long[8][];
        in.coins = 50_000_000L;
        in.minPredictedProfit = 20_000L;
        in.scoredFlips = new ArrayList<>();
        in.scoredFlips.add(flip(DRAGON_ARROWS, "Dragon arrow(p++)", 1770, 1900, 12_000));

        Suggestion s = LocalSuggestionEngine.next(in);
        assertEquals(SuggestionType.WAIT, s.getType());
        assertEquals(LocalSuggestionEngine.waitMinProfit(20_000L), s.getMessage());
        assertTrue(s.getWhy() != null && !s.getWhy().isEmpty());
    }

    @Test
    public void emptyScorerWaitHasVisibleReason() {
        LocalSuggestionEngine.Input in = baseInput();
        in.offersBySlot = new long[8][];
        in.coins = 50_000_000L;
        in.scoredFlips = new ArrayList<>();

        Suggestion s = LocalSuggestionEngine.next(in);
        assertEquals(SuggestionType.WAIT, s.getType());
        assertEquals(LocalSuggestionEngine.WAIT_NO_CANDIDATES, s.getMessage());
        assertTrue(s.getWhy() != null && s.getWhy().contains("slots"));
    }

    @Test
    public void notEnoughCoinsWaitWhenBroke() {
        LocalSuggestionEngine.Input in = baseInput();
        in.offersBySlot = new long[8][];
        in.coins = 0L;

        Suggestion s = LocalSuggestionEngine.next(in);
        assertEquals(SuggestionType.WAIT, s.getType());
        assertEquals(LocalSuggestionEngine.WAIT_NOT_ENOUGH_COINS, s.getMessage());
    }

    @Test
    public void autoMinProfitAllowsSmallBuy() {
        LocalSuggestionEngine.Input in = baseInput();
        in.offersBySlot = new long[8][];
        in.coins = 50_000_000L;
        in.minPredictedProfit = 0L;
        in.scoredFlips = new ArrayList<>();
        in.scoredFlips.add(flip(DRAGON_ARROWS, "Dragon arrow(p++)", 1770, 1900, 12_000));

        Suggestion s = LocalSuggestionEngine.next(in);
        assertEquals(SuggestionType.BUY, s.getType());
        assertEquals(DRAGON_ARROWS, s.getItemId());
    }

    @Test
    public void ownedModifyDeadMarginFallsThroughToBuy() {
        LocalSuggestionEngine.Input in = baseInput();
        in.offersBySlot = new long[8][];
        in.ownedModifySlot = 2;
        in.ownedModifyItemId = RUBY_NECKLACE;
        in.ownedModifyBuy = false;
        in.ownedModifyTargetPrice = 1367;
        in.ownedModifyQuantity = 1813;
        in.ownedModifyName = "Ruby necklace";
        in.ownedModifyOfferPrice = 2100;
        in.coins = 50_000_000L;
        in.scoredFlips = new ArrayList<>();
        in.scoredFlips.add(flip(DRAGON_ARROWS, "Dragon arrow(p++)", 1770, 1900, 421_000));
        in.scoredFlips.add(deadFlip(RUBY_NECKLACE, "Ruby necklace", 1400, 1367));

        Suggestion s = LocalSuggestionEngine.next(in);
        assertEquals(SuggestionType.BUY, s.getType());
        assertEquals(DRAGON_ARROWS, s.getItemId());
    }

    @Test
    public void ownedModifySkippedFallsThroughToBuy() {
        LocalSuggestionEngine.Input in = baseInput();
        in.offersBySlot = new long[8][];
        in.ownedModifySlot = 2;
        in.ownedModifyItemId = RUBY_NECKLACE;
        in.ownedModifyBuy = false;
        in.ownedModifyTargetPrice = 2025;
        in.ownedModifyQuantity = 1813;
        in.ownedModifyName = "Ruby necklace";
        in.skipOfferItemIds = new HashSet<>(Collections.singleton(RUBY_NECKLACE));
        in.coins = 50_000_000L;

        Suggestion s = LocalSuggestionEngine.next(in);
        assertEquals(SuggestionType.BUY, s.getType());
        assertEquals(DRAGON_ARROWS, s.getItemId());
    }

    @Test
    public void ownedModifySlotTakenByOtherItemFallsThroughToBuy() {
        LocalSuggestionEngine.Input in = baseInput();
        in.offersBySlot = new long[8][];
        in.offersBySlot[2] = offer(DRAGON_ARROWS, true, 1858, 10, 5847, 0L);
        in.ownedModifySlot = 2;
        in.ownedModifyItemId = RUBY_NECKLACE;
        in.ownedModifyBuy = false;
        in.ownedModifyTargetPrice = 2025;
        in.ownedModifyQuantity = 1813;
        in.ownedModifyName = "Ruby necklace";
        in.coins = 50_000_000L;

        Suggestion s = LocalSuggestionEngine.next(in);
        assertTrue(s.getItemId() != RUBY_NECKLACE);
        assertTrue(s.getType() != SuggestionType.MODIFY_SELL);
    }

    @Test
    public void heldDeadRubyDoesNotBlockEmptySlotBuy() {
        LocalSuggestionEngine.Input in = baseInput();
        in.offersBySlot = new long[8][];
        in.coins = 50_000_000L;
        in.held = new LinkedHashMap<>();
        in.held.put(RUBY_NECKLACE, new long[]{1813, 1400});
        in.scoredFlips = new ArrayList<>();
        in.scoredFlips.add(flip(DRAGON_ARROWS, "Dragon arrow(p++)", 1770, 1900, 421_000));
        Map<String, Object> rubySell = deadFlip(RUBY_NECKLACE, "Ruby necklace", 1400, 1367);
        rubySell.put("side", "sell");
        in.scoredFlips.add(0, rubySell);

        Suggestion s = LocalSuggestionEngine.next(in);
        assertEquals(SuggestionType.BUY, s.getType());
        assertEquals(DRAGON_ARROWS, s.getItemId());
    }

    @Test
    public void leftoverQtyProtectedFromImmediateAbort() {
        LocalSuggestionEngine.Input in = deadBuyInput(9);
        in.protectAbortItemIds = new HashSet<>(Collections.singleton(VAMPYRE_DUST));

        Suggestion s = LocalSuggestionEngine.next(in);
        assertTrue(s.getType() != SuggestionType.ABORT);
        assertTrue(s.getType() != SuggestionType.MODIFY_BUY);
    }

    @Test
    public void leftoverQtyUnprotectedDeadBuyAborts() {
        LocalSuggestionEngine.Input in = deadBuyInput(9);

        Suggestion s = LocalSuggestionEngine.next(in);
        assertEquals(SuggestionType.ABORT, s.getType());
        assertEquals(VAMPYRE_DUST, s.getItemId());
        assertEquals(9, s.getQuantity());
    }

    @Test
    public void doesNotModifyIntoDeadMargin() {
        LocalSuggestionEngine.Input in = deadBuyInput(9);
        in.protectAbortItemIds = new HashSet<>(Collections.singleton(VAMPYRE_DUST));
        // Offer is clearly off the quote, but the book is dead — must not reprice into it.
        in.offersBySlot[0][2] = 200;

        Suggestion s = LocalSuggestionEngine.next(in);
        assertTrue(s.getType() != SuggestionType.MODIFY_BUY);
        assertTrue(s.getType() != SuggestionType.ABORT);
    }

    @Test
    public void fillingDeadMarginIsNotAbortedUnlessStale() {
        LocalSuggestionEngine.Input in = baseInput();
        in.offersBySlot = new long[8][];
        in.coins = 50_000_000L;
        in.nowMs = 10_000_000L;
        in.timeframeMinutes = 5;
        in.scoredFlips = new ArrayList<>();
        in.scoredFlips.add(deadFlip(BLACK_CHINCHOMPA, "Black chinchompa", 800, 780));
        // Filling, last progress 30 min ago — under 2h, not stale.
        in.offersBySlot[0] = offer(BLACK_CHINCHOMPA, false, 780, 50, 200, in.nowMs - 30L * 60L * 1000L);

        Suggestion s = LocalSuggestionEngine.next(in);
        assertTrue(s.getType() != SuggestionType.ABORT);
    }

    @Test
    public void staleFillingHoldAbortsEvenAtALoss() {
        LocalSuggestionEngine.Input in = baseInput();
        in.offersBySlot = new long[8][];
        in.coins = 50_000_000L;
        in.nowMs = 10_000_000L;
        in.timeframeMinutes = 5;
        in.scoredFlips = new ArrayList<>();
        in.scoredFlips.add(deadFlip(BLACK_CHINCHOMPA, "Black chinchompa", 800, 780));
        long lastFill = in.nowMs - LocalSuggestionEngine.STALE_HOLD_MIN_MS - 1;
        in.offersBySlot[0] = offer(BLACK_CHINCHOMPA, false, 780, 50, 200, lastFill);

        Suggestion s = LocalSuggestionEngine.next(in);
        assertEquals(SuggestionType.ABORT, s.getType());
        assertEquals(BLACK_CHINCHOMPA, s.getItemId());
        assertEquals(LocalSuggestionEngine.WHY_ABORT_STALE, s.getWhy());
    }

    @Test
    public void recentlyListedStaleTimestampStillOwned() {
        LocalSuggestionEngine.Input in = baseInput();
        in.offersBySlot = new long[8][];
        in.coins = 50_000_000L;
        in.nowMs = 10_000_000L;
        in.timeframeMinutes = 5;
        in.protectAbortItemIds = new HashSet<>(Collections.singleton(BLACK_CHINCHOMPA));
        in.scoredFlips = new ArrayList<>();
        in.scoredFlips.add(deadFlip(BLACK_CHINCHOMPA, "Black chinchompa", 800, 780));
        long lastFill = in.nowMs - LocalSuggestionEngine.STALE_HOLD_MIN_MS - 1;
        in.offersBySlot[0] = offer(BLACK_CHINCHOMPA, false, 780, 50, 200, lastFill);

        Suggestion s = LocalSuggestionEngine.next(in);
        assertTrue(s.getType() != SuggestionType.ABORT);
    }

    @Test
    public void staleAfterUsesFourTimesVolumeWindowWhenLargerThanTwoHours() {
        assertEquals(LocalSuggestionEngine.STALE_HOLD_MIN_MS, LocalSuggestionEngine.staleAfterMs(5));
        assertEquals(4L * 60L * 60L * 1000L, LocalSuggestionEngine.staleAfterMs(60));
    }

    private static final int VAMPYRE_DUST = 3325;
    private static final int BLACK_CHINCHOMPA = 11959;

    private static LocalSuggestionEngine.Input deadBuyInput(int leftover) {
        LocalSuggestionEngine.Input in = baseInput();
        in.offersBySlot = new long[8][];
        in.coins = 50_000_000L;
        in.scoredFlips = new ArrayList<>();
        in.scoredFlips.add(deadFlip(VAMPYRE_DUST, "Vampyre dust", 250, 240));
        in.offersBySlot[0] = offer(VAMPYRE_DUST, true, 250, 0, leftover, 0L);
        return in;
    }

    private static long[] offer(int itemId, boolean buy, long price, int sold, int total, long lastProgressMs) {
        return new long[]{ itemId, buy ? 1 : 0, price, sold, total, 1, lastProgressMs };
    }

    private static Map<String, Object> deadFlip(int id, String name, long buyAt, long sellAt) {
        Map<String, Object> m = flip(id, name, buyAt, sellAt, -1_000);
        m.put("dead", Boolean.TRUE);
        m.put("margin_post_tax", (double) (sellAt - buyAt));
        m.put("dead_reason", "Margin gone after tax.");
        return m;
    }

    private static LocalSuggestionEngine.Input baseInput() {
        LocalSuggestionEngine.Input in = new LocalSuggestionEngine.Input();
        in.maxSlots = 8;
        in.scoredFlips = new ArrayList<>();
        in.scoredFlips.add(flip(DRAGON_ARROWS, "Dragon arrow(p++)", 1770, 1900, 421_000));
        in.scoredFlips.add(flip(RUBY_NECKLACE, "Ruby necklace", 1900, 2025, 12_000));
        in.held = new LinkedHashMap<>();
        return in;
    }

    private static Map<String, Object> flip(int id, String name, long buyAt, long sellAt, long profit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("buy_at", buyAt);
        m.put("sell_at", sellAt);
        m.put("margin_post_tax", (double) (sellAt - buyAt));
        m.put("projected_profit", (double) profit);
        m.put("suggested_qty", 100L);
        m.put("est_fill_hours", 1.0);
        m.put("ge_limit", 11000);
        return m;
    }
}
