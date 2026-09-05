package com.runeassist.flip.controller;
import com.runeassist.flip.model.*;
import com.runeassist.flip.ui.GpDropOverlay;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.ui.overlay.OverlayManager;

import static com.runeassist.flip.model.OsrsLoginManager.GE_LOGIN_BURST_WINDOW;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class GrandExchangeOfferEventHandler {

    // dependencies
    private final Client client;
    private final OfferManager offerPersistence;
    private final GrandExchange grandExchange;
    private final TransactionManager transactionManager;
    private final OsrsLoginManager osrsLoginManager;
    private final OverlayManager overlayManager;
    private final GrandExchangeUncollectedManager grandExchangeUncollectedManager;
    private final SuggestionManager suggestionManager;
    private final AccountStatusManager accountStatusManager;
    private final LocalFlipLedger localFlipLedger;

    // state
    private final Queue<Transaction> transactionsToProcess = new ConcurrentLinkedQueue<>();

    public void onGameTick() {
        if(!transactionsToProcess.isEmpty()) {
            processTransactions();
        }
    }

    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged offerEvent) {
        final int slot = offerEvent.getSlot();
        final GrandExchangeOffer offer = offerEvent.getOffer();
        Long accountHash = client.getAccountHash();

        if (offer.getState() == GrandExchangeOfferState.EMPTY && client.getGameState() != GameState.LOGGED_IN) {
            // Trades are cleared by the client during LOGIN_SCREEN/HOPPING/LOGGING_IN, ignore those
            return;
        }
        if (osrsLoginManager.isUnsupportedWorldType()) {
            log.debug("ignoring GE offer update on unsupported world type(s): {}", client.getWorldType());
            return;
        }

        log.debug("tick {} GE offer updated: state: {}, slot: {}, item: {}, qty: {}, lastLoginTick: {}", client.getTickCount(), offer.getState(), slot, offer.getItemId(), offer.getQuantitySold(), osrsLoginManager.getLastLoginTick());

        SavedOffer o = SavedOffer.fromGrandExchangeOffer(offer);

        SavedOffer prev = offerPersistence.loadOffer(accountHash, slot);

        if(Objects.equals(o, prev)) {
            log.debug("skipping duplicate offer event {}", o);
            return;
        }

        boolean consistent = isConsistent(prev, o);
        if(!consistent) {
            log.warn("offer on slot {} is inconsistent with previous saved offer", slot);
        }

        Transaction t = inferTransaction(slot, o, prev, consistent);
        if(t != null) {
            transactionsToProcess.add(t);
            processTransactions();
            log.debug("inferred transaction {}", t);
        }
        updateUncollected(accountHash, slot, o, prev, consistent);
        offerPersistence.saveOffer(accountHash, slot, o);
        if (o.getState() == GrandExchangeOfferState.CANCELLED_BUY
                || o.getState() == GrandExchangeOfferState.CANCELLED_SELL) {
            String displayName = osrsLoginManager.getPlayerDisplayName();
            if (displayName != null) {
                localFlipLedger.recordCancelled(displayName, o);
            }
        }

        // Own a freshly listed or modified offer for ~10 min (leftover qty after
        // cancel-relist looks like sold==0 and must not abort the same tick).
        boolean loginBurst = client.getTickCount() <= osrsLoginManager.getLastLoginTick() + GE_LOGIN_BURST_WINDOW;
        if (!loginBurst && isNewOffer(prev, o)) {
            GrandExchangeOfferState st = o.getState();
            if ((st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.SELLING)
                    && o.getItemId() > 0) {
                accountStatusManager.protectListing(o.getItemId());
            }
        }

        boolean editorOpen = grandExchange.isSlotOpen();
        if (o.getState() == GrandExchangeOfferState.EMPTY) {
            accountStatusManager.releaseOwnedModifyIfSlotEmpty(slot, editorOpen);
        }
        accountStatusManager.releaseStaleOwnedModify(client.getGrandExchangeOffers(), editorOpen);

        // Always fetch suggestion to ensure fast response for better UX — except while
        // a MODIFY is in progress: cancel-then-relist empties the slot and would emit BUY.
        if (!accountStatusManager.isOwnedModifyActive() || !editorOpen) {
            suggestionManager.setSuggestionNeeded(true);
        }
    }


    private void updateUncollected(Long accountHash, int slot, SavedOffer o, SavedOffer prev, boolean consistent) {
        if(!consistent) {
            return;
        }
        long uncollectedGp = 0;
        int uncollectedItems = 0;
        switch (o.getState()) {
            case BUYING:
            case BOUGHT:
                uncollectedItems = isNewOffer(prev, o) ? o.getQuantitySold() : o.getQuantitySold() - prev.getQuantitySold();
                break;
            case SOLD:
            case SELLING:
                uncollectedGp = (isNewOffer(prev, o) ? o.getQuantitySold() : o.getQuantitySold() - prev.getQuantitySold()) * o.getPrice();
                break;
            case CANCELLED_BUY:
                uncollectedGp = (o.getTotalQuantity() - o.getQuantitySold()) * o.getPrice();
                break;
            case CANCELLED_SELL:
                uncollectedItems = o.getTotalQuantity() - o.getQuantitySold();
                break;
            case EMPTY:
                // if the slot is empty we want to ensure that the un collected manager doesn't think there is something to collect
                // this can happen due to race conditions between the collection and offer fills timing
                grandExchangeUncollectedManager.ensureSlotClear(accountHash, slot);
                if (!accountStatusManager.isOwnedModifyActive() || !grandExchange.isSlotOpen()) {
                    suggestionManager.setSuggestionNeeded(true);
                }
                return;
        }
        grandExchangeUncollectedManager.addUncollected(accountHash, slot, o.getItemId(), uncollectedItems, uncollectedGp);

    }

    private void processTransactions() {
        if (osrsLoginManager.isUnsupportedWorldType()) {
            return;
        }
        String displayName = osrsLoginManager.getPlayerDisplayName();
        if(displayName != null) {
            Transaction transaction;
            while ((transaction = transactionsToProcess.poll()) != null) {
                long profit = transactionManager.addTransaction(transaction, displayName);
                if (grandExchange.isHomeScreenOpen() && profit != 0) {
                    new GpDropOverlay(overlayManager, client, profit, transaction.getBoxId());
                }
            }
        }
    }

    public Transaction inferTransaction(int slot, SavedOffer offer, SavedOffer prev, boolean consistent) {
        boolean login = client.getTickCount() <= osrsLoginManager.getLastLoginTick() + GE_LOGIN_BURST_WINDOW;
        return inferFill(slot, offer, prev, consistent, login);
    }

    /**
     * Infer a fill from consecutive offer snapshots. Instant same-tick sells often
     * arrive as EMPTY→SOLD (or SELLING→SOLD) with {@code quantitySold} set but
     * {@code spent} still 0 (GE tax item-sink / client lag). Require a quantity
     * increase and fall back to {@code price * qty} when spent did not move —
     * same fallback {@link LocalFlipLedger#seedFromSavedOffers} already uses.
     */
    static Transaction inferFill(int slot, SavedOffer offer, SavedOffer prev, boolean consistent, boolean login) {
        boolean newOffer = isNewOffer(prev, offer);
        int prevSold = (newOffer || prev == null) ? 0 : prev.getQuantitySold();
        long prevSpent = (newOffer || prev == null) ? 0L : prev.getSpent();
        int quantityDiff = offer.getQuantitySold() - prevSold;
        long amountSpentDiff = offer.getSpent() - prevSpent;
        if (quantityDiff <= 0) {
            return null;
        }
        if (amountSpentDiff <= 0) {
            amountSpentDiff = offer.getPrice() * (long) quantityDiff;
        }
        if (amountSpentDiff <= 0) {
            return null;
        }
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setType(offer.getOfferStatus());
        t.setItemId(offer.getItemId());
        t.setPrice(offer.getPrice());
        t.setQuantity(quantityDiff);
        t.setBoxId(slot);
        t.setAmountSpent(amountSpentDiff);
        t.setTimestamp(Instant.now());
        t.setLogin(login);
        t.setConsistent(consistent);
        return t;
    }

    private boolean isConsistent(SavedOffer prev, SavedOffer updated) {
        if(prev == null) {
            return false;
        }
        if(updated.getState() == GrandExchangeOfferState.EMPTY) {
            return true;
        }
        if(prev.getState() == GrandExchangeOfferState.EMPTY && !(updated.getState() == GrandExchangeOfferState.CANCELLED_BUY || updated.getState() == GrandExchangeOfferState.CANCELLED_SELL)) {
            return true;
        }
        return prev.getOfferStatus() == updated.getOfferStatus() ||
                prev.getItemId() == updated.getItemId()
                || prev.getPrice() == updated.getPrice()
                || prev.getTotalQuantity() == updated.getTotalQuantity();
    }

    static boolean isNewOffer(SavedOffer prev, SavedOffer updated) {
        if (prev == null) {
            return true;
        }
        return prev.getOfferStatus() != updated.getOfferStatus() ||
                prev.getItemId() != updated.getItemId()
                || prev.getPrice() != updated.getPrice()
                || prev.getTotalQuantity() != updated.getTotalQuantity()
                || prev.getQuantitySold() > updated.getQuantitySold()
                || prev.getSpent() > updated.getSpent();
    }
}
