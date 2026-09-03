package com.runeassist.flip.controller;

import com.runeassist.flip.model.OfferStatus;
import com.runeassist.flip.model.SavedOffer;
import com.runeassist.flip.model.Transaction;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Instant same-tick sells often report quantitySold without spent (GE tax item
 * sink / client lag). The fill inferrer must still book the flip.
 */
public class GrandExchangeOfferEventHandlerTest {

    @Test
    public void emptyToSoldWithZeroSpentUsesPriceTimesQty() {
        SavedOffer prev = offer(GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
        SavedOffer sold = offer(GrandExchangeOfferState.SOLD, 4151, 100, 100, 1200, 0);

        Transaction t = GrandExchangeOfferEventHandler.inferFill(3, sold, prev, true, false);

        assertNotNull(t);
        assertEquals(OfferStatus.SELL, t.getType());
        assertEquals(4151, t.getItemId());
        assertEquals(100, t.getQuantity());
        assertEquals(1200L * 100, t.getAmountSpent());
        assertEquals(3, t.getBoxId());
    }

    @Test
    public void sellingToSoldWithZeroSpentStillRecords() {
        SavedOffer prev = offer(GrandExchangeOfferState.SELLING, 4151, 0, 100, 1200, 0);
        SavedOffer sold = offer(GrandExchangeOfferState.SOLD, 4151, 100, 100, 1200, 0);

        Transaction t = GrandExchangeOfferEventHandler.inferFill(0, sold, prev, true, false);

        assertNotNull(t);
        assertEquals(100, t.getQuantity());
        assertEquals(1200L * 100, t.getAmountSpent());
    }

    @Test
    public void partialFillUsesActualSpentDelta() {
        SavedOffer prev = offer(GrandExchangeOfferState.SELLING, 4151, 10, 100, 1200, 11_760);
        SavedOffer next = offer(GrandExchangeOfferState.SELLING, 4151, 20, 100, 1200, 23_520);

        Transaction t = GrandExchangeOfferEventHandler.inferFill(1, next, prev, true, false);

        assertNotNull(t);
        assertEquals(10, t.getQuantity());
        assertEquals(11_760L, t.getAmountSpent());
    }

    @Test
    public void noQuantityIncreaseIsNotAFill() {
        SavedOffer prev = offer(GrandExchangeOfferState.SELLING, 4151, 50, 100, 1200, 60_000);
        SavedOffer next = offer(GrandExchangeOfferState.SELLING, 4151, 50, 100, 1200, 60_000);

        assertNull(GrandExchangeOfferEventHandler.inferFill(1, next, prev, true, false));
    }

    private static SavedOffer offer(GrandExchangeOfferState state, int itemId, int sold, int total,
                                    long price, long spent) {
        SavedOffer o = new SavedOffer();
        o.setState(state);
        o.setItemId(itemId);
        o.setQuantitySold(sold);
        o.setTotalQuantity(total);
        o.setPrice(price);
        o.setSpent(spent);
        return o;
    }
}
