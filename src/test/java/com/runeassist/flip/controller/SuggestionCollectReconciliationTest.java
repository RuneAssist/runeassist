package com.runeassist.flip.controller;

import com.runeassist.flip.model.*;
import com.runeassist.flip.ui.PauseButton;
import com.runeassist.flip.ui.SuggestionPanel;
import com.runeassist.flip.ui.flipsdialog.FlipsDialogController;
import net.runelite.api.Client;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class SuggestionCollectReconciliationTest {
    private int tick = 100;
    private boolean homeOpen = true;
    private boolean collectVisible;
    private int clears;
    private SuggestionPanel panel;
    private SuggestionController controller;
    private GrandExchangeUncollectedManager uncollected;
    private final SuggestionManager suggestions = new SuggestionManager();

    @BeforeEach void setUp() throws Exception {
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
                new Class<?>[]{Client.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getTickCount": return tick;
                        case "getAccountHash": return 1L;
                        default: throw new AssertionError("Unexpected client call: " + method.getName());
                    }
                });
        OsrsLoginManager login = new OsrsLoginManager(client);
        GrandExchange ge = new GrandExchange(client) {
            @Override boolean isHomeScreenOpen() { return homeOpen; }
            @Override boolean isCollectButtonVisible() { return collectVisible; }
        };
        uncollected = new GrandExchangeUncollectedManager(client) {
            @Override public synchronized void clearAllUncollected(Long accountHash) {
                clears++;
                super.clearAllUncollected(accountHash);
            }
        };
        controller = new SuggestionController(null, client, null, login, null, ge,
                null, null, null, null, null, suggestions, null, uncollected,
                null, null, null, null, null, null);
        SwingUtilities.invokeAndWait(() -> {
            FlipsDialogController dialogs = new FlipsDialogController(null, null, null, null,
                    null, null, null, null, null, null, null, null);
            panel = new SuggestionPanel(null, suggestions, null, null, new PauseButton(null, null),
                    login, client, null, uncollected, null, null, null, ge, dialogs, null, controller);
            controller.setSuggestionPanel(panel);
        });
    }

    @Test void collectToWaitClearsTheHiddenCollectMessage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            panel.suggestCollect();
            assertTrue(panel.isCollectItemsSuggested());
            panel.updateSuggestion(waitSuggestion());
            assertFalse(panel.isCollectItemsSuggested());
        });
        controller.reconcileUncollected();
        assertFalse(suggestions.isSuggestionNeeded());
        assertEquals(0, clears);
    }

    @Test void collectToLoadingClearsTheHiddenCollectMessage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            panel.suggestCollect();
            panel.showLoading();
            assertFalse(panel.isCollectItemsSuggested());
        });
        controller.reconcileUncollected();
        assertFalse(suggestions.isSuggestionNeeded());
        assertEquals(0, clears);
    }

    @Test void staleCardIsConsumedOnceEvenBeforeTheEdtCanRepaint() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            panel.setMessage(new String("Collect items"));
            controller.reconcileUncollected();
            assertFalse(panel.isCollectItemsSuggested());
            assertTrue(suggestions.isSuggestionNeeded());
            suggestions.setSuggestionNeeded(false);
            for (int i = 0; i < 100; i++) {
                tick++;
                controller.reconcileUncollected();
            }
            assertFalse(suggestions.isSuggestionNeeded());
            assertEquals(0, clears, "A stale card must not clear collection bookkeeping");
        });
        SwingUtilities.invokeAndWait(() -> assertFalse(panel.isCollectItemsSuggested()));
    }

    @Test void realStaleItemsAreClearedOnceAndNewFillsCanBeReconciledLater() {
        uncollected.addUncollected(1L, 0, 4151, 1, 0);
        tick += 3;
        controller.reconcileUncollected();
        assertEquals(1, clears);
        assertFalse(uncollected.HasUncollected(1L));
        assertTrue(suggestions.isSuggestionNeeded());
        suggestions.setSuggestionNeeded(false);
        tick++;
        controller.reconcileUncollected();
        assertEquals(1, clears);
        assertFalse(suggestions.isSuggestionNeeded());
        uncollected.addUncollected(1L, 1, 4151, 2, 0);
        tick += 3;
        controller.reconcileUncollected();
        assertEquals(2, clears);
        assertTrue(suggestions.isSuggestionNeeded());
    }

    @Test void gracePeriodProtectsFreshFillsAndCollectCard() throws Exception {
        uncollected.addUncollected(1L, 0, 4151, 1, 0);
        SwingUtilities.invokeAndWait(panel::suggestCollect);
        for (int i = 0; i <= 2; i++) {
            controller.reconcileUncollected();
            assertTrue(panel.isCollectItemsSuggested());
            assertTrue(uncollected.HasUncollected(1L));
            assertEquals(0, clears);
            tick++;
        }
        controller.reconcileUncollected();
        assertFalse(panel.isCollectItemsSuggested());
        assertEquals(1, clears);
    }

    @Test void visibleCollectButtonOrClosedHomeNeverClearsCollectionState() throws Exception {
        uncollected.addUncollected(1L, 0, 4151, 1, 0);
        tick += 3;
        SwingUtilities.invokeAndWait(panel::suggestCollect);
        collectVisible = true;
        controller.reconcileUncollected();
        collectVisible = false;
        homeOpen = false;
        controller.reconcileUncollected();
        assertTrue(panel.isCollectItemsSuggested());
        assertTrue(uncollected.HasUncollected(1L));
        assertFalse(suggestions.isSuggestionNeeded());
        assertEquals(0, clears);
    }

    @Test void delayedDismissalDoesNotOverwriteANewerCollectCard() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            panel.suggestCollect();
            panel.clearCollectSuggestion();
            panel.suggestCollect();
        });
        SwingUtilities.invokeAndWait(() -> assertTrue(panel.isCollectItemsSuggested()));
    }

    @Test void pauseReplacesCollectState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            panel.suggestCollect();
            panel.setIsPausedMessage();
            assertFalse(panel.isCollectItemsSuggested());
        });
    }

    private Suggestion waitSuggestion() {
        Suggestion suggestion = new Suggestion();
        suggestion.setType(SuggestionType.WAIT);
        suggestion.setMessage("Waiting for offers to fill");
        suggestion.setWhy("Offers are active");
        return suggestion;
    }

    @Test void compactWaitAndLoadingHaveReadableStatesAndIgnoreLateTimeouts() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                panel.updateSuggestion(waitSuggestion());
                assertEquals(96, panel.getPreferredSize().height);
                panel.showLoading();
                java.lang.reflect.Field textField = SuggestionPanel.class.getDeclaredField("suggestionText");
                textField.setAccessible(true);
                javax.swing.JLabel text = (javax.swing.JLabel) textField.get(panel);
                assertTrue(text.getText().contains("Getting the next flip"));
                java.lang.reflect.Field timerField = SuggestionPanel.class.getDeclaredField("loadingStatusTimer");
                timerField.setAccessible(true);
                javax.swing.Timer timer = (javax.swing.Timer) timerField.get(panel);
                timer.getActionListeners()[0].actionPerformed(null);
                assertTrue(text.getText().contains("Server slow"));
                panel.showLoading();
                assertTrue(text.getText().contains("Server slow"), "Polling must not reset the slow state");
                panel.suggestCollect();
                timer.getActionListeners()[0].actionPerformed(null);
                assertTrue(text.getText().contains("Collect items"));
                assertFalse(timer.isRunning());
            } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        });
    }
}
