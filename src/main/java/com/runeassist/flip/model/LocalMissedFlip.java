package com.runeassist.flip.model;

import lombok.Data;

/**
 * A cancelled leftover or incomplete local flip, for the Missed flips tab.
 * Profit is not estimated — only quantities and the listed/cost price we already have.
 */
@Data
public class LocalMissedFlip {

    public enum Kind {
        INCOMPLETE,
        CANCELLED
    }

    private Kind kind;
    private int itemId;
    private String itemName;
    private String why;
    private int time;
    private int qtyLeft;
    private int filledQty;
    private int listedQty;
    private long listedPrice;
    private FlipV2 sourceFlip;
}
