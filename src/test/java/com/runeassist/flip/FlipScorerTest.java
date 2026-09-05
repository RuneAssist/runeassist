package com.runeassist.flip;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The only scoring arithmetic left client-side: valuing a decant of stock already held.
 *  Flip ranking, decant ranking and per-item health are server-side, so none of their
 *  scoring is duplicated or tested here. */
public class FlipScorerTest {

    @Test
    public void decantingHeldStockBeatsSellingItAsIs() {
        // The case that motivated this, measured live: 208x Super strength(3) held, bought at
        // 2375ea. Selling as-is at 2403 (2% tax = 48) nets 489,840 -- a loss against the
        // 494,000 spent. Decanting to 156x (4) and selling at 3300 (tax 66) nets 504,504.
        long gain = FlipScorer.decantGainOverRawSell(208, 3, 4, 2403, 48, 3300, 66);
        assertEquals(504_504L - 489_840L, gain);
        assertTrue(gain > 0);
    }

    @Test
    public void decantGainIsNegativeWhenConvertingLosesValue() {
        // Same shape, but the 4-dose has fallen below 4/3 of the 3-dose: converting destroys
        // value and must not be suggested.
        assertTrue(FlipScorer.decantGainOverRawSell(208, 3, 4, 2403, 48, 3000, 60) < 0);
    }

    @Test
    public void decantGainFloorsPartialBottlesAndRejectsNonsense() {
        // 5x 3-dose = 15 doses -> 3x 4-dose (12 doses); the leftover 3 doses convert to
        // nothing and must not be counted as if they did.
        assertEquals(3 * (3300 - 66) - 5 * (2403 - 48),
            FlipScorer.decantGainOverRawSell(5, 3, 4, 2403, 48, 3300, 66));
        assertEquals(0, FlipScorer.decantGainOverRawSell(0, 3, 4, 2403, 48, 3300, 66));
        assertEquals(0, FlipScorer.decantGainOverRawSell(208, 3, 0, 2403, 48, 3300, 66));
    }

}
