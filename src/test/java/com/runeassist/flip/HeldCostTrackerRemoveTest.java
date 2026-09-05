package com.runeassist.flip;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Removal from the portfolio panel. FC did this over their cloud endpoint, which this fork
 * never reaches, so it silently did nothing; these cover the local replacement. Persistence
 * is a no-op here (the injected ConfigManager is absent, and both load and save swallow it),
 * so what is asserted is the in-memory lot state.
 */
public class HeldCostTrackerRemoveTest {

    private static final String ACC = "Bof118";
    private static final int ANTIFIRE_4 = 2452;
    private static final int WHIP = 4151;

    @Test
    public void removeAllDropsEveryLotForThatItemOnly() {
        HeldCostTracker t = new HeldCostTracker();
        t.addManualLot(ACC, ANTIFIRE_4, 2000, 837);
        t.addManualLot(ACC, WHIP, 3, 1_500_000);

        assertEquals(2000, t.removeLots(ACC, ANTIFIRE_4, 0));

        Map<Integer, long[]> held = t.held(ACC);
        assertFalse(held.containsKey(ANTIFIRE_4));
        assertEquals(3, held.get(WHIP)[0]);
    }

    @Test
    public void removeXTakesTheOldestLotsFirstAndKeepsTheRest() {
        HeldCostTracker t = new HeldCostTracker();
        t.addManualLot(ACC, ANTIFIRE_4, 500, 800);   // oldest
        t.addManualLot(ACC, ANTIFIRE_4, 500, 900);

        assertEquals(600, t.removeLots(ACC, ANTIFIRE_4, 600));

        long[] left = t.held(ACC).get(ANTIFIRE_4);
        assertEquals(400, left[0]);
        assertEquals(900, left[1]); // the 800 lot went first, so only the 900 basis survives
    }

    @Test
    public void removingMoreThanHeldRemovesWhatIsThereAndReportsIt() {
        HeldCostTracker t = new HeldCostTracker();
        t.addManualLot(ACC, ANTIFIRE_4, 10, 800);

        assertEquals(10, t.removeLots(ACC, ANTIFIRE_4, 999));
        assertTrue(t.held(ACC).isEmpty());
        // Nothing left to remove -- must report 0 rather than a negative or stale count.
        assertEquals(0, t.removeLots(ACC, ANTIFIRE_4, 5));
    }

    @Test
    public void clearEmptiesOnlyTheCallingAccount() {
        HeldCostTracker t = new HeldCostTracker();
        t.addManualLot(ACC, ANTIFIRE_4, 2000, 837);
        t.addManualLot(ACC, WHIP, 3, 1_500_000);
        t.addManualLot("ColdTyres", WHIP, 7, 1_400_000);

        assertEquals(2003, t.clearLots(ACC));

        assertTrue(t.held(ACC).isEmpty());
        assertEquals(7, t.held("ColdTyres").get(WHIP)[0]);
    }
}
