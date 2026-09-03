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
