package com.runeassist.flip.controller;

import com.runeassist.flip.HeldCostTracker;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeldOfferObservationContextTest {
    @Test void teardownAndLoginBurstEmptyCannotEraseCountersOrReplayFilledQuantity() {
        HeldCostTracker tracker = new HeldCostTracker();
        tracker.onOffer("Account", 0, GrandExchangeOfferState.BOUGHT, 536, 100, 10, 10, 1000);
        for (GameState state : new GameState[]{GameState.HOPPING, GameState.LOGIN_SCREEN,
                GameState.LOGGING_IN, GameState.CONNECTION_LOST}) {
            boolean observe = RuneAssistPlugin.shouldObserveHeldOffer("Account", state,
                    GrandExchangeOfferState.EMPTY, false, true);
            assertFalse(observe);
            if (observe) tracker.onOffer("Account", 0, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0, 0);
            tracker.onOffer("Account", 0, GrandExchangeOfferState.BOUGHT, 536, 100, 10, 10, 1000);
            assertEquals(10, tracker.held("Account").get(536)[0]);
        }
        assertFalse(RuneAssistPlugin.shouldObserveHeldOffer("Account", GameState.LOGGED_IN,
                GrandExchangeOfferState.EMPTY, true, true));
        assertTrue(RuneAssistPlugin.shouldObserveHeldOffer("Account", GameState.LOGGED_IN,
                GrandExchangeOfferState.EMPTY, true, false));
    }

    @Test void realLoginFillsRemainEligibleButAnonymousStateDoesNot() {
        assertTrue(RuneAssistPlugin.shouldObserveHeldOffer("Account", GameState.LOGGED_IN,
                GrandExchangeOfferState.BOUGHT, true, true));
        assertTrue(RuneAssistPlugin.shouldObserveHeldOffer("Account", GameState.LOGGING_IN,
                GrandExchangeOfferState.BUYING, false, true));
        assertFalse(RuneAssistPlugin.shouldObserveHeldOffer(null, GameState.LOGGED_IN,
                GrandExchangeOfferState.BOUGHT, true, false));
        assertFalse(RuneAssistPlugin.shouldObserveHeldOffer(" ", GameState.LOGGED_IN,
                GrandExchangeOfferState.BOUGHT, true, false));
    }
}
