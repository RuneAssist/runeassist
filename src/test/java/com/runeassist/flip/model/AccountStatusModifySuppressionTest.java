package com.runeassist.flip.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class AccountStatusModifySuppressionTest
{
    @Test
    public void closingModifyEditorRecordsTimestampForServerPolicy()
    {
        AccountStatusManager manager = new AccountStatusManager(
            null, null, null, null, null, null, null, null, null, null, null, null);
        Suggestion suggestion = new Suggestion();
        suggestion.setType(SuggestionType.MODIFY_BUY);
        suggestion.setItemId(4151);
        suggestion.setBoxId(2);
        suggestion.setPrice(10_000_000L);
        suggestion.setQuantity(1);

        manager.beginOwnedModify(suggestion, 2);
        assertTrue(manager.releaseStaleOwnedModify(null, false));
        assertTrue(manager.getModifyDismissedMs().get(4151) > 0L);
    }
}
