package com.runeassist.flip;

import com.google.gson.Gson;
import com.runeassist.flip.model.Suggestion;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Opt-in telemetry stubbed for Hub review-token budget.
 * Public API kept as no-ops / local dedupe helpers; JSONL + upload removed.
 */
@Slf4j
@Singleton
public class TelemetryService {
    private final Map<String, String> lastGeOfferByAccountSlot = new HashMap<>();
    private final Map<Suggestion, String> suggestionTelemetryIds = new WeakHashMap<>();
    private final Map<Suggestion, Set<String>> suggestionOutcomes = new WeakHashMap<>();

    public static final class GeHistoryFill {
        public final int itemId;
        public final int qty;
        public final long price;
        public final boolean buy;
        public final String name;
        public final Long fillTs;

        public GeHistoryFill(int itemId, int qty, long price, boolean buy, String name, Long fillTs) {
            this.itemId = itemId;
            this.qty = qty;
            this.price = price;
            this.buy = buy;
            this.name = name;
            this.fillTs = fillTs;
        }
    }

    @Inject
    public TelemetryService(OkHttpClient httpClient, Gson gson) {
        // Guice signature retained; stub does not use these.
    }

    public void logGeOffer(String rsn, int slot, String state, int itemId, int price,
                           int totalQuantity, int quantitySold, int spent) {
        claimGeOffer(rsn == null ? "" : rsn, slot, state, itemId, price, totalQuantity, quantitySold, spent);
    }

    synchronized boolean claimGeOffer(String acct, int slot, String state, int itemId, int price,
                                      int totalQuantity, int quantitySold, int spent) {
        String key = acct + "|" + slot;
        String value = state + "|" + itemId + "|" + price + "|" + totalQuantity + "|"
                + quantitySold + "|" + spent;
        if (value.equals(lastGeOfferByAccountSlot.get(key))) {
            return false;
        }
        lastGeOfferByAccountSlot.put(key, value);
        return true;
    }

    public void logSuggestionDecision(String rsn, Suggestion s, String outcome, String source) {
        if (s == null) {
            return;
        }
        claimSuggestionOutcome(s, outcome == null || outcome.isEmpty() ? "shown" : outcome);
    }

    synchronized String claimSuggestionOutcome(Suggestion suggestion, String outcome) {
        Set<String> outcomes = suggestionOutcomes.computeIfAbsent(suggestion, ignored -> new HashSet<>());
        if (!outcomes.add(outcome)) {
            return null;
        }
        return suggestionTelemetryIds.computeIfAbsent(suggestion, ignored -> UUID.randomUUID().toString());
    }

    public void logGeHistory(String rsn, List<GeHistoryFill> newestFirst, int capturedAtSec) {}

    public void onUploadSettingsChanged() {}

    public void shutdown() {}
}
