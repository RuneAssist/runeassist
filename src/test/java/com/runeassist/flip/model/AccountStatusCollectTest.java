package com.runeassist.flip.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WAIT with a finished box must prompt Collect: on a full board so a slot frees
 * for selling held stock, and otherwise because there is nothing else to point at.
 */
public class AccountStatusCollectTest {

    @Test
    public void waitOnFullBoardWithFinishedOfferAsksCollect() {
        AccountStatus status = memberStatus();
        StatusOfferList offers = status.getOffers();
        for (int i = 0; i < 7; i++) {
            offers.set(i, fillingBuy(i, 1000 + i));
        }
        offers.set(7, finishedSell(7, 2199));

        assertTrue(status.isCollectNeeded(waitSuggestion(), false));
    }

    @Test
    public void waitOnFullBoardAllFillingDoesNotAskCollect() {
        AccountStatus status = memberStatus();
        StatusOfferList offers = status.getOffers();
        for (int i = 0; i < 8; i++) {
            offers.set(i, fillingBuy(i, 1000 + i));
        }

        assertFalse(status.isCollectNeeded(waitSuggestion(), false));
    }

    @Test
    public void waitWithEmptySlotsStillAsksCollectForAFinishedOffer() {
        // Nothing else to do, so point at the coins or items waiting in the slot,
        // as after a cancel by hand.
        AccountStatus status = memberStatus();
        StatusOfferList offers = status.getOffers();
        offers.set(0, finishedSell(0, 2199));
        // remaining slots stay EMPTY from the constructor

        assertTrue(status.isCollectNeeded(waitSuggestion(), false));
        assertFalse(status.isCollectNeeded(waitSuggestion(), true), "not while an offer is being set up");
    }

    private static AccountStatus memberStatus() {
        AccountStatus status = new AccountStatus();
        status.setWorldMember(true);
        status.setAccountMember(true);
        status.setReservedSlots(0);
        return status;
    }

    private static Suggestion waitSuggestion() {
        Suggestion s = new Suggestion();
        s.setType(SuggestionType.WAIT);
        return s;
    }

    private static Offer fillingBuy(int slot, int itemId) {
        return new Offer(OfferStatus.BUY, itemId, 1000L, 100, 50_000L, 50, slot, true);
    }

    private static Offer finishedSell(int slot, int itemId) {
        return new Offer(OfferStatus.SELL, itemId, 2025L, 10, 20_250L, 10, slot, false);
    }
}
