package com.runeassist.flip.model;

public final class ModifyStep {
    private ModifyStep() {
    }

    public static boolean editorMatches(int openSlot, int currentItemId, int itemId, int boxId) {
        if (itemId <= 0) {
            return false;
        }
        if (currentItemId > 0 && currentItemId == itemId) {
            return true;
        }
        return openSlot >= 0 && boxId >= 0 && openSlot == boxId;
    }

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
