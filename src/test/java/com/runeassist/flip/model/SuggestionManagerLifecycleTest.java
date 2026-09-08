package com.runeassist.flip.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class SuggestionManagerLifecycleTest
{
    @Test
    public void submittedSuggestionMatchesOnlyTheSameOfferForFiveTicks()
    {
        Suggestion suggestion = new Suggestion();
        suggestion.setType(SuggestionType.BUY);
        suggestion.setItemId(4151);
        suggestion.setServerSuggestionId("11111111-1111-1111-1111-111111111111");
        SuggestionManager manager = new SuggestionManager();

        manager.recordOfferSubmission(suggestion, 100);

        assertEquals(suggestion.getServerSuggestionId(),
                manager.matchingSubmittedSuggestion(4151, OfferStatus.BUY, 105));
        assertNull(manager.matchingSubmittedSuggestion(4151, OfferStatus.SELL, 105));
        assertNull(manager.matchingSubmittedSuggestion(4151, OfferStatus.BUY, 106));
    }
}
