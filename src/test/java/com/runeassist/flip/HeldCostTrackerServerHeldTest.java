package com.runeassist.flip;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
