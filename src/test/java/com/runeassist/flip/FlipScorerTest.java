package com.runeassist.flip;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure scoring arithmetic shared with flip-scorer.mjs; the ranking loop itself needs wiki data. */
public class FlipScorerTest {

    @Test
    public void fillEstimateCoversBothLegsAtOurLiquidityShare() {
        // 1000 units against 2000/hr per side: the old qty / volume gave 0.5h; two passive
        // legs at half the bottleneck side's volume is 2h.
        assertEquals(2.0, FlipScorer.expectedFillHours(1000, 2000), 1e-9);
        assertEquals(0.0, FlipScorer.expectedFillHours(0, 2000), 1e-9);
        assertTrue(FlipScorer.expectedFillHours(10, 0) > 0);
    }

    @Test
    public void expectedMarginShavesOneRepriceOfSlippage() {
        // Frost dragon bones shape: 6675 -> 6933, tax 138, post-tax 120.
        long postTax = 6933 - 6675 - 138;
        long shaved = (long) Math.floor(6675 * FlipScorer.SLIPPAGE_PCT / 100.0);
        assertEquals(postTax - shaved, FlipScorer.expectedMargin(6675, postTax));
        // Dark crab shape: 1gp post-tax goes negative once slippage is charged.
        assertTrue(FlipScorer.expectedMargin(1292, 1) < 0);
    }

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

    @Test
    public void driftComparesFiveMinuteHighToHourHigh() {
        int[] v1 = {1000, 1000, 7300, 7000};
        int[] v5 = {100, 100, 6933, 6700};
        Double drift = FlipScorer.driftPct(v5, v1);
        assertEquals((6933 - 7300) * 100.0 / 7300, drift, 1e-9);
        assertTrue(drift < FlipScorer.FALLING_DRIFT_PCT);
        assertNull(FlipScorer.driftPct(null, v1));
        assertNull(FlipScorer.driftPct(new int[]{100, 100, 0, 0}, v1));
    }
}
