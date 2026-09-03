package com.runeassist.flip;

import com.osrsmcp.WikiPriceService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The wiki's storeline bucket returns numbers as loosely-typed strings ("N/A", "∞",
 * comma-grouped thousands) -- these are the parsing edge cases that actually showed up in
 * live sample data while building ShopFlipService, not hypothetical ones.
 */
public class ShopFlipServiceTest {

    @Test
    public void parseNumericHandlesPlainInteger() {
        assertEquals(1014L, ShopFlipService.parseNumeric("1014"));
    }

    @Test
    public void parseNumericHandlesCommaGroupedThousands() {
        assertEquals(12414L, ShopFlipService.parseNumeric("12,414"));
    }

    @Test
    public void parseNumericTreatsNAAsAbsent() {
        assertNull(ShopFlipService.parseNumeric("N/A"));
        assertNull(ShopFlipService.parseNumeric("n/a"));
    }

    @Test
    public void parseNumericTreatsBlankAndNullAsAbsent() {
        assertNull(ShopFlipService.parseNumeric(""));
        assertNull(ShopFlipService.parseNumeric(null));
    }

    @Test
    public void parseNumericTreatsGarbageAsAbsent() {
        assertNull(ShopFlipService.parseNumeric("not a number"));
    }

    @Test
    public void parseStockTreatsInfinitySymbolAsMaxValue() {
        assertEquals(Long.MAX_VALUE, ShopFlipService.parseStock("∞"));
    }

    @Test
    public void parseStockHandlesZero() {
        assertEquals(0L, ShopFlipService.parseStock("0"));
    }

    @Test
    public void runCapQtyUsesRealStockWhenKnown() {
        WikiPriceService.ItemMeta meta = new WikiPriceService.ItemMeta();
        meta.limit = 100;
        assertEquals(5L, ShopFlipService.runCapQty(5L, meta));
    }

    @Test
    public void runCapQtyFallsBackToGeLimitWhenStockIsUnlimited() {
        WikiPriceService.ItemMeta meta = new WikiPriceService.ItemMeta();
        meta.limit = 8000;
        assertEquals(8000L, ShopFlipService.runCapQty(Long.MAX_VALUE, meta));
    }

    @Test
    public void runCapQtyFallsBackToSentinelWhenStockUnlimitedAndNoGeLimit() {
        WikiPriceService.ItemMeta meta = new WikiPriceService.ItemMeta();
        meta.limit = 0;
        assertEquals(100L, ShopFlipService.runCapQty(Long.MAX_VALUE, meta));
    }

    @Test
    public void runCapQtyFallsBackWhenStockUnknown() {
        WikiPriceService.ItemMeta meta = new WikiPriceService.ItemMeta();
        meta.limit = 250;
        assertEquals(250L, ShopFlipService.runCapQty(null, meta));
    }
}
