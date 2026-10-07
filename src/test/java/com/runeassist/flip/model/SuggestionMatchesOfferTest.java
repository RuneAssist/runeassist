package com.runeassist.flip.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuggestionMatchesOfferTest {
    private static Suggestion card(SuggestionType type, int itemId) {
        Suggestion s = new Suggestion();
        s.setType(type);
        s.setItemId(itemId);
        return s;
    }

    @Test
    void onlyTheCardsOwnItemAndSideCount() {
        assertTrue(card(SuggestionType.BUY, 561).matchesOffer("buy", 561));
        assertTrue(card(SuggestionType.MODIFY_BUY, 561).matchesOffer("buy", 561));
        assertTrue(card(SuggestionType.SELL, 561).matchesOffer("sell", 561));
        assertTrue(card(SuggestionType.MODIFY_SELL, 561).matchesOffer("sell", 561));
        // A bulk order of another item placed while a buy card is showing.
        assertFalse(card(SuggestionType.BUY, 561).matchesOffer("buy", 1515));
        // The right item on the wrong side.
        assertFalse(card(SuggestionType.BUY, 561).matchesOffer("sell", 561));
        // Cards without an offer side never match a confirm.
        assertFalse(card(SuggestionType.WAIT, 0).matchesOffer("buy", 0));
        assertFalse(card(SuggestionType.ABORT, 561).matchesOffer("buy", 561));
    }
}
