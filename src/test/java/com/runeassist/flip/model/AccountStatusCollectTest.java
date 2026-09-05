package com.runeassist.flip.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WAIT with a full GE board and a finished box must prompt Collect so a slot
 * frees for selling held stock. Reserved-slots=0 used to skip that prompt.
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
    public void waitWithEmptySlotDoesNotAskCollectForFinishedOffer() {
        AccountStatus status = memberStatus();
        StatusOfferList offers = status.getOffers();
        offers.set(0, finishedSell(0, 2199));
        // remaining slots stay EMPTY from the constructor

        assertFalse(status.isCollectNeeded(waitSuggestion(), false));
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
