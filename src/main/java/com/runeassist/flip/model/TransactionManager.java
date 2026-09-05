package com.runeassist.flip.model;

import com.runeassist.flip.controller.FlipHistorySyncService;
import com.runeassist.flip.controller.Persistance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TransactionManager {

    private final ScheduledExecutorService executorService;
    private final OsrsLoginManager osrsLoginManager;
    private final LocalFlipLedger localFlipLedger;
    private final OfferManager offerManager;
    private final FlipHistorySyncService flipHistorySyncService;

    private final ConcurrentMap<String, List<Transaction>> cachedUnAckedTransactions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicBoolean> transactionSyncScheduled = new ConcurrentHashMap<>();

    public void syncUnAckedTransactions(String displayName) {
        synchronized (this) {
            AtomicBoolean scheduled = transactionSyncScheduled.get(displayName);
            if (scheduled != null) {
                scheduled.set(false);
            }
        }
        // Legacy copilot transaction upload is disabled. FlipHistorySyncService
        // handles opt-in history sync independently of this queue.
    }

    /**
     * Load the session flip book for this account and replay any leftover unacked
     * GE fills that were recorded before the local ledger existed.
     */
    public void hydrateLocal(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return;
        }
        localFlipLedger.hydrate(displayName);
        seedSavedOffers(displayName);
        captureCurrentCancelled(displayName);
        List<Transaction> leftover;
        synchronized (this) {
            leftover = new ArrayList<>(getUnAckedTransactions(displayName));
        }
        if (!leftover.isEmpty()) {
            log.info("replaying {} stored GE fills into session flip ledger for {}", leftover.size(), displayName);
            localFlipLedger.applyAll(leftover, displayName);
            synchronized (this) {
                getUnAckedTransactions(displayName).clear();
                Persistance.storeUnackedTransactions(Collections.emptyList(), displayName);
            }
        }
    }

    private void seedSavedOffers(String displayName) {
        if (localFlipLedger.hasFlips(displayName)) {
            return;
        }
        Long accountHash = osrsLoginManager.getAccountHash();
        if (accountHash == null) {
            return;
        }
        List<SavedOffer> offers = new ArrayList<>(8);
        for (int slot = 0; slot < 8; slot++) {
            offers.add(offerManager.loadOffer(accountHash, slot));
        }
        localFlipLedger.seedFromSavedOffers(displayName, offers);
    }

    /** Seed the empty local ledger from the live GE box, not just persisted slots. */
    public void seedLiveOffers(String displayName, net.runelite.api.GrandExchangeOffer[] live) {
        if (displayName == null || displayName.isEmpty() || live == null || live.length == 0) {
            return;
        }
        List<SavedOffer> offers = new ArrayList<>(live.length);
        for (net.runelite.api.GrandExchangeOffer offer : live) {
            offers.add(offer == null ? null : SavedOffer.fromGrandExchangeOffer(offer));
        }
        localFlipLedger.seedFromSavedOffers(displayName, offers);
        localFlipLedger.captureCancelledFromOffers(displayName, offers);
    }

    private void captureCurrentCancelled(String displayName) {
        Long accountHash = osrsLoginManager.getAccountHash();
        if (accountHash == null) {
            return;
        }
        List<SavedOffer> offers = new ArrayList<>(8);
        for (int slot = 0; slot < 8; slot++) {
            offers.add(offerManager.loadOffer(accountHash, slot));
        }
        localFlipLedger.captureCancelledFromOffers(displayName, offers);
    }

    public long addTransaction(Transaction transaction, String displayName) {
        if (osrsLoginManager.isUnsupportedWorldType()) {
            log.debug("ignoring transaction for {} on unsupported world type(s)", displayName);
            return 0;
        }
        hydrateLocal(displayName);
        long profit = localFlipLedger.apply(transaction, displayName);
        flipHistorySyncService.enqueue(transaction, displayName);
        synchronized (this) {
            List<Transaction> unAckedTransactions = getUnAckedTransactions(displayName);
            UUID id = transaction.getId();
            if (id != null) {
                unAckedTransactions.removeIf(t -> id.equals(t.getId()));
                Persistance.storeUnackedTransactions(unAckedTransactions, displayName);
            }
        }
        return profit;
    }

    public List<Transaction> getUnAckedTransactions(String displayName) {
        return cachedUnAckedTransactions.computeIfAbsent(displayName, Persistance::loadUnackedTransactions);
    }

    public synchronized void scheduleSyncIn(int seconds, String displayName) {
        AtomicBoolean scheduled = transactionSyncScheduled.computeIfAbsent(displayName, k -> new AtomicBoolean(false));
        if (scheduled.compareAndSet(false, true)) {
            log.info("scheduling {} session ledger settle in {}s", displayName, seconds);
            executorService.schedule(() -> this.syncUnAckedTransactions(displayName), seconds, TimeUnit.SECONDS);
        } else {
            log.debug("skipping scheduling sync as already scheduled");
        }
    }
}
