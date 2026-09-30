package com.runeassist.flip.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class InventoryAvailabilitySnapshot
{
    private final boolean inventorySnapshotKnown;
    private final List<ItemQuantity> availableInventory;
    private final boolean bankSnapshotKnown;
    private final List<ItemQuantity> availableBank;

    private InventoryAvailabilitySnapshot(boolean known, List<ItemQuantity> items,
            boolean bankKnown, List<ItemQuantity> bankItems)
    {
        inventorySnapshotKnown = known;
        availableInventory = Collections.unmodifiableList(items);
        bankSnapshotKnown = bankKnown;
        availableBank = Collections.unmodifiableList(bankItems);
    }

    public static InventoryAvailabilitySnapshot from(Map<Integer, long[]> trackedHeld,
            Map<Integer, Long> physicalInventory, boolean known)
    {
        return from(trackedHeld, physicalInventory, known, null, false);
    }

    public static InventoryAvailabilitySnapshot from(Map<Integer, long[]> trackedHeld,
            Map<Integer, Long> physicalInventory, boolean known,
            Map<Integer, Long> bankInventory, boolean bankKnown)
    {
        boolean inventoryOk = known && physicalInventory != null;
        boolean bankOk = bankKnown && bankInventory != null;
        return new InventoryAvailabilitySnapshot(
                inventoryOk, inventoryOk ? tracked(trackedHeld, physicalInventory) : Collections.emptyList(),
                bankOk, bankOk ? tracked(trackedHeld, bankInventory) : Collections.emptyList());
    }

    private static List<ItemQuantity> tracked(Map<Integer, long[]> trackedHeld, Map<Integer, Long> physicalInventory)
    {
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
        return items;
    }

    public boolean isInventorySnapshotKnown()
    {
        return inventorySnapshotKnown;
    }

    public List<ItemQuantity> getAvailableInventory()
    {
        return availableInventory;
    }

    public boolean isBankSnapshotKnown()
    {
        return bankSnapshotKnown;
    }

    public List<ItemQuantity> getAvailableBank()
    {
        return availableBank;
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
