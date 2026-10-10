package com.runeassist.flip.model;

public final class ModifyStep {
    public static final int RELIST_GRACE_TICKS = 10;

    private ModifyStep() {
    }

    public static boolean inRelistGrace(int nowTick, int startedTick) {
        return startedTick >= 0 && nowTick >= startedTick && nowTick - startedTick <= RELIST_GRACE_TICKS;
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
