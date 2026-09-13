package com.runeassist.flip;

import com.runeassist.flip.model.*;
import com.runeassist.flip.rs.OsrsLoginRS;
import com.runeassist.flip.rs.PortfolioStateRS;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class SuggestionEnrichmentTest {
    @Test void coldLimitCacheNeverDownloadsAndUnknownRemainsUnknown() {
        assertEquals(0, new AresMarketClient(null, null).cachedGeLimit(536));
    }

    @Test void finalLimitStampPreservesServerEvidenceWithoutFetchingMapping() throws Exception {
        RuneAssistSuggestionSource source = new RuneAssistSuggestionSource();
        int[] localRemaining = {-1};
        set(source, "market", new AresMarketClient(null,null));
        set(source, "heldCostTracker", new HeldCostTracker() {
            @Override public synchronized int remainingLimitOrUnknown(String name, int id, int limit) { return localRemaining[0]; }
        });
        Suggestion s = new Suggestion(); s.setItemId(536); s.setGeLimit(100); s.setRemainingLimit(75); s.setLimitKnown(true);
        Method stamp = RuneAssistSuggestionSource.class.getDeclaredMethod("stampLimitFields", String.class, Suggestion.class, long[][].class);
        stamp.setAccessible(true);
        stamp.invoke(source,"synthetic",s,new long[8][]);
        assertEquals(100,s.getGeLimit()); assertEquals(75,s.getRemainingLimit()); assertTrue(s.isLimitKnown());
        localRemaining[0] = 90;
        stamp.invoke(source,"synthetic",s,new long[8][]);
        assertEquals(75,s.getRemainingLimit(),"Local observations must not widen server-known remaining limits");
        localRemaining[0] = -1;
        s.setGeLimit(0); s.setRemainingLimit(-1); s.setLimitKnown(false);
        stamp.invoke(source,"synthetic",s,new long[8][]);
        assertEquals(0,s.getGeLimit()); assertEquals(-1,s.getRemainingLimit()); assertFalse(s.isLimitKnown());
    }

    @Test void changedHoldingsOrOfferFillRejectsSlowValuationSnapshot() {
        Map<Integer,long[]> held = Map.of(536,new long[]{10,100});
        assertTrue(RuneAssistSuggestionSource.samePortfolioSnapshot(held,Map.of(536,new long[]{10,100}),new long[8][],new long[8][]));
        assertFalse(RuneAssistSuggestionSource.samePortfolioSnapshot(held,Map.of(536,new long[]{8,100}),new long[8][],new long[8][]));
        long[][] before = {{536,1,100,2,10,1}};
        long[][] after = {{536,1,100,3,10,1}};
        assertFalse(RuneAssistSuggestionSource.samePortfolioSnapshot(held,held,before,after));
    }

    @Test void optionalPortfolioIsSingleFlightSnapshotTimedAndDoesNotRunBeforeWorker() throws Exception {
        Fixture f = new Fixture();
        List<Instant> received = new ArrayList<>();
        f.source.getPortfolioItemsAsync((items,at)->received.add(at));
        Instant afterSnapshot = Instant.now();
        f.source.getPortfolioItemsAsync((items,at)->received.add(at));
        assertEquals(1,f.work.size()); assertEquals(0,f.quoteCalls); assertTrue(received.isEmpty());
        f.work.remove().run();
        assertEquals(1,f.quoteCalls); assertTrue(received.isEmpty());
        f.callbacks.remove().run();
        assertEquals(1,received.size()); assertFalse(received.get(0).isAfter(afterSnapshot));
    }

    @Test void snapshotFailureReleasesSingleFlightAndNextAttemptCanRecover() throws Exception {
        Fixture f = new Fixture(); f.throwSnapshot = true;
        f.source.getPortfolioItemsAsync((items,at)->fail("snapshot failed"));
        assertTrue(f.work.isEmpty());
        f.throwSnapshot = false;
        f.source.getPortfolioItemsAsync((items,at)->{});
        assertEquals(1,f.work.size());
    }

    @Test void changedQuantityAndDifferentAccountDiscardLatePortfolio() throws Exception {
        Fixture f = new Fixture();
        f.source.getPortfolioItemsAsync((items,at)->fail("stale quantity"));
        f.work.remove().run(); f.quantity = 9; f.callbacks.remove().run();
        set(f.source,"lastPortfolioRequestAt",0L);
        f.source.getPortfolioItemsAsync((items,at)->fail("other account"));
        f.work.remove().run(); f.account = 2; f.callbacks.remove().run();
    }

    @Test void olderPortfolioSnapshotCannotOverwriteNewerValuationAndGuardRunsWhenQueued() throws Exception {
        Queue<BooleanSupplier> callbacks = new ArrayDeque<>();
        ClientThread thread = new ClientThread() {
            @Override public void invokeLater(BooleanSupplier action) { callbacks.add(action); }
        };
        PortfolioStateRS state = new PortfolioStateRS(new OsrsLoginRS(),null,null,thread,null);
        Instant newer = Instant.now();
        set(state,"portfolioItemsServerTime",newer);
        // Null dependencies are deliberate: a rejected update must not reach state-building.
        state.updatePortfolioState(null,Collections.emptyList(),null,null,newer.minusSeconds(12),()->true);
        assertTrue(callbacks.remove().getAsBoolean());
        boolean[] current = {true};
        state.updatePortfolioState(null,Collections.emptyList(),null,null,newer.plusSeconds(1),()->current[0]);
        current[0] = false;
        assertTrue(callbacks.remove().getAsBoolean());
    }

    private static void set(Object object,String name,Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object,value);
    }

    private static class Fixture {
        final RuneAssistSuggestionSource source = new RuneAssistSuggestionSource();
        final Queue<Runnable> work = new ArrayDeque<>(), callbacks = new ArrayDeque<>();
        int quoteCalls, quantity = 10;
        long account = 1;
        boolean throwSnapshot;
        Fixture() throws Exception {
            Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),new Class<?>[]{Client.class},(p,m,a)->{
                if (m.getName().equals("getAccountHash")) return account;
                if (m.getName().equals("getGrandExchangeOffers")) return null;
                throw new AssertionError(m.getName());
            });
            set(source,"client",client);
            set(source,"osrsLoginManager",new OsrsLoginManager(client) {
                @Override public boolean isValidLoginState() { return true; }
                @Override public String getPlayerDisplayName() { return "synthetic"; }
            });
            set(source,"heldCostTracker",new HeldCostTracker() {
                @Override public synchronized Map<Integer,long[]> held(String name) {
                    if (throwSnapshot) throw new IllegalStateException("synthetic snapshot failure");
                    return Map.of(536,new long[]{quantity,100});
                }
            });
            set(source,"market",new AresMarketClient(null,null) {
                @Override public Map<Integer,Map<String,Object>> quotes(Collection<Integer> ids) {
                    quoteCalls++; return Map.of(536,Map.of("sell_at",120,"tax_at_sell",2));
                }
            });
            set(source,"clientThread",new ClientThread() {
                @Override public void invokeLater(Runnable action) { callbacks.add(action); }
            });
            set(source,"executor",new AbstractExecutorService() {
                @Override public void execute(Runnable action) { work.add(action); }
                @Override public void shutdown() { }
                @Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
                @Override public boolean isShutdown() { return false; }
                @Override public boolean isTerminated() { return false; }
                @Override public boolean awaitTermination(long time,TimeUnit unit) { return false; }
            });
        }
    }
}
