package com.runeassist.flip;

import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.model.SuggestionType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Held Super defence(4) — including GE-collected notes — must be a SELL/list, not
 * a buy-more-3s-to-decant suggestion, whenever a GE slot is free.
 */
public class DecantTrackerTest {

    // Wiki/GE unnoted ids; notes are +1 for these potions.
    private static final int SUPER_DEFENCE_3 = 2436;
    private static final int SUPER_DEFENCE_3_NOTED = 2437;
    private static final int SUPER_DEFENCE_4 = 2440;
    private static final int SUPER_DEFENCE_4_NOTED = 2441;
    private static final long BUY_QTY = 148L;

    @Test
    public void heldFourDoseIsNeedSell() {
        Map<Integer, Long> owned = new HashMap<>();
        owned.put(SUPER_DEFENCE_4, 8L);

        assertEquals(DecantTracker.Phase.NEED_SELL,
                DecantTracker.phaseFor(owned, SUPER_DEFENCE_3, BUY_QTY, SUPER_DEFENCE_4));
    }

    @Test
    public void notedHeldFourDoseCollapsesToNeedSell() {
        Map<Integer, Long> raw = new HashMap<>();
        raw.put(SUPER_DEFENCE_4_NOTED, 8L);

        Map<Integer, Long> owned = DecantTracker.collapseToUnnoted(raw, DecantTrackerTest::unnoteDefence);

        assertEquals(8L, DecantTracker.ownedQty(owned, SUPER_DEFENCE_4));
        assertEquals(0L, DecantTracker.ownedQty(owned, SUPER_DEFENCE_4_NOTED));
        assertEquals(DecantTracker.Phase.NEED_SELL,
                DecantTracker.phaseFor(owned, SUPER_DEFENCE_3, BUY_QTY, SUPER_DEFENCE_4));
    }

    @Test
    public void notedAndUnnotedFoursMerge() {
        Map<Integer, Long> raw = new HashMap<>();
        raw.put(SUPER_DEFENCE_4, 2L);
        raw.put(SUPER_DEFENCE_4_NOTED, 6L);

        Map<Integer, Long> owned = DecantTracker.collapseToUnnoted(raw, DecantTrackerTest::unnoteDefence);

        assertEquals(8L, DecantTracker.ownedQty(owned, SUPER_DEFENCE_4));
        assertEquals(DecantTracker.Phase.NEED_SELL,
                DecantTracker.phaseFor(owned, SUPER_DEFENCE_3, BUY_QTY, SUPER_DEFENCE_4));
    }

    @Test
    public void noFoursIsNeedBuy() {
        Map<Integer, Long> owned = new HashMap<>();
        owned.put(SUPER_DEFENCE_3, 20L);

        assertEquals(DecantTracker.Phase.NEED_BUY,
                DecantTracker.phaseFor(owned, SUPER_DEFENCE_3, BUY_QTY, SUPER_DEFENCE_4));
    }

    @Test
    public void notedThreesCountTowardNeedDecant() {
        Map<Integer, Long> raw = new HashMap<>();
        raw.put(SUPER_DEFENCE_3_NOTED, BUY_QTY);

        Map<Integer, Long> owned = DecantTracker.collapseToUnnoted(raw, DecantTrackerTest::unnoteDefence);

        assertEquals(DecantTracker.Phase.NEED_DECANT,
                DecantTracker.phaseFor(owned, SUPER_DEFENCE_3, BUY_QTY, SUPER_DEFENCE_4));
    }

    @Test
    public void heldFourAndFreeSlotPrefersSellOverBuyThree() {
        Suggestion engineBuyThree = suggestion(SuggestionType.BUY, SUPER_DEFENCE_3, 13_000);
        Suggestion decantSellFour = suggestion(SuggestionType.SELL, SUPER_DEFENCE_4, 2_000);

        assertTrue(RuneAssistSuggestionSource.preferDecant(engineBuyThree, decantSellFour),
                "listing held (4)s must beat buying more (3)s even when the buy batch projects more profit");
        assertFalse(RuneAssistSuggestionSource.preferDecant(decantSellFour, engineBuyThree),
                "buy-toward-decant must not replace an engine SELL of held (4)s");
    }

    @Test
    public void noFoursKeepsBuyTowardDecantOverWait() {
        Map<Integer, Long> owned = new HashMap<>();
        assertEquals(DecantTracker.Phase.NEED_BUY,
                DecantTracker.phaseFor(owned, SUPER_DEFENCE_3, BUY_QTY, SUPER_DEFENCE_4));

        Suggestion wait = suggestion(SuggestionType.WAIT, 0, null);
        Suggestion buyThree = suggestion(SuggestionType.BUY, SUPER_DEFENCE_3, 13_000);

        assertTrue(RuneAssistSuggestionSource.preferDecant(wait, buyThree));
    }

    @Test
    public void buyTowardDecantStillBeatsLowerProfitUnrelatedBuy() {
        Suggestion otherBuy = suggestion(SuggestionType.BUY, 11228, 5_000);
        Suggestion buyThree = suggestion(SuggestionType.BUY, SUPER_DEFENCE_3, 13_000);

        assertTrue(RuneAssistSuggestionSource.preferDecant(otherBuy, buyThree));
    }

    @Test
    public void abortIsNotPreemptedBySellFour() {
        Suggestion abort = suggestion(SuggestionType.ABORT, 4151, 0.0);
        Suggestion sellFour = suggestion(SuggestionType.SELL, SUPER_DEFENCE_4, 2_000);

        assertFalse(RuneAssistSuggestionSource.preferDecant(abort, sellFour));
    }

    private static int unnoteDefence(int itemId) {
        if (itemId == SUPER_DEFENCE_4_NOTED) return SUPER_DEFENCE_4;
        if (itemId == SUPER_DEFENCE_3_NOTED) return SUPER_DEFENCE_3;
        return itemId;
    }

    private static Suggestion suggestion(SuggestionType type, int itemId, double profit) {
        return suggestion(type, itemId, Double.valueOf(profit));
    }

    private static Suggestion suggestion(SuggestionType type, int itemId, Double profit) {
        Suggestion s = new Suggestion();
        s.setType(type);
        s.setItemId(itemId);
        s.setId(itemId);
        s.setExpectedProfit(profit);
        return s;
    }
}
