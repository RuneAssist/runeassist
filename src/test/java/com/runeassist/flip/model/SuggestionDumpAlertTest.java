package com.runeassist.flip.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dump alerts must stay unactioned until Confirm or Skip — not only for 10s —
 * so clicking Back from a sell setup cannot replace them with a SELL of inventory.
 */
public class SuggestionDumpAlertTest {

    @Test
    public void unactionedDumpStaysActiveAfterTenSeconds() {
        Suggestion s = new Suggestion();
        s.setType(SuggestionType.BUY);
        s.setDumpAlert(true);
        s.setDumpAlertReceived(Instant.now().minusSeconds(30));
        s.actionedTick = -1;

        assertTrue(s.isRecentUnActionedDumpAlert());
    }

    @Test
    public void actionedDumpIsNotHeld() {
        Suggestion s = new Suggestion();
        s.setType(SuggestionType.BUY);
        s.setDumpAlert(true);
        s.actionedTick = 0;

        assertFalse(s.isRecentUnActionedDumpAlert());
    }

    @Test
    public void skipMarksDumpActioned() {
        Suggestion s = new Suggestion();
        s.setType(SuggestionType.BUY);
        s.setDumpAlert(true);
        s.actionedTick = -1;
        s.actionedTick = 0; // skipCurrentSuggestion

        assertFalse(s.isRecentUnActionedDumpAlert());
    }

    @Test
    public void buyDumpSuggestionRequiresBuyType() {
        Suggestion buy = new Suggestion();
        buy.setType(SuggestionType.BUY);
        buy.setDumpAlert(true);
        assertTrue(buy.isBuyDumpSuggestion());

        Suggestion sell = new Suggestion();
        sell.setType(SuggestionType.SELL);
        sell.setDumpAlert(true);
        assertFalse(sell.isBuyDumpSuggestion());
    }
}
