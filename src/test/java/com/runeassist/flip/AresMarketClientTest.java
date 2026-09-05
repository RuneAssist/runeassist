package com.runeassist.flip;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The only scoring arithmetic left client-side: valuing a decant of stock already held.
 * Flip ranking, decant ranking and per-item health are server-side.
 */
public class AresMarketClientTest {

    @Test
    public void decantingHeldStockBeatsSellingItAsIs() {
        // 208x Super strength(3) held: sell-as-is vs decant-to-4 then sell.
        long gain = AresMarketClient.decantGainOverRawSell(208, 3, 4, 2403, 48, 3300, 66);
        assertEquals(504_504L - 489_840L, gain);
        assertTrue(gain > 0);
    }

    @Test
    public void decantGainIsNegativeWhenConvertingLosesValue() {
        assertTrue(AresMarketClient.decantGainOverRawSell(208, 3, 4, 2403, 48, 3000, 60) < 0);
    }

    @Test
    public void decantGainFloorsPartialBottlesAndRejectsNonsense() {
        assertEquals(3 * (3300 - 66) - 5 * (2403 - 48),
            AresMarketClient.decantGainOverRawSell(5, 3, 4, 2403, 48, 3300, 66));
        assertEquals(0, AresMarketClient.decantGainOverRawSell(0, 3, 4, 2403, 48, 3300, 66));
        assertEquals(0, AresMarketClient.decantGainOverRawSell(208, 3, 0, 2403, 48, 3300, 66));
    }
}
