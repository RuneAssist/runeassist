package com.runeassist.flip.controller;

import com.runeassist.flip.model.PortfolioItemCardData;
import com.runeassist.flip.model.TooltipHoverSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InventorySlotTooltipLinesTest {
    private static List<String> lines(long unitBuy, Long unitProfit) {
        PortfolioItemCardData item = new PortfolioItemCardData(560, "Death rune", 0, 25_000, 1_000, 1, 202L, unitBuy, unitProfit, 1, 25_000);
        return new InventorySlotTooltipDataProvider(null, null, null).buildTooltipLines(item, TooltipHoverSource.INVENTORY);
    }

    @Test
    void averageBuyAndRoiComeFromTheHeldStockItself() {
        List<String> lines = lines(199L, 3L);
        assertTrue(lines.contains("Avg buy price: 199 gp"), lines.toString());
        assertTrue(lines.contains("Unrealized ROI: 1.51%"), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Unrealized Profit: ") && !l.endsWith("Unknown")), lines.toString());
    }

    @Test
    void unknownCostStaysUnknown() {
        List<String> lines = lines(0L, null);
        assertTrue(lines.contains("Avg buy price: Unknown"), lines.toString());
        assertTrue(lines.contains("Unrealized ROI: Unknown"), lines.toString());
        assertTrue(lines.contains("Unrealized Profit: Unknown"), lines.toString());
    }
}
