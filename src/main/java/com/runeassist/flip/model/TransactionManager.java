package com.runeassist.flip.model;

import com.runeassist.flip.controller.FlipHistorySyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session GE-fill intake. Applies fills to {@link LocalFlipLedger} for instant UI and
 * durable-queues them through {@link FlipHistorySyncService} (unacked JSONL → server
 * ackedIds). Flip history itself is server-owned after device/OSRS link.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TransactionManager {

    private final OsrsLoginManager osrsLoginManager;
    private final LocalFlipLedger localFlipLedger;
    private final OfferManager offerManager;
    private final FlipHistorySyncService flipHistorySyncService;

    /** Display names whose unacked JSONL has been replayed into the in-memory ledger this session. */
    private final Set<String> replayedUnacked = ConcurrentHashMap.newKeySet();

    public void syncUnAckedTransactions(String displayName) {
        flipHistorySyncService.flushNow();
    }

    /**
     * Load the session flip book and replay any leftover unacked GE fills into the
     * in-memory ledger for instant UI. Unacked rows stay on disk until the server
     * returns {@code ackedIds}.
     */
    public void hydrateLocal(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return;
        }
        localFlipLedger.hydrate(displayName);
        seedSavedOffers(displayName);
        captureCurrentCancelled(displayName);
        if (!replayedUnacked.add(displayName)) {
            return;
        }
        List<Transaction> leftover = flipHistorySyncService.listUnacked(displayName);
        if (!leftover.isEmpty()) {
            log.info("replaying {} stored GE fills into session flip ledger for {}", leftover.size(), displayName);
            localFlipLedger.applyAll(leftover, displayName);
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
        // Persist to unacked JSONL first (FC-shaped); upload clears via server ackedIds.
        flipHistorySyncService.enqueue(transaction, displayName);
        return profit;
    }

    public List<Transaction> getUnAckedTransactions(String displayName) {
        return flipHistorySyncService.listUnacked(displayName);
    }

}
