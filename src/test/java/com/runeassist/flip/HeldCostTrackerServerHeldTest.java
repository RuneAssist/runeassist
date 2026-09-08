package com.runeassist.flip;

import net.runelite.api.GrandExchangeOfferState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HeldCostTrackerServerHeldTest {

    @Test
    public void replaceServerHeldOverwritesLocalLots() {
        HeldCostTracker t = new HeldCostTracker();
        t.addManualLot("Bob", 4151, 2, 100);
        Map<Integer, long[]> held = new HashMap<>();
        held.put(4151, new long[]{5, 200});
        held.put(4152, new long[]{1, 50});
        t.replaceServerHeld("Bob", held);

        Map<Integer, long[]> out = t.held("Bob");
        assertEquals(2, out.size());
        assertEquals(5L, out.get(4151)[0]);
        assertEquals(200L, out.get(4151)[1]);
        assertEquals(1L, out.get(4152)[0]);
    }

    @Test
    public void replaceServerHeldNullClears() {
        HeldCostTracker t = new HeldCostTracker();
        t.addManualLot("Bob", 1, 3, 10);
        t.replaceServerHeld("Bob", null);
        assertTrue(t.held("Bob").isEmpty());
    }

    @Test
    public void offerPriceChangeResetsRepriceClock() throws Exception {
        HeldCostTracker t = new HeldCostTracker();
        t.onOffer("Bob", 0, GrandExchangeOfferState.BUYING, 4151,
            10_000, 10, 0, 0);
        long first = t.lastPriceChangeMs("Bob", 0, 4151);

        t.onOffer("Bob", 0, GrandExchangeOfferState.BUYING, 4151,
            10_000, 10, 0, 0);
        assertEquals(first, t.lastPriceChangeMs("Bob", 0, 4151));

        Thread.sleep(2L);
        t.onOffer("Bob", 0, GrandExchangeOfferState.BUYING, 4151,
            10_500, 10, 0, 0);
        assertTrue(t.lastPriceChangeMs("Bob", 0, 4151) > first);
        assertEquals(first, t.listedMs("Bob", 0, 4151));
    }

    @Test
    public void staleServerHeldCannotRestoreStockConsumedByLocalFill() {
        HeldCostTracker t = new HeldCostTracker();
        t.addManualLot("Bob", 11943, 670, 5565);
        long beforeRequest = t.heldRevision("Bob");

        t.onOffer("Bob", 3, GrandExchangeOfferState.SOLD, 11943,
            5700, 670, 670, 3_819_000);

        Map<Integer, long[]> stale = new HashMap<>();
        stale.put(11943, new long[]{670, 5565});
        assertFalse(t.replaceServerHeldIfUnchanged("Bob", stale, beforeRequest));
        assertTrue(t.held("Bob").isEmpty());
    }
}
