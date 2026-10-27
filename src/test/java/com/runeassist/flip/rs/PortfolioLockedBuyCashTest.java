package com.runeassist.flip.rs;

import com.runeassist.flip.model.Offer;
import com.runeassist.flip.model.OfferStatus;
import com.runeassist.flip.model.StatusOfferList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortfolioLockedBuyCashTest {

    private static Offer offer(OfferStatus status, int slot, long price, int total, int traded, boolean active) {
        return new Offer(status, 4151, price, total, traded * price, traded, slot, active);
    }

    @Test
    void onlyTheUnfilledPartOfActiveBuysIsLockedCash() {
        StatusOfferList offers = new StatusOfferList();
        offers.set(0, offer(OfferStatus.BUY, 0, 29_218L, 146, 70, true));
        offers.set(1, offer(OfferStatus.BUY, 1, 7_844_000L, 1, 0, true));
        offers.set(2, offer(OfferStatus.BUY, 2, 9_265_000L, 1, 1, false));
        offers.set(3, offer(OfferStatus.BUY, 3, 344L, 3038, 1200, false));
        offers.set(4, offer(OfferStatus.SELL, 4, 217_000L, 8, 8, false));
        offers.set(5, offer(OfferStatus.SELL, 5, 1_000L, 100, 10, true));
        assertEquals(76L * 29_218L + 7_844_000L, PortfolioStateRS.totalLockedBuyCash(offers));
    }

    @Test
    void noOffersMeansNoLockedCash() {
        assertEquals(0L, PortfolioStateRS.totalLockedBuyCash(null));
        assertEquals(0L, PortfolioStateRS.totalLockedBuyCash(new StatusOfferList()));
    }
}
