package com.runeassist.flip.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SuggestionCardTextTest {
    @Test void profitAndSizingReasonsStayOutOfTheMainCard() {
        assertEquals("Waiting for a suitable flip", SuggestionCardText.waitStatus(
                "Available safe batches fall below the 20,000 gp profit target."));
        assertEquals("Waiting for a suitable flip", SuggestionCardText.waitStatus(
                "No safely sized batch fits the available slot budget and liquidity."));
        assertEquals("Waiting for a suitable flip", SuggestionCardText.waitStatus(null));
    }

    @Test void actionableWaitStatesRemainDistinct() {
        assertEquals("Waiting for offers to fill", SuggestionCardText.waitStatus("All GE slots are full."));
        assertEquals("More coins needed", SuggestionCardText.waitStatus("Not enough coins for the next flip."));
        assertEquals("Waiting for buy limits", SuggestionCardText.waitStatus("Buy limits exhausted."));
        assertEquals("Connection unavailable", SuggestionCardText.waitStatus("Ares is unreachable — no flip candidates."));
        assertEquals("Waiting for fresh prices", SuggestionCardText.waitStatus("Market data is temporarily unavailable."));
    }

    @Test void detailsAreRetainedEscapedAndDeduplicated() {
        assertEquals("Reason<br>4/8 slots", SuggestionCardText.details("Reason", "4/8 slots"));
        assertEquals("Reason", SuggestionCardText.details("Reason", "Reason"));
        assertEquals("&lt;html&gt; &amp;", SuggestionCardText.details(null, "<html> &"));
        assertEquals("", SuggestionCardText.details(null, null));
    }
}
