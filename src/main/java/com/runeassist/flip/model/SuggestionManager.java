package com.runeassist.flip.model;

import lombok.Getter;
import lombok.Setter;

import javax.inject.Singleton;
import java.time.Instant;

@Singleton
@Getter
@Setter
public class SuggestionManager {

    private volatile boolean suggestionNeeded;
    private volatile boolean suggestionRequestInProgress;
    private volatile boolean suggestionRefreshPending;
    private Instant lastFailureAt;
    private Suggestion suggestion;
    private Instant suggestionReceivedAt;
    private int lastOfferSubmittedTick = -1;

    public volatile int suggestionsDelayedUntil = 0;

    public void setSuggestion(Suggestion suggestion) {
        this.suggestion = suggestion;
        suggestionReceivedAt = Instant.now();

    }

    public void reset() {
        suggestionNeeded = false;
        suggestionRefreshPending = false;
        suggestion = null;
        suggestionReceivedAt = null;
        lastFailureAt = null;
        lastOfferSubmittedTick = -1;
    }

    public boolean suggestionOutOfDate() {
        Instant tenSecondsAgo = Instant.now().minusSeconds(10L);
        if (suggestionReceivedAt == null || tenSecondsAgo.isAfter(suggestionReceivedAt)) {
            return lastFailureAt == null || tenSecondsAgo.isAfter(lastFailureAt);
        }
        return false;
    }

    public boolean suggestionVeryOutOfDate() {
        return suggestionReceivedAt != null && Instant.now().minusSeconds(60L).isAfter(suggestionReceivedAt);
    }
}
