package com.runeassist.flip.controller;

import com.google.gson.JsonObject;
import com.runeassist.flip.model.SavedOffer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OfferObservationTrackerTest {
    private SavedOffer offer(GrandExchangeOfferState state, int filled) {
        SavedOffer o = new SavedOffer();
        o.setState(state);
        if (state != GrandExchangeOfferState.EMPTY) {
            o.setItemId(4151); o.setPrice(100); o.setTotalQuantity(10);
            o.setQuantitySold(filled); o.setSpent(filled * 100L);
        }
        return o;
    }

    @Test void observesPlacementPartialFillAndCompletionWithStableIdentity() {
        OfferObservationTracker tracker = new OfferObservationTracker();
        tracker.observe(1, 0, offer(GrandExchangeOfferState.EMPTY, 0), true, 1000);
        SavedOffer placed = offer(GrandExchangeOfferState.BUYING, 0);
        tracker.observe(1, 0, placed, false, 2000);
        for (GrandExchangeOfferState state : new GrandExchangeOfferState[]{
                GrandExchangeOfferState.BUYING, GrandExchangeOfferState.BOUGHT}) {
            SavedOffer next = offer(state, state == GrandExchangeOfferState.BOUGHT ? 10 : 3);
            tracker.observe(1, 0, next, false, 3000);
            assertEquals(2000, next.getPlacedAt());
            assertEquals(placed.getOfferInstanceId(), next.getOfferInstanceId());
            JsonObject json = FlipHistorySyncService.offerEventJson("event", "account", 0, next, placed, 3000);
            assertEquals("observed_placement", json.get("placementQuality").getAsString());
            assertEquals(next.getQuantitySold(), json.get("quantitySold").getAsInt());
            assertEquals(100, json.get("price").getAsLong());
            assertEquals(10, json.get("totalQuantity").getAsInt());
            assertEquals(2000, json.get("placedAt").getAsLong());
        }
    }

    @Test void firstSeenEvenZeroFilledOrInstantlyCompletedHasUnknownPlacement() {
        for (GrandExchangeOfferState state : new GrandExchangeOfferState[]{
                GrandExchangeOfferState.BUYING, GrandExchangeOfferState.BOUGHT}) {
            OfferObservationTracker tracker = new OfferObservationTracker();
            SavedOffer first = offer(state, state == GrandExchangeOfferState.BOUGHT ? 10 : 0);
            assertTrue(tracker.observe(1, 0, first, false, 1000));
            assertEquals(0, first.getPlacedAt());
        }
    }

    @Test void cancelRelistStartsNewInstanceAndReconnectLosesTimingCertainty() {
        OfferObservationTracker tracker = new OfferObservationTracker();
        tracker.observe(1, 0, offer(GrandExchangeOfferState.EMPTY, 0), false, 1000);
        SavedOffer placed = offer(GrandExchangeOfferState.SELLING, 0);
        tracker.observe(1, 0, placed, false, 2000);
        SavedOffer cancelled = offer(GrandExchangeOfferState.CANCELLED_SELL, 2);
        tracker.observe(1, 0, cancelled, false, 3000);
        assertEquals(placed.getOfferInstanceId(), cancelled.getOfferInstanceId());
        tracker.observe(1, 0, offer(GrandExchangeOfferState.EMPTY, 0), false, 4000);
        SavedOffer relisted = offer(GrandExchangeOfferState.SELLING, 0);
        tracker.observe(1, 0, relisted, false, 5000);
        assertNotEquals(placed.getOfferInstanceId(), relisted.getOfferInstanceId());
        assertEquals(5000, relisted.getPlacedAt());
        tracker.reset();
        SavedOffer recovered = offer(GrandExchangeOfferState.SELLING, 3);
        tracker.observe(1, 0, recovered, true, 6000);
        assertEquals(0, recovered.getPlacedAt());
        assertTrue(recovered.isLoginObservation());
        assertNotEquals(relisted.getObservationSessionId(), recovered.getObservationSessionId());
    }

    @Test void accountSwitchAndLoginBurstDoNotCertifyPlacement() {
        OfferObservationTracker tracker = new OfferObservationTracker();
        tracker.observe(1, 0, offer(GrandExchangeOfferState.EMPTY, 0), false, 1000);
        SavedOffer otherAccount = offer(GrandExchangeOfferState.BUYING, 0);
        tracker.observe(2, 0, otherAccount, false, 2000);
        assertEquals(0, otherAccount.getPlacedAt());
        tracker.observe(2, 1, offer(GrandExchangeOfferState.EMPTY, 0), true, 3000);
        SavedOffer login = offer(GrandExchangeOfferState.BUYING, 0);
        tracker.observe(2, 1, login, true, 4000);
        assertEquals(0, login.getPlacedAt());
    }
    @Test void relistSoonAfterACancelIsReportedAsAnAdjustmentOnce() {
        OfferObservationTracker tracker = new OfferObservationTracker();
        tracker.observe(1, 0, offer(GrandExchangeOfferState.EMPTY, 0), true, 1000);
        SavedOffer first = offer(GrandExchangeOfferState.BUYING, 0);
        tracker.observe(1, 0, first, false, 2000);
        SavedOffer stopped = offer(GrandExchangeOfferState.CANCELLED_BUY, 0);
        tracker.observe(1, 0, stopped, false, 300_000);
        tracker.observe(1, 0, offer(GrandExchangeOfferState.EMPTY, 0), false, 301_000);
        SavedOffer again = offer(GrandExchangeOfferState.BUYING, 0);
        again.setPrice(102);
        tracker.observe(1, 3, again, false, 330_000);
        assertEquals("relist", again.getAdjustKind());
        assertEquals(first.getOfferInstanceId(), again.getAdjustedFromInstanceId());
        assertEquals(100, again.getAdjustedFromPrice());
        assertEquals(10, again.getAdjustedFromQuantity());
        JsonObject json = FlipHistorySyncService.offerEventJson("event", "account", 3, again, null, 330_000);
        assertEquals("relist", json.get("adjustKind").getAsString());
        assertEquals(100, json.get("adjustedFromPrice").getAsLong());
        SavedOffer fill = offer(GrandExchangeOfferState.BUYING, 4);
        fill.setPrice(102);
        tracker.observe(1, 3, fill, false, 340_000);
        assertNull(fill.getAdjustKind(), "only the first event of the new offer carries it");
        assertFalse(FlipHistorySyncService.offerEventJson("event", "account", 3, fill, again, 340_000).has("adjustKind"));
    }

    @Test void aLateRelistOrAnotherItemIsNotAnAdjustmentAndAnInPlaceEditIsAModify() {
        OfferObservationTracker tracker = new OfferObservationTracker();
        tracker.observe(1, 0, offer(GrandExchangeOfferState.BUYING, 0), false, 1000);
        tracker.observe(1, 0, offer(GrandExchangeOfferState.CANCELLED_BUY, 0), false, 2000);
        tracker.observe(1, 0, offer(GrandExchangeOfferState.EMPTY, 0), false, 3000);
        SavedOffer late = offer(GrandExchangeOfferState.BUYING, 0);
        tracker.observe(1, 0, late, false, 2000 + OfferObservationTracker.RELIST_WINDOW_MS + 1);
        assertNull(late.getAdjustKind());
        SavedOffer edited = offer(GrandExchangeOfferState.BUYING, 0);
        edited.setPrice(105);
        tracker.observe(1, 0, edited, false, 900_000);
        assertEquals("modify", edited.getAdjustKind());
        assertEquals(late.getOfferInstanceId(), edited.getAdjustedFromInstanceId());
    }

    @Test void aCancelIsCreditedToTheCardOnlyWhenTheCardAskedForIt() {
        com.runeassist.flip.model.Suggestion abort = new com.runeassist.flip.model.Suggestion();
        abort.setType(com.runeassist.flip.model.SuggestionType.ABORT);
        abort.setItemId(4151);
        assertEquals("card", GrandExchangeOfferEventHandler.cancelReason(abort, 4151));
        assertEquals("manual", GrandExchangeOfferEventHandler.cancelReason(abort, 11802));
        assertEquals("manual", GrandExchangeOfferEventHandler.cancelReason(null, 4151));
        com.runeassist.flip.model.Suggestion buy = new com.runeassist.flip.model.Suggestion();
        buy.setType(com.runeassist.flip.model.SuggestionType.BUY);
        buy.setItemId(4151);
        assertEquals("manual", GrandExchangeOfferEventHandler.cancelReason(buy, 4151));
        SavedOffer stopped = offer(GrandExchangeOfferState.CANCELLED_BUY, 0);
        stopped.setCancelReason("card");
        assertEquals("card", FlipHistorySyncService.offerEventJson("event", "account", 0, stopped, null, 1).get("cancelReason").getAsString());
    }
}
