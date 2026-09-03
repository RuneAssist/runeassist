package com.runeassist.flip.model;

import lombok.Value;

@Value
public class GeHistoryRow {
    int itemId;
    int quantity;
    long price;
    boolean buy;
    /** Item name from the history widget, if present. */
    String name;
    /** Fill time in epoch millis when the widget exposes one; otherwise null. */
    Long fillTs;
}
