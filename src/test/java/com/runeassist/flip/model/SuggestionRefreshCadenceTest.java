package com.runeassist.flip.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class SuggestionRefreshCadenceTest {
    @Test void routineRefreshIsFiveSecondsWithoutClearingTheVisibleCard() {
        SuggestionManager manager = new SuggestionManager();
        Suggestion wait = new Suggestion();
        wait.setType(SuggestionType.WAIT);
        manager.setSuggestion(wait);
        Instant received = manager.getSuggestionReceivedAt();
        assertFalse(manager.suggestionOutOfDate(received.plusMillis(4999)));
        assertTrue(manager.suggestionOutOfDate(received.plusSeconds(5)));
        assertSame(wait, manager.getSuggestion());
    }

    @Test void failuresKeepTenSecondCooldown() {
        SuggestionManager manager = new SuggestionManager();
        Instant now = Instant.parse("2026-09-13T12:00:00Z");
        manager.setSuggestionReceivedAt(now);
        manager.setLastFailureAt(now);
        assertFalse(manager.suggestionOutOfDate(now.plusSeconds(9)));
        assertTrue(manager.suggestionOutOfDate(now.plusSeconds(10)));
    }
}
