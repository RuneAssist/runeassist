package com.runeassist.flip;

import net.runelite.api.GrandExchangeOfferState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HeldCostTrackerOfferReuseTest
{
    private static final String ACCOUNT = "Bof118";
    private static final int ITEM = 11943;

    private static void offer(HeldCostTracker tracker, String account, int slot,
            GrandExchangeOfferState state, int total, int filled, int unitPrice)
    {
        tracker.onOffer(account, slot, state, ITEM, unitPrice, total, filled, filled * unitPrice);
    }

    private static void empty(HeldCostTracker tracker, String account, int slot)
    {
        tracker.onOffer(account, slot, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
    }

    @Test
    void sameItemSameQuantityReplacementCountsBothPurchases()
    {
        HeldCostTracker tracker = new HeldCostTracker();
        offer(tracker, ACCOUNT, 0, GrandExchangeOfferState.BOUGHT, 100, 100, 5500);
        empty(tracker, ACCOUNT, 0);
        offer(tracker, ACCOUNT, 0, GrandExchangeOfferState.BOUGHT, 100, 100, 5500);
        assertArrayEquals(new long[]{200, 5500}, tracker.held(ACCOUNT).get(ITEM));
        assertEquals(200, tracker.boughtInWindow(ACCOUNT, ITEM));
    }

    @Test
    void partialFillsAndRepeatedTerminalUpdatesStayIdempotentWithinOneOffer()
    {
        HeldCostTracker tracker = new HeldCostTracker();
        offer(tracker, ACCOUNT, 0, GrandExchangeOfferState.BUYING, 10, 3, 100);
        offer(tracker, ACCOUNT, 0, GrandExchangeOfferState.BUYING, 10, 3, 100);
        offer(tracker, ACCOUNT, 0, GrandExchangeOfferState.BUYING, 10, 7, 100);
        offer(tracker, ACCOUNT, 0, GrandExchangeOfferState.BOUGHT, 10, 10, 100);
        offer(tracker, ACCOUNT, 0, GrandExchangeOfferState.BOUGHT, 10, 10, 100);
        assertArrayEquals(new long[]{10, 100}, tracker.held(ACCOUNT).get(ITEM));
        assertEquals(10, tracker.boughtInWindow(ACCOUNT, ITEM));
    }

    @Test
    void collectionClearsOfferClocksButPreservesOwnedStockAndHeldRevision()
    {
        HeldCostTracker tracker = new HeldCostTracker();
        offer(tracker, ACCOUNT, 0, GrandExchangeOfferState.BOUGHT, 10, 10, 100);
        long revision = tracker.heldRevision(ACCOUNT);
        assertTrue(tracker.listedMs(ACCOUNT, 0, ITEM) > 0);
        empty(tracker, ACCOUNT, 0);
        empty(tracker, ACCOUNT, 0);
        assertArrayEquals(new long[]{10, 100}, tracker.held(ACCOUNT).get(ITEM));
        assertEquals(revision, tracker.heldRevision(ACCOUNT));
        assertEquals(0, tracker.listedMs(ACCOUNT, 0, ITEM));
        assertEquals(0, tracker.lastProgressMs(ACCOUNT, 0, ITEM));
        assertEquals(0, tracker.lastPriceChangeMs(ACCOUNT, 0, ITEM));
    }

    @Test
    void cancelledPartialBuyThenReplacementCountsOnlyRealFilledAmounts()
    {
        HeldCostTracker tracker = new HeldCostTracker();
        offer(tracker, ACCOUNT, 0, GrandExchangeOfferState.BUYING, 100, 20, 100);
        offer(tracker, ACCOUNT, 0, GrandExchangeOfferState.CANCELLED_BUY, 100, 20, 100);
        empty(tracker, ACCOUNT, 0);
        offer(tracker, ACCOUNT, 0, GrandExchangeOfferState.BUYING, 100, 0, 110);
        offer(tracker, ACCOUNT, 0, GrandExchangeOfferState.CANCELLED_BUY, 100, 20, 110);
        assertArrayEquals(new long[]{40, 105}, tracker.held(ACCOUNT).get(ITEM));
        assertEquals(40, tracker.boughtInWindow(ACCOUNT, ITEM));
    }

    @Test
    void repeatedSameItemSalesConsumeBothOfferInstancesWithoutPhantomRemainder()
    {
        HeldCostTracker tracker = new HeldCostTracker();
        tracker.addManualLot(ACCOUNT, ITEM, 20, 100);
        offer(tracker, ACCOUNT, 0, GrandExchangeOfferState.SELLING, 10, 3, 120);
        offer(tracker, ACCOUNT, 0, GrandExchangeOfferState.SOLD, 10, 10, 120);
        offer(tracker, ACCOUNT, 0, GrandExchangeOfferState.SOLD, 10, 10, 120);
        assertEquals(10, tracker.held(ACCOUNT).get(ITEM)[0]);
        empty(tracker, ACCOUNT, 0);
        offer(tracker, ACCOUNT, 0, GrandExchangeOfferState.SOLD, 10, 10, 120);
        assertTrue(tracker.held(ACCOUNT).isEmpty());
    }

    @Test
    void emptySlotDoesNotResetAnotherSlotOrAccountCounters()
    {
        HeldCostTracker tracker = new HeldCostTracker();
        offer(tracker, ACCOUNT, 0, GrandExchangeOfferState.BOUGHT, 10, 10, 100);
        offer(tracker, ACCOUNT, 1, GrandExchangeOfferState.BOUGHT, 10, 10, 100);
        offer(tracker, "Ghaelvyn", 0, GrandExchangeOfferState.BOUGHT, 10, 10, 100);
        empty(tracker, ACCOUNT, 0);
        offer(tracker, ACCOUNT, 1, GrandExchangeOfferState.BOUGHT, 10, 10, 100);
        offer(tracker, "Ghaelvyn", 0, GrandExchangeOfferState.BOUGHT, 10, 10, 100);
        assertEquals(20, tracker.held(ACCOUNT).get(ITEM)[0]);
        assertEquals(10, tracker.held("Ghaelvyn").get(ITEM)[0]);
    }
}
