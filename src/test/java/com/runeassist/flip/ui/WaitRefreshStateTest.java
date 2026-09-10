package com.runeassist.flip.ui;

import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.model.SuggestionType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaitRefreshStateTest {
    private Suggestion suggestion(SuggestionType type) {
        Suggestion suggestion = new Suggestion();
        suggestion.setType(type);
        return suggestion;
    }

    @Test void repeatedPendingAndInFlightRefreshesKeepTheDisplayedWait() {
        WaitRefreshState state = new WaitRefreshState();
        Suggestion wait = suggestion(SuggestionType.WAIT);
        assertFalse(state.canKeepWhileRefreshing(wait, 1L), "first load still loads");
        state.showing(wait, 1L);
        for (int i = 0; i < 100; i++) assertTrue(state.canKeepWhileRefreshing(wait, 1L));
    }

    @Test void neverRetainsActionableOrReplacedResults() {
        WaitRefreshState state = new WaitRefreshState();
        Suggestion wait = suggestion(SuggestionType.WAIT);
        state.showing(wait, 1L);
        assertFalse(state.canKeepWhileRefreshing(suggestion(SuggestionType.WAIT), 1L));
        assertFalse(state.canKeepWhileRefreshing(null, 1L));
        for (SuggestionType type : SuggestionType.values()) {
            if (type == SuggestionType.WAIT) continue;
            Suggestion action = suggestion(type);
            state.showing(action, 1L);
            assertFalse(state.canKeepWhileRefreshing(action, 1L));
        }
    }

    @Test void resetPauseLoginAndAccountChangesInvalidateRetention() {
        WaitRefreshState state = new WaitRefreshState();
        Suggestion wait = suggestion(SuggestionType.WAIT);
        state.showing(wait, 1L);
        assertFalse(state.canKeepWhileRefreshing(wait, 2L));
        assertFalse(state.canKeepWhileRefreshing(wait, null));
        state.clear();
        assertFalse(state.canKeepWhileRefreshing(wait, 1L));
        state.showing(wait, null);
        assertFalse(state.canKeepWhileRefreshing(wait, null));
    }

    @Test void aFreshWaitMustBePaintedBeforeItCanBeRetained() {
        WaitRefreshState state = new WaitRefreshState();
        Suggestion oldWait = suggestion(SuggestionType.WAIT);
        Suggestion newWait = suggestion(SuggestionType.WAIT);
        newWait.setMessage("Connection unavailable");
        state.showing(oldWait, 1L);
        assertFalse(state.canKeepWhileRefreshing(newWait, 1L));
        state.showing(newWait, 1L);
        assertTrue(state.canKeepWhileRefreshing(newWait, 1L));
        newWait.setType(SuggestionType.BUY);
        assertFalse(state.canKeepWhileRefreshing(newWait, 1L));
    }
}
