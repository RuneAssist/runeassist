package com.runeassist.flip;

import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.model.SuggestionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WaitSuggestionsTest {

    @Test
    public void waitFallbackSetsTypeMessageAndSlotWhy() {
        long[][] offers = new long[8][];
        offers[0] = new long[]{1, 1, 100, 0, 1, 1};
        offers[2] = new long[]{2, 0, 50, 0, 1, 1};
        Suggestion s = WaitSuggestions.waitFallback(WaitSuggestions.WAIT_ARES_DOWN, offers, 8);
        assertEquals(SuggestionType.WAIT, s.getType());
        assertEquals(WaitSuggestions.WAIT_ARES_DOWN, s.getMessage());
        assertEquals("2/8 slots", s.getWhy());
        assertTrue(s.isWaitSuggestion());
    }

    @Test
    public void waitFallbackDefaultsEmptyMessage() {
        Suggestion s = WaitSuggestions.waitFallback("", null, 3);
        assertEquals(WaitSuggestions.WAIT_NO_MARGIN, s.getMessage());
        assertEquals("0/3 slots", s.getWhy());
    }
}
