package com.runeassist.flip.controller;

import com.runeassist.flip.model.*;
import com.runeassist.flip.ui.flipsdialog.FlipsDialogController;
import com.runeassist.flip.ui.graph.model.Data;
import com.runeassist.flip.rs.OsrsLoginRS;
import com.runeassist.flip.rs.PortfolioStateRS;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.client.callback.ClientThread;
import okhttp3.Call;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class SuggestionLatencyTest {
    private final SuggestionManager manager = new SuggestionManager();
    private final List<GraphRequest> graphs = new ArrayList<>();
    private final Queue<Runnable> clientCallbacks = new ArrayDeque<>();
    private SuggestionController controller;
    private long account = 1L;
    private boolean geOpen = true;
    private int requests;
    private int portfolioUpdates;
    private Instant portfolioSnapshotAt;

    @BeforeEach void setUp() {
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (p,m,a) -> {
                if (m.getName().equals("getAccountHash")) return account;
                if (m.getName().equals("getTickCount")) return 100;
                if (m.getName().equals("getGrandExchangeOffers")) return new GrandExchangeOffer[8];
                throw new AssertionError(m.getName());
            });
        OsrsLoginManager login = new OsrsLoginManager(client) {
            @Override public boolean isValidLoginState() { return true; }
            @Override public boolean hasJustLoggedIn() { return false; }
        };
        GrandExchange ge = new GrandExchange(client) {
            @Override public boolean isOpen() { return geOpen; }
            @Override public boolean isSlotOpen() { return false; }
            @Override boolean isHomeScreenOpen() { return false; }
        };
        AccountStatusManager status = new AccountStatusManager(null,null,null,null,null,null,null,null,null,null,null,null) {
            @Override public AccountStatus getAccountStatus() { return new AccountStatus(); }
        };
        PausedManager paused = new PausedManager(login, null) {
            @Override public boolean isPaused() { return false; }
        };
        HighlightController highlights = new HighlightController(null,null,null,null,null,null,null,null,null,null,null,null) {
            @Override public void removeAll() { }
        };
        ApiRequestHandler api = new ApiRequestHandler(null, null) {
            @Override public Call asyncGetRuneAssistGraph(int itemId, Consumer<Data> onData, Consumer<Throwable> onError) {
                GraphRequest request = new GraphRequest(onData);
                graphs.add(request);
                return request.call;
            }
        };
        ClientThread thread = new ClientThread() {
            @Override public void invokeLater(Runnable runnable) { clientCallbacks.add(runnable); }
        };
        FlipsDialogController dialogs = new FlipsDialogController(null,null,null,null,null,null,null,null,null,null,null,null);
        PortfolioStateRS portfolio = new PortfolioStateRS(new OsrsLoginRS(),null,null,thread,status) {
            @Override public void updatePortfolioState(Map<Integer,Integer> bank, List<Suggestion.PortfolioItem> items,
                    StatusOfferList offers, Map<Integer,Long> uncollected, Instant at, BooleanSupplier stillCurrent) {
                if (stillCurrent.getAsBoolean()) { portfolioUpdates++; portfolioSnapshotAt = at; }
            }
        };
        controller = new SuggestionController(paused,client,null,login,highlights,ge,null,null,null,thread,null,manager,
                status,new GrandExchangeUncollectedManager(client),portfolio,dialogs,null,null,api,null) {
            @Override public void getSuggestionAsync() {
                requests++;
                manager.setSuggestionNeeded(false);
                manager.setSuggestionRequestInProgress(true);
            }
        };
        controller.syncTradingContext();
    }

    @Test void pendingGraphDoesNotBlockRefreshOrAllowOverlappingComposeRequests() {
        manager.setGraphDataReadingInProgress(true);
        manager.setSuggestionNeeded(true);
        for (int i=0; i<100; i++) controller.onGameTick();
        assertEquals(1, requests);
        assertTrue(manager.isSuggestionRequestInProgress());
    }

    @Test void repeatedSameItemRefreshSharesPendingGraphAndCachesCompletion() throws Exception {
        Suggestion first = suggestion(536);
        manager.setSuggestion(first);
        controller.feedSuggestionGraph(first, true);
        Suggestion refreshed = suggestion(536);
        manager.setSuggestion(refreshed);
        controller.feedSuggestionGraph(refreshed, true);
        assertEquals(1, graphs.size());
        assertFalse(graphs.get(0).cancelled);
        graphs.get(0).complete.accept(new Data());
        drain();
        assertFalse(manager.isGraphDataReadingInProgress());
        controller.feedSuggestionGraph(refreshed, true);
        assertEquals(1, graphs.size());
    }

    @Test void changedItemCancelsOldGraphAndLateCallbackCannotReleaseNewGraph() throws Exception {
        manager.setSuggestion(suggestion(536));
        controller.feedSuggestionGraph(manager.getSuggestion(), true);
        manager.setSuggestion(suggestion(397));
        controller.feedSuggestionGraph(manager.getSuggestion(), true);
        assertTrue(graphs.get(0).cancelled);
        graphs.get(0).complete.accept(new Data());
        drain();
        assertTrue(manager.isGraphDataReadingInProgress());
        graphs.get(1).complete.accept(new Data());
        drain();
        assertFalse(manager.isGraphDataReadingInProgress());
    }

    @Test void contextChangeCancelsGraphAndRejectsPortfolioCallbackBeforeStateAccess() throws Exception {
        Suggestion old = suggestion(536);
        manager.setSuggestion(old);
        controller.feedSuggestionGraph(old, true);
        long generation = controller.getTradingContext().generation();
        account = 2L;
        controller.syncTradingContext();
        assertTrue(graphs.get(0).cancelled);
        graphs.get(0).complete.accept(new Data());
        controller.receivePortfolioForContext(generation, old, Collections.emptyList(), Instant.now());
        drain();
        assertNull(manager.getSuggestion());
        assertFalse(manager.isGraphDataReadingInProgress());
        assertEquals(0, portfolioUpdates);
    }

    @Test void slowPortfolioSurvivesRoutineSameAccountCardRefreshAndKeepsSnapshotTime() {
        Suggestion old = suggestion(536);
        Instant snapshotAt = Instant.now().minusSeconds(12);
        manager.setSuggestion(suggestion(397));
        controller.receivePortfolioForContext(controller.getTradingContext().generation(), old, Collections.emptyList(), snapshotAt);
        assertEquals(397, manager.getSuggestion().getItemId());
        assertEquals(1, portfolioUpdates);
        assertEquals(snapshotAt, portfolioSnapshotAt);
    }

    @Test void lowDataModeNeverFetchesGraphAndCancelsOptionalPendingWork() {
        manager.setSuggestion(suggestion(536));
        controller.feedSuggestionGraph(manager.getSuggestion(), true);
        controller.feedSuggestionGraph(manager.getSuggestion(), false);
        assertTrue(graphs.get(0).cancelled);
        assertFalse(manager.isGraphDataReadingInProgress());
        assertEquals(1, graphs.size());
    }

    @Test void dedicatedComposeWorkerIsSerialAndQueueIsBounded() throws Exception {
        ExecutorService executor = new RuneAssistPlugin().provideSuggestionExecutor();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), queued = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            executor.execute(queued::countDown);
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
            assertEquals(1L, queued.getCount());
            release.countDown();
            assertTrue(queued.await(2, TimeUnit.SECONDS));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void sameSingletonWorkerCanBeDisabledAndReenabled() throws Exception {
        com.runeassist.flip.SuggestionTaskExecutor executor = new RuneAssistPlugin().provideSuggestionExecutor();
        try {
            executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
            executor.shutdownNow();
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            executor.start();
            executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
            assertFalse(executor.isShutdown());
        } finally { executor.shutdownNow(); }
    }

    @Test void quickReenableDoesNotOverlapAnOldRequestStillFinishing() throws Exception {
        com.runeassist.flip.SuggestionTaskExecutor executor = new RuneAssistPlugin().provideSuggestionExecutor();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), next = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                boolean done = false;
                while (!done) {
                    try { done = release.await(2, TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) { /* Model a call completing after cancellation. */ }
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            executor.shutdownNow();
            executor.start();
            executor.execute(next::countDown);
            assertFalse(next.await(50, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(next.await(2, TimeUnit.SECONDS));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    private void drain() throws Exception {
        while (!clientCallbacks.isEmpty()) clientCallbacks.remove().run();
        SwingUtilities.invokeAndWait(() -> {});
    }
    private static Suggestion suggestion(int itemId) {
        Suggestion s = new Suggestion(); s.setType(SuggestionType.BUY); s.setItemId(itemId); return s;
    }
    private static class GraphRequest {
        final Consumer<Data> complete;
        final Call call;
        boolean cancelled;
        GraphRequest(Consumer<Data> complete) {
            this.complete = complete;
            call = (Call) Proxy.newProxyInstance(Call.class.getClassLoader(), new Class<?>[]{Call.class}, (p,m,a) -> {
                if (m.getName().equals("cancel")) { cancelled = true; return null; }
                if (m.getName().equals("isCanceled")) return cancelled;
                throw new AssertionError(m.getName());
            });
        }
    }
}
