package com.runeassist.flip;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase / dose helpers; suggestion action selection is server-side via /v1/suggestion. */
public class DecantTrackerTest {

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

    private static int unnoteDefence(int itemId) {
        if (itemId == SUPER_DEFENCE_4_NOTED) return SUPER_DEFENCE_4;
        if (itemId == SUPER_DEFENCE_3_NOTED) return SUPER_DEFENCE_3;
        return itemId;
    }

    @Test
    public void doseFamilyRecognisesPotionsAndRejectsEverythingElse() {
        assertEquals("Super strength", DecantTracker.doseFamily("Super strength(3)"));
        assertEquals("Prayer potion", DecantTracker.doseFamily("Prayer potion(4)"));
        assertEquals("Divine super combat potion", DecantTracker.doseFamily("Divine super combat potion(1)"));
        assertNull(DecantTracker.doseFamily("Abyssal whip"));
        assertNull(DecantTracker.doseFamily("Super strength(0)"));
        assertNull(DecantTracker.doseFamily("Super strength(12)"));
        assertNull(DecantTracker.doseFamily("Bracelet of ethereum (uncharged)"));
        assertNull(DecantTracker.doseFamily("(3)"));
        assertNull(DecantTracker.doseFamily(null));
    }

    @Test
    public void doseConservingShiftRecognisesARealDecant() {
        assertTrue(DecantTracker.isDoseConservingShift(208, 3, 156, 4));
        assertTrue(DecantTracker.isDoseConservingShift(4, 1, 1, 4));
    }

    @Test
    public void doseConservingShiftRejectsAnythingThatDoesNotBalance() {
        assertFalse(DecantTracker.isDoseConservingShift(208, 3, 155, 4));
        assertFalse(DecantTracker.isDoseConservingShift(10, 3, 10, 3));
        assertFalse(DecantTracker.isDoseConservingShift(0, 3, 156, 4));
        assertFalse(DecantTracker.isDoseConservingShift(208, 3, 0, 4));
        assertFalse(DecantTracker.isDoseConservingShift(-208, 3, 156, 4));
        assertFalse(DecantTracker.isDoseConservingShift(208, 0, 156, 4));
    }
}
