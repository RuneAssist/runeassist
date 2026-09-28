package com.runeassist.flip.ui;

import com.runeassist.flip.model.FlipV2;
import com.runeassist.flip.model.Stats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShareSummaryTest {
    @Test
    void formatsPeriodProfitAndBestFlip() {
        Stats stats = new Stats(728_500L, 40_000_000L, 800_000L, 12);
        FlipV2 best = new FlipV2();
        best.setItemId(13576);
        best.setProfit(223_900L);
        best.setClosedTime(1);
        best.setCachedItemName("Dragon warhammer");
        assertEquals("RuneAssist \u00b7 Today: 12 flips, +" + UIUtilities.formatProfit(728_500L)
                + " gp \u00b7 best: Dragon warhammer +" + UIUtilities.formatProfit(223_900L),
                ShareSummary.format("Today", stats, best));
    }

    @Test
    void handlesLossesSingularAndMissingPieces() {
        Stats one = new Stats(-1_500L, 10_000L, 0L, 1);
        assertEquals("RuneAssist \u00b7 Session: 1 flip, -" + UIUtilities.formatProfit(1_500L) + " gp",
                ShareSummary.format("", one, null));
        FlipV2 unnamed = new FlipV2();
        unnamed.setItemId(4151);
        unnamed.setProfit(50L);
        assertEquals("RuneAssist \u00b7 Week: 0 flips, +" + UIUtilities.formatProfit(0L) + " gp \u00b7 best: item 4151 +"
                + UIUtilities.formatProfit(50L), ShareSummary.format("Week", null, unnamed));
    }
}
