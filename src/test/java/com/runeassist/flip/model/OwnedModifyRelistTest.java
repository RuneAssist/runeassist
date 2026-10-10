package com.runeassist.flip.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The game modifies an offer by cancelling it, collecting what filled and
 * reopening Set up offer. For a few ticks the slot is empty and no editor is
 * open; the owned modify (and its card) must survive that gap.
 */
public class OwnedModifyRelistTest
{
    private static Suggestion modifyBuy()
    {
        Suggestion s = new Suggestion();
        s.setType(SuggestionType.MODIFY_BUY);
        s.setItemId(4698);
        s.setBoxId(5);
        s.setPrice(106L);
        s.setQuantity(8733);
        return s;
    }

    private static AccountStatusManager manager()
    {
        return new AccountStatusManager(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    public void emptySlotWithNoEditorKeepsTheModifyDuringTheRelist()
    {
        AccountStatusManager m = manager();
        m.beginOwnedModify(modifyBuy(), 5, 100);
        assertFalse(m.releaseStaleOwnedModify(null, false, 102));
        assertFalse(m.releaseOwnedModifyIfSlotEmpty(5, false, 103));
        assertTrue(m.isOwnedModifyRelisting(103));
        assertTrue(m.isOwnedModifyActive());
    }

    @Test
    public void abandonedModifyIsReleasedOnceTheGracePasses()
    {
        AccountStatusManager m = manager();
        m.beginOwnedModify(modifyBuy(), 5, 100);
        int after = 101 + ModifyStep.RELIST_GRACE_TICKS;
        assertFalse(m.isOwnedModifyRelisting(after));
        assertTrue(m.releaseOwnedModifyIfSlotEmpty(5, false, after));
        assertFalse(m.isOwnedModifyActive());
    }

    @Test
    public void clickingModifyAgainRestartsTheGrace()
    {
        AccountStatusManager m = manager();
        m.beginOwnedModify(modifyBuy(), 5, 100);
        m.beginOwnedModify(modifyBuy(), 5, 200);
        assertFalse(m.releaseStaleOwnedModify(null, false, 205));
        assertTrue(m.isOwnedModifyRelisting(205));
    }

    @Test
    public void anOpenEditorStillHoldsAfterTheGrace()
    {
        AccountStatusManager m = manager();
        m.beginOwnedModify(modifyBuy(), 5, 100);
        assertFalse(m.releaseOwnedModifyIfSlotEmpty(5, true, 500));
        assertFalse(m.releaseStaleOwnedModify(null, true, 500));
        assertTrue(m.isOwnedModifyActive());
    }
}
