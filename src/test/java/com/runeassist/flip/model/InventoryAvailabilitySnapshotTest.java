package com.runeassist.flip.model;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InventoryAvailabilitySnapshotTest
{
    @Test
    void missingAndUnvalidatedInventoryAreUnknown()
    {
        Map<Integer, long[]> held = Map.of(11943, new long[]{670, 5500});
        assertFalse(InventoryAvailabilitySnapshot.from(held, null, true).isInventorySnapshotKnown());
        InventoryAvailabilitySnapshot invalid = InventoryAvailabilitySnapshot.from(held, Map.of(11943, 670L), false);
        assertFalse(invalid.isInventorySnapshotKnown());
        assertTrue(invalid.getAvailableInventory().isEmpty());
    }

    @Test
    void observedEmptyInventoryIsKnownAndDoesNotUseHistoricalStock()
    {
        InventoryAvailabilitySnapshot snapshot = InventoryAvailabilitySnapshot.from(
                Map.of(11943, new long[]{3525, 5500}), Collections.emptyMap(), true);
        assertTrue(snapshot.isInventorySnapshotKnown());
        assertTrue(snapshot.getAvailableInventory().isEmpty());
    }

    @Test
    void includesOnlyTrackedIdsAndRealPhysicalQuantities()
    {
        InventoryAvailabilitySnapshot snapshot = InventoryAvailabilitySnapshot.from(
                Map.of(11943, new long[]{3525, 5500}, 561, new long[]{100, 200}),
                Map.of(11943, 36L, 561, 500L, 995, 100000000L, 560, 900L), true);
        assertEquals(2, snapshot.getAvailableInventory().size());
        assertEquals(561, snapshot.getAvailableInventory().get(0).getItemId());
        assertEquals(500, snapshot.getAvailableInventory().get(0).getQuantity());
        assertEquals(11943, snapshot.getAvailableInventory().get(1).getItemId());
        assertEquals(36, snapshot.getAvailableInventory().get(1).getQuantity());
    }

    @Test
    void invalidRowsAndUntrackedStockAreExcluded()
    {
        Map<Integer, long[]> held = new HashMap<>();
        held.put(1, new long[]{1, 2});
        held.put(2, new long[]{1, 2});
        held.put(3, new long[]{0, 2});
        held.put(4, null);
        held.put(5, new long[]{});
        held.put(null, new long[]{1, 2});
        held.put(-1, new long[]{1, 2});
        InventoryAvailabilitySnapshot snapshot = InventoryAvailabilitySnapshot.from(held,
                Map.of(1, -1L, 2, (long) Integer.MAX_VALUE + 1, 3, 30L, 4, 20L, 5, 10L), true);
        assertTrue(snapshot.getAvailableInventory().isEmpty());
    }

    @Test
    void snapshotDoesNotFollowLaterInventoryMutationOrExposeUntrackedItems()
    {
        Map<Integer, Long> inventory = new HashMap<>();
        inventory.put(561, 20L);
        inventory.put(995, 1000L);
        InventoryAvailabilitySnapshot snapshot = InventoryAvailabilitySnapshot.from(
                Map.of(561, new long[]{100, 200}), inventory, true);
        inventory.put(561, 0L);
        assertEquals(20, snapshot.getAvailableInventory().get(0).getQuantity());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getAvailableInventory().clear());
        assertEquals("{\"inventorySnapshotKnown\":true,\"availableInventory\":[{\"itemId\":561,\"quantity\":20}]}",
                new Gson().toJson(snapshot));
    }
}
