package com.runeassist.flip.model;

import lombok.Value;

import java.util.List;

@Value
public class InventorySlotTooltipData {
    int itemId;
    int quantity;
    String itemName;
    List<String> lines;
}
