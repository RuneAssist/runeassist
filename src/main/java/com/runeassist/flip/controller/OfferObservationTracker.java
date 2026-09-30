package com.runeassist.flip.controller;

import com.runeassist.flip.model.SavedOffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.runelite.api.GrandExchangeOfferState;

final class OfferObservationTracker {
    private final Map<Integer, SavedOffer> slots = new HashMap<>();
    static final long RELIST_WINDOW_MS = 10 * 60_000L;
    private final Map<String, SavedOffer> cancelled = new HashMap<>();
    private String sessionId = UUID.randomUUID().toString();
    private Long account;

    void reset() {
        slots.clear();
        cancelled.clear();
        sessionId = UUID.randomUUID().toString();
        account = null;
    }

    private static String key(SavedOffer o) {
        return o.getItemId() + ":" + o.getOfferStatus();
    }

    private void markAdjustment(SavedOffer offer, SavedOffer previous, boolean previousActive, long now) {
        SavedOffer from = null;
        String kind = null;
        if (previous != null && previousActive && previous.getItemId() == offer.getItemId()
                && previous.getOfferStatus() == offer.getOfferStatus()) {
            from = previous;
            kind = "modify";
        } else {
            SavedOffer last = cancelled.get(key(offer));
            if (last != null && now - last.getObservedAt() <= RELIST_WINDOW_MS) {
                from = last;
                kind = "relist";
                cancelled.remove(key(offer));
            }
        }
        if (from == null || from.getOfferInstanceId() == null) {
            return;
        }
        offer.setAdjustKind(kind);
        offer.setAdjustedFromInstanceId(from.getOfferInstanceId());
        offer.setAdjustedFromPrice(from.getPrice());
        offer.setAdjustedFromQuantity(Math.max(0, from.getTotalQuantity() - from.getQuantitySold()));
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
                offer.setPlacedAt(now);
            }
        }
        if (!empty && !same && active && !login) {
            markAdjustment(offer, previous, previousActive, now);
        }
        if (offer.getState() == GrandExchangeOfferState.CANCELLED_BUY
                || offer.getState() == GrandExchangeOfferState.CANCELLED_SELL) {
            cancelled.put(key(offer), offer);
        }
        slots.put(slot, offer);
        return first;
    }
}
