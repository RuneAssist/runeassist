package com.runeassist.flip.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cancel-then-relist empties the GE slot while Set up offer is still showing
 * the item. Ghost detection must keep that modify so the side panel and price
 * highlight can reuse the buy/sell setup path.
 */
public class ModifyStepTest {

    private static final int DARK_CRAB = 11934;

    @Test
    public void editorMatchesByCurrentItemWhenSlotAlreadyCancelled() {
        int openSlot = 3;
        int staleBoxId = 0;
        assertTrue(ModifyStep.editorMatches(openSlot, DARK_CRAB, DARK_CRAB, staleBoxId));
    }

    @Test
    public void editorMatchesByOpenSlotWhenCurrentItemNotYetSet() {
        assertTrue(ModifyStep.editorMatches(3, -1, DARK_CRAB, 3));
    }

    @Test
    public void editorDoesNotMatchADifferentItemOrSlot() {
        assertFalse(ModifyStep.editorMatches(3, 4151, DARK_CRAB, 0));
        assertFalse(ModifyStep.editorMatches(-1, -1, DARK_CRAB, 3));
        assertFalse(ModifyStep.editorMatches(3, -1, 0, 3));
    }

    @Test
    public void cancelledRelistIsNotGhostWhileEditorIsOpen() {
        assertFalse(ModifyStep.isGhost(true, DARK_CRAB, true, false));
    }

    @Test
    public void modifyWithFillingOfferIsNotGhost() {
        assertFalse(ModifyStep.isGhost(true, DARK_CRAB, false, true));
    }

    @Test
    public void leftoverModifyWithEditorClosedIsGhost() {
        assertTrue(ModifyStep.isGhost(true, DARK_CRAB, false, false));
    }

    @Test
    public void buySellWaitAreNeverGhost() {
        assertFalse(ModifyStep.isGhost(false, DARK_CRAB, false, false));
        assertFalse(ModifyStep.isGhost(true, 0, false, false));
    }
}
