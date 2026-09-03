package com.runeassist.flip;

import com.google.gson.Gson;
import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.model.SuggestionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TelemetryServiceTest {

    @Test
    public void geOfferSnapshotsOnlyRecordMeaningfulChanges() {
        TelemetryService telemetry = new TelemetryService(null, new Gson());

        assertTrue(telemetry.claimGeOffer("acct", 2, "BUYING", 4151,
            1_000, 10, 0, 0, 1_100, 900));
        assertFalse(telemetry.claimGeOffer("acct", 2, "BUYING", 4151,
            1_000, 10, 0, 0, 1_100, 900));
        assertTrue(telemetry.claimGeOffer("acct", 2, "BUYING", 4151,
            1_000, 10, 1, 1_000, 1_100, 900));
        assertTrue(telemetry.claimGeOffer("other", 2, "BUYING", 4151,
            1_000, 10, 1, 1_000, 1_100, 900));
    }

    @Test
    public void suggestionOutcomesHaveOneStableLifecycleId() {
        TelemetryService telemetry = new TelemetryService(null, new Gson());
        Suggestion suggestion = suggestion();

        String shownId = telemetry.claimSuggestionOutcome(suggestion, "shown");
        assertNotNull(shownId);
        assertNull(telemetry.claimSuggestionOutcome(suggestion, "shown"));
        assertEquals(shownId, telemetry.claimSuggestionOutcome(suggestion, "acted"));
        assertNull(telemetry.claimSuggestionOutcome(suggestion, "acted"));

        String nextId = telemetry.claimSuggestionOutcome(suggestion(), "shown");
        assertNotNull(nextId);
        assertNotEquals(shownId, nextId);
    }

    private static Suggestion suggestion() {
        Suggestion suggestion = new Suggestion();
        suggestion.setType(SuggestionType.BUY);
        suggestion.setItemId(4151);
        suggestion.setName("Abyssal whip");
        return suggestion;
    }
}
