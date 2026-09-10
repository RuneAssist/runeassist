package com.runeassist.flip.controller;

import com.runeassist.flip.model.SavedOffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.runelite.api.GrandExchangeOfferState;

/** Session-local evidence, independent of persisted offers or suggestion attribution. */
final class OfferObservationTracker {
    private final Map<Integer, SavedOffer> slots = new HashMap<>();
    private String sessionId = UUID.randomUUID().toString();
    private Long account;

    void reset() {
        slots.clear();
        sessionId = UUID.randomUUID().toString();
        account = null;
    }

    boolean observe(long accountHash, int slot, SavedOffer offer, boolean login, long now) {
        if (account == null || account != accountHash) {
            reset();
            account = accountHash;
        }
        SavedOffer previous = slots.get(slot);
        boolean first = previous == null;
        boolean empty = offer.getState() == GrandExchangeOfferState.EMPTY;
        boolean active = offer.getState() == GrandExchangeOfferState.BUYING
                || offer.getState() == GrandExchangeOfferState.SELLING;
        boolean previousActive = previous != null && (previous.getState() == GrandExchangeOfferState.BUYING
                || previous.getState() == GrandExchangeOfferState.SELLING);
        boolean same = previous != null && !GrandExchangeOfferEventHandler.isNewOffer(previous, offer)
                && !(active && !previousActive);
        offer.setObservedAt(now);
        offer.setObservationSessionId(sessionId);
        offer.setLoginObservation(login);
        if (!empty) {
            offer.setOfferInstanceId(same ? previous.getOfferInstanceId() : UUID.randomUUID().toString());
            if (same) {
                offer.setPlacedAt(previous.getPlacedAt());
            } else if (!login && active && offer.getQuantitySold() == 0 && previous != null
                    && previous.getState() == GrandExchangeOfferState.EMPTY) {
                // Timestamp of the actual client placement transition, not a market-price inference.
                offer.setPlacedAt(now);
            }
        }
        slots.put(slot, offer);
        return first;
    }
}
