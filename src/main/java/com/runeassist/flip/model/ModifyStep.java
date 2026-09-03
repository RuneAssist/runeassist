package com.runeassist.flip.model;

/**
 * Whether a GE modify is still being acted on. Modify cancels the live offer
 * first, then opens Set up offer with the item pre-filled — so matching only
 * by a filling BUYING/SELLING offer (or a stale {@code boxId}) treats a real
 * modify as a ghost: the side panel shows "Getting the next flip…" and the
 * offer-screen price highlight never draws.
 */
public final class ModifyStep {
    private ModifyStep() {
    }

    /**
     * True when the open GE editor is this modify: the selected slot is the
     * modify slot, or the editor's current item is the modify item.
     */
    public static boolean editorMatches(int openSlot, int currentItemId, int itemId, int boxId) {
        if (itemId <= 0) {
            return false;
        }
        if (currentItemId > 0 && currentItemId == itemId) {
            return true;
        }
        return openSlot >= 0 && boxId >= 0 && openSlot == boxId;
    }

    /**
     * A leftover lock after logout/collect/empty slot — not a cancel-then-relist
     * that still has the offer editor open for this item.
     */
    public static boolean isGhost(boolean isModify, int itemId, boolean editorInProgress,
                                  boolean hasFillingOffer) {
        if (!isModify || itemId <= 0) {
            return false;
        }
        if (editorInProgress) {
            return false;
        }
        return !hasFillingOffer;
    }
}
