package com.runeassist.flip.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OfferFillSecondsTest {

    private static Suggestion card(SuggestionType type, Double buyHours, Double durationSeconds) {
        Suggestion s = new Suggestion();
        s.setType(type);
        s.setEstimatedBuyHours(buyHours);
        s.setExpectedDuration(durationSeconds);
        return s;
    }

    @Test
    void buyUsesTheBuyLegAndSellUsesTheCardDuration() {
        assertEquals(1800L, card(SuggestionType.BUY, 0.5, 9600.0).offerFillSeconds());
        assertEquals(1200L, card(SuggestionType.SELL, null, 1200.0).offerFillSeconds());
    }

    @Test
    void noEstimateMeansNoProgressLabel() {
        assertEquals(0L, card(SuggestionType.BUY, null, 9600.0).offerFillSeconds(), "whole-flip time is not the buy time");
        assertEquals(0L, card(SuggestionType.MODIFY_BUY, 0.5, 600.0).offerFillSeconds());
        assertEquals(0L, card(SuggestionType.SELL, null, null).offerFillSeconds());
    }
}
