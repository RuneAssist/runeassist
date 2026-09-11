package com.runeassist.flip.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Request-scoped physical inventory proof, restricted to tracked item IDs. */
public final class InventoryAvailabilitySnapshot
{
    private final boolean inventorySnapshotKnown;
    private final List<ItemQuantity> availableInventory;

    private InventoryAvailabilitySnapshot(boolean known, List<ItemQuantity> items)
    {
        inventorySnapshotKnown = known;
        availableInventory = Collections.unmodifiableList(items);
    }

    /**
     * The caller must normalize noted IDs and validate the current account before
     * setting known. Do not supply cached bank stock, GE offers or limbo estimates.
     * Missing inventory is unknown; an observed empty inventory is known empty.
     */
    public static InventoryAvailabilitySnapshot from(Map<Integer, long[]> trackedHeld,
            Map<Integer, Long> physicalInventory, boolean known)
    {
        if (!known || physicalInventory == null)
        {
            return new InventoryAvailabilitySnapshot(false, Collections.emptyList());
        }
        List<ItemQuantity> items = new ArrayList<>();
        if (trackedHeld != null)
        {
            for (Map.Entry<Integer, long[]> entry : trackedHeld.entrySet())
            {
                Integer itemId = entry.getKey();
                long[] held = entry.getValue();
                if (itemId == null || itemId <= 0 || held == null || held.length == 0 || held[0] <= 0) continue;
                Long quantity = physicalInventory.get(itemId);
                if (quantity == null || quantity <= 0 || quantity > Integer.MAX_VALUE) continue;
                items.add(new ItemQuantity(itemId, quantity));
            }
        }
        items.sort((left, right) -> Integer.compare(left.itemId, right.itemId));
        return new InventoryAvailabilitySnapshot(true, items);
    }

    public boolean isInventorySnapshotKnown()
    {
        return inventorySnapshotKnown;
    }

    public List<ItemQuantity> getAvailableInventory()
    {
        return availableInventory;
    }

    public static final class ItemQuantity
    {
        private final int itemId;
        private final long quantity;

        private ItemQuantity(int itemId, long quantity)
        {
            this.itemId = itemId;
            this.quantity = quantity;
        }

        public int getItemId()
        {
            return itemId;
        }

        public long getQuantity()
        {
            return quantity;
        }
    }
}
