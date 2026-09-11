package com.runeassist.flip.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradingContextTest {
    @Test void awayDoesNotPollAndReopeningInvalidatesEarlierResponses() {
        TradingContext context = new TradingContext();
        assertTrue(context.update(1L, true, true, false));
        long before = context.generation();
        assertTrue(context.accepts(before));
        assertFalse(context.update(1L, true, true, false));
        assertTrue(context.update(1L, true, false, false));
        assertFalse(context.canRequest());
        assertFalse(context.accepts(before));
        for (int i = 0; i < 100; i++) assertFalse(context.update(1L, true, false, false));
        assertTrue(context.update(1L, true, true, false));
        assertTrue(context.canRequest());
        assertFalse(context.accepts(before));
    }

    @Test void manualPauseIsNotReleasedByOpeningGe() {
        TradingContext context = new TradingContext();
        context.update(1L, true, true, false);
        long beforePause = context.generation();
        context.update(1L, true, true, true);
        context.update(1L, true, false, true);
        context.update(1L, true, true, true);
        assertFalse(context.canRequest());
        assertFalse(context.accepts(beforePause));
        context.update(1L, true, true, false);
        assertTrue(context.canRequest());
        assertFalse(context.accepts(beforePause));
    }

    @Test void accountSwitchHasIndependentPauseAndNeverAcceptsTheOtherAccountsReply() {
        TradingContext context = new TradingContext();
        context.update(1L, true, true, false);
        long firstAccount = context.generation();
        context.update(2L, true, true, true);
        assertFalse(context.canRequest());
        assertFalse(context.accepts(firstAccount));
        assertFalse(context.sameAccount(1L));
        context.update(1L, true, true, false);
        assertTrue(context.canRequest());
        assertFalse(context.accepts(firstAccount));
    }

    @Test void logoutAndReloginSameAccountStillRejectsInFlightReply() {
        TradingContext context = new TradingContext();
        context.update(1L, true, true, false);
        long previousSession = context.generation();
        context.invalidate();
        assertFalse(context.canRequest());
        assertFalse(context.sameAccount(1L));
        context.update(1L, true, true, false);
        assertFalse(context.accepts(previousSession));
        assertTrue(context.canRequest());
    }

    @Test void invalidLoginNeverRequestsEvenWithLeftoverGeWidgets() {
        TradingContext context = new TradingContext();
        context.update(1L, false, true, false);
        assertFalse(context.canRequest());
        context.update(null, true, true, false);
        assertFalse(context.canRequest());
    }
}
