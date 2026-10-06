package com.runeassist.flip.controller;

import com.runeassist.flip.RuneAssistSuggestionSource;
import com.runeassist.flip.model.*;
import net.runelite.api.Client;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SuggestionTradingContextTest {
    private long account = 1L;
    private boolean loggedIn = true;
    private boolean geOpen = true;
    private int clearHighlights;
    private boolean physicalStock = true;
    private boolean fillingSell;
    private boolean setupOpen;
    private final Map<Integer, Long> uncollectedStock = new HashMap<>();
    private final Map<Long, Boolean> pauses = new HashMap<>();
    private final SuggestionManager suggestions = new SuggestionManager();
    private SuggestionController controller;

    @BeforeEach void setUp() {
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
                new Class<?>[]{Client.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getAccountHash")) return account;
                    throw new AssertionError("Unexpected client call: " + method.getName());
                });
        OsrsLoginManager login = new OsrsLoginManager(client) {
            @Override public boolean isValidLoginState() { return loggedIn; }
        };
        GrandExchange ge = new GrandExchange(client) {
            @Override public boolean isOpen() { return geOpen; }
            @Override boolean hasFillingSellOffer(int itemId) { return fillingSell; }
            @Override public boolean isSetupOfferOpen() { return setupOpen; }
        };
        GrandExchangeUncollectedManager uncollected = new GrandExchangeUncollectedManager(client) {
            @Override public synchronized Map<Integer, Long> loadAllUncollected(Long accountHash) {
                return uncollectedStock;
            }
        };
        PausedManager paused = new PausedManager(login, null) {
            @Override public boolean isPaused() { return pauses.getOrDefault(account, false); }
        };
        HighlightController highlights = new HighlightController(null, null, null, null,
                null, null, null, null, null, null, null, null) {
            @Override public void removeAll() { clearHighlights++; }
        };
        RuneAssistSuggestionSource source = new RuneAssistSuggestionSource() {
            @Override public boolean hasInventoryForSale(Suggestion suggestion) { return physicalStock; }
        };
        controller = new SuggestionController(paused, client, null, login, highlights, ge,
                null, null, null, null, null, suggestions, null, uncollected,
                null, null, null, source, null, null);
        controller.syncTradingContext();
        clearHighlights = 0;
    }

    @Test void leavingGeClearsActionAndPollingFlagsWithoutRepeatedUiChurn() {
        suggestions.setSuggestion(suggestion(SuggestionType.ABORT));
        suggestions.setSuggestionRequestInProgress(true);
        suggestions.setGraphDataReadingInProgress(true);
        suggestions.setSuggestionRefreshPending(true);
        geOpen = false;
        for (int i = 0; i < 100; i++) controller.onGameTick();
        assertNull(suggestions.getSuggestion());
        assertFalse(suggestions.isSuggestionRequestInProgress());
        assertFalse(suggestions.isGraphDataReadingInProgress());
        assertFalse(suggestions.isSuggestionRefreshPending());
        assertFalse(suggestions.isSuggestionNeeded());
        assertEquals(1, clearHighlights);
        // Direct refreshes (preferences/skip/plugin changes) must also stay quiet.
        controller.getSuggestionAsync();
        assertFalse(suggestions.isSuggestionRequestInProgress());
    }

    @Test void returningStartsFreshAndLateResponseCannotReleaseTheNewRequest() {
        long oldGeneration = controller.getTradingContext().generation();
        geOpen = false;
        controller.syncTradingContext();
        geOpen = true;
        assertTrue(controller.syncTradingContext());
        assertTrue(suggestions.isSuggestionNeeded());
        Suggestion current = suggestion(SuggestionType.WAIT);
        suggestions.setSuggestion(current);
        suggestions.setSuggestionRequestInProgress(true);
        controller.receiveForContext(oldGeneration, null, suggestion(SuggestionType.ABORT), null, true);
        assertSame(current, suggestions.getSuggestion());
        assertTrue(suggestions.isSuggestionRequestInProgress());
    }

    @Test void lateOldAccountResponseDoesNotTouchTheNewAccountsState() {
        long oldGeneration = controller.getTradingContext().generation();
        account = 2L;
        controller.syncTradingContext();
        Suggestion current = suggestion(SuggestionType.WAIT);
        suggestions.setSuggestion(current);
        suggestions.setSuggestionRequestInProgress(true);
        controller.receiveForContext(oldGeneration, null, suggestion(SuggestionType.ABORT), null, false);
        assertSame(current, suggestions.getSuggestion());
        assertTrue(suggestions.isSuggestionRequestInProgress());
    }

    @Test void logoutInvalidatesRequestsEvenWhenSameAccountReturns() {
        long oldGeneration = controller.getTradingContext().generation();
        suggestions.setSuggestionRequestInProgress(true);
        controller.onSessionEnded();
        assertFalse(suggestions.isSuggestionRequestInProgress());
        controller.syncTradingContext();
        controller.receiveForContext(oldGeneration, null, suggestion(SuggestionType.ABORT), null, false);
        assertNull(suggestions.getSuggestion());
    }

    @Test void pausedAccountStaysPausedOnGeReopenAndOtherAccountCanTrade() {
        pauses.put(1L, true);
        assertFalse(controller.syncTradingContext());
        geOpen = false;
        controller.syncTradingContext();
        geOpen = true;
        assertFalse(controller.syncTradingContext());
        account = 2L;
        assertTrue(controller.syncTradingContext());
        account = 1L;
        assertFalse(controller.syncTradingContext());
        assertTrue(pauses.get(1L));
    }

    @Test void manualPauseDiscardsPendingRepliesAndDumpAlerts() {
        long oldGeneration = controller.getTradingContext().generation();
        suggestions.setSuggestionRequestInProgress(true);
        pauses.put(1L, true);
        controller.receiveForContext(oldGeneration, null, suggestion(SuggestionType.BUY), null, true);
        Suggestion dump = suggestion(SuggestionType.BUY);
        dump.isDumpAlert = true;
        controller.handleDumpSuggestion(dump);
        assertNull(suggestions.getSuggestion());
        assertFalse(suggestions.isSuggestionRequestInProgress());
    }

    @Test void missingPhysicalSellStockNeverReachesUiOrNotificationSideEffects() {
        physicalStock = false;
        suggestions.setSuggestionRequestInProgress(true);
        controller.receiveForContext(controller.getTradingContext().generation(), null,
                suggestion(SuggestionType.SELL), null, true);
        assertTrue(suggestions.getSuggestion().isWaitSuggestion());
        assertFalse(suggestions.isSuggestionRequestInProgress());
        assertFalse(suggestions.isGraphDataReadingInProgress());
        assertFalse(suggestions.isSuggestionNeeded(), "Use normal refresh cadence instead of retrying every tick");
    }

    @Test void queuedDumpFromOldStreamCannotCrossAccountsOrReopenedGe() {
        long generation = controller.getTradingContext().generation();
        account = 2L;
        controller.handleDumpSuggestion(suggestion(SuggestionType.BUY), generation);
        assertNull(suggestions.getSuggestion());
        assertTrue(suggestions.isSuggestionNeeded());
        generation = controller.getTradingContext().generation();
        geOpen = false;
        controller.syncTradingContext();
        geOpen = true;
        controller.handleDumpSuggestion(suggestion(SuggestionType.BUY), generation);
        assertNull(suggestions.getSuggestion());
    }

    @Test void localDecantInstructionSurvivesLeavingGeButNotSwitchingAccounts() {
        Suggestion decant = suggestion(SuggestionType.DECANT);
        suggestions.setSuggestion(decant);
        geOpen = false;
        assertFalse(controller.syncTradingContext());
        assertSame(decant, suggestions.getSuggestion());
        assertFalse(suggestions.isSuggestionNeeded());
        account = 2L;
        controller.syncTradingContext();
        assertNull(suggestions.getSuggestion());
    }

    @Test void modifySellMayCancelAndCollectButCannotRelistMissingStock() {
        Suggestion modify = suggestion(SuggestionType.MODIFY_SELL);
        physicalStock = false;
        fillingSell = true;
        assertTrue(controller.isSellAvailableNow(modify));
        fillingSell = false;
        uncollectedStock.put(536, 10L);
        assertTrue(controller.isSellAvailableNow(modify));
        setupOpen = true;
        assertFalse(controller.isSellAvailableNow(modify));
        uncollectedStock.clear();
        assertFalse(controller.isSellAvailableNow(modify));
        physicalStock = true;
        assertTrue(controller.isSellAvailableNow(modify));
        physicalStock = false;
        fillingSell = true;
        assertFalse(controller.isSellAvailableNow(suggestion(SuggestionType.SELL)),
                "An ordinary new sale cannot reuse stock already listed elsewhere");
    }

    private static Suggestion suggestion(SuggestionType type) {
        Suggestion suggestion = new Suggestion();
        suggestion.setType(type);
        suggestion.setItemId(536);
        suggestion.setQuantity(10);
        return suggestion;
    }
}
