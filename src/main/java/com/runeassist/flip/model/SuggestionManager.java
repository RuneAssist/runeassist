package com.runeassist.flip.model;

import lombok.Getter;
import lombok.Setter;

import javax.inject.Singleton;
import java.time.Instant;
import java.util.Objects;

@Singleton
@Getter
@Setter
public class SuggestionManager {

    private volatile boolean suggestionNeeded;
    private volatile boolean suggestionRequestInProgress;
    private volatile boolean graphDataReadingInProgress;
    private volatile boolean suggestionRefreshPending;
    private Instant lastFailureAt;
    private Suggestion suggestion;
    private Instant suggestionReceivedAt;
    private int lastOfferSubmittedTick = -1;
    private String submittedSuggestionId;
    private int submittedItemId;
    private OfferStatus submittedOfferStatus;
    private int submittedTick = -1;

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
        submittedSuggestionId = null;
        submittedItemId = 0;
        submittedOfferStatus = null;
        submittedTick = -1;
    }

    public boolean suggestionOutOfDate() {
        return suggestionOutOfDate(Instant.now());
    }

    boolean suggestionOutOfDate(Instant now) {
        // Faster routine refresh; failures retain a longer cooldown. Explicit offer
        // events still use suggestionNeeded and the controller's single-flight guard.
        return (suggestionReceivedAt == null || !suggestionReceivedAt.plusSeconds(5).isAfter(now))
                && (lastFailureAt == null || !lastFailureAt.plusSeconds(10).isAfter(now));
    }

    public boolean suggestionVeryOutOfDate() {
        return suggestionReceivedAt != null && Instant.now().minusSeconds(60L).isAfter(suggestionReceivedAt);
    }

    public synchronized void recordOfferSubmission(Suggestion suggestion, int tick) {
        if (suggestion == null || suggestion.offerType() == null) return;
        submittedSuggestionId = suggestion.getServerSuggestionId();
        submittedItemId = suggestion.getItemId();
        submittedOfferStatus = suggestion.isBuySuggestion() ? OfferStatus.BUY : OfferStatus.SELL;
        submittedTick = tick;
    }

    public synchronized String matchingSubmittedSuggestion(int itemId, OfferStatus status, int tick) {
        if (submittedSuggestionId == null || submittedSuggestionId.isEmpty()) return null;
        if (tick < submittedTick || tick - submittedTick > 5) return null;
        return submittedItemId == itemId && Objects.equals(submittedOfferStatus, status)
                ? submittedSuggestionId : null;
    }
}
