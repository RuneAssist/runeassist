package com.runeassist.flip.model;

import com.runeassist.flip.controller.*;
import com.runeassist.flip.rs.BankStateRS;
import com.runeassist.flip.rs.HeldItemSyncStateRS;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.gameval.InventoryID;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AccountStatusManager {

    // dependencies
    private final Client client;
    private final OsrsLoginManager osrsLoginManager;
    private final GrandExchangeUncollectedManager geUncollected;
    private final SuggestionPreferencesManager suggestionPreferencesManager;
    private final PausedManager pausedManager;
    private final PortfolioController portfolioController;
    private final GrandExchange grandExchange;
    private final BankStateRS bankStateRS;
    private final TransactionManager transactionManager;
    private final HeldItemSyncStateRS heldItemSyncStateRS;
    private final ItemController itemController;
    private final SuggestionManager suggestionManager;
    private final com.osrsmcp.TelemetryService telemetry;

    /** Skip lasts 45 minutes so an abort/skip does not loop the same item, then it can surface again. */
    private static final long SKIP_TTL_MS = 45L * 60L * 1000L;
    /** After we suggest BUY/SELL, do not ABORT that item for this long (list-then-abort). */
    private static final long PROTECT_ABORT_TTL_MS = 10L * 60L * 1000L;

    // state
    @Setter
    private int skipSuggestion = -1;
    /** itemId -> expire-at epoch millis. Same skip set as the Skip button. */
    private final Map<Integer, Long> skippedItemUntil = new HashMap<>();
    /** User Skip only: do not ABORT/MODIFY this item (leave the live offer). */
    private final Map<Integer, Long> skipOfferUntil = new HashMap<>();
    /** itemId -> expire-at: listings we suggested, protected from immediate ABORT. */
    private final Map<Integer, Long> protectAbortUntil = new HashMap<>();

    public synchronized AccountStatus getAccountStatus() {
        Long accountHash =  osrsLoginManager.getAccountHash();
        ItemContainer itemContainer = client.getItemContainer(InventoryID.INV);
        Inventory inventory;
        if(itemContainer == null) {
            log.warn("Item container was null!");
            inventory = new Inventory();
        } else {
            inventory = Inventory.fromRunelite(itemContainer, client);
        }
        Map<Integer, Long> u = geUncollected.loadAllUncollected(accountHash);

        GrandExchangeOffer[] geOffers = client.getGrandExchangeOffers();
        StatusOfferList offerList = StatusOfferList.fromRunelite(geOffers);

        AccountStatus status = new AccountStatus();
        status.setOffers(offerList);
        status.setInventory(inventory);
        status.setUncollected(u);
        status.setDisplayName(osrsLoginManager.getPlayerDisplayName());
        status.setSkipSuggestion(skipSuggestion);
        status.setSellOnlyMode(suggestionPreferencesManager.isSellOnlyMode());
        status.setBuyAndHold(suggestionPreferencesManager.isBuyAndHold());
        status.setF2pOnlyMode(suggestionPreferencesManager.isF2pOnlyMode());
        status.setWorldMember(osrsLoginManager.isMembersWorld());
        status.setAccountMember(osrsLoginManager.isAccountMember());
        status.setSuggestionsPaused(pausedManager.isPaused());
        status.setBlockedItems(suggestionPreferencesManager.blockedItems());
        status.setTimeframe(suggestionPreferencesManager.getTimeframe());
        status.setRiskLevel(suggestionPreferencesManager.getRiskLevel());
        status.setReservedSlots(suggestionPreferencesManager.getEffectiveReservedSlots());
        status.setMinPredictedProfit(suggestionPreferencesManager.getMinPredictedProfit());
        status.setDumpMinPredictedProfit(suggestionPreferencesManager.getEffectiveDumpMinPredictedProfit());
        status.setBankAvailable(bankStateRS.get().isLoaded());
        status.setBankInventory(bankStateRS.get().getItems());
        status.setBagInventory(extractBagInventory());
        status.setSyncExcluded(computeSyncExcludedItems(status.getDisplayName(), u));
        status.setAllowedSync(client.getTickCount() > heldItemSyncStateRS.get().getDelayUntilTick());

        Map<Integer, Long> inLimboItems = geUncollected.getLastClearedUncollected();
        List<Integer> clearedSlots = geUncollected.getLastClearedSlots();
        if (geUncollected.getLastClearedTick() == client.getTickCount()) {
            log.debug("tick {} in limbo items {}, cleared slots {}", client.getTickCount(), inLimboItems, clearedSlots);
            if(inventory.missingJustCollected(inLimboItems)) {
                inLimboItems.forEach((itemId, qty) -> {
                    if (qty > 0) {
                        log.debug("tick {} move in limbo item {}, qty {} to inventory", client.getTickCount(), itemId, qty);
                        inventory.mergeItem(new RSItem(itemId, qty));
                    }
                });
            }
            for (Integer slot : clearedSlots) {
                Offer o = offerList.get(slot);
                GrandExchangeOffer geOffer = geOffers[slot];
                if (!isActive(geOffer.getState()) && geOffer.getState() != GrandExchangeOfferState.EMPTY) {
                    log.debug("tick {} in-activate slot {} just collected setting to EMPTY", client.getTickCount(), slot);
                    o.setStatus(OfferStatus.EMPTY);
                }
            }
        }

        return status;
    }

    private boolean isActive(GrandExchangeOfferState state) {
        switch (state){
            case EMPTY:
            case CANCELLED_BUY:
            case CANCELLED_SELL:
            case BOUGHT:
            case SOLD:
                return false;
            default:
                return true;
        }
    }

    private Map<Integer, Integer> extractBagInventory() {
        Map<Integer, Integer> bagInventory = itemController.getRunliteInventory();
        return bagInventory == null ? new HashMap<>() : bagInventory;
    }

    private List<Integer> computeSyncExcludedItems(String displayName, Map<Integer, Long> uncollectedItems) {
        Set<Integer> excluded = new LinkedHashSet<>(portfolioController.getActiveGrandExchangeItemIdsForSync());

        if (uncollectedItems != null) {
            excluded.addAll(uncollectedItems.keySet());
        }

        if (displayName != null && !displayName.isEmpty()) {
            List<Transaction> unAckedTransactions = transactionManager.getUnAckedTransactions(displayName);
            if (unAckedTransactions != null) {
                for (Transaction transaction : unAckedTransactions) {
                    if (transaction != null && transaction.getItemId() > 0) {
                        excluded.add(transaction.getItemId());
                    }
                }
            }
        }
        return new java.util.ArrayList<>(excluded);
    }

    public void resetSkipSuggestion() {
        skipSuggestion = -1;
    }

    /**
     * Skip button: record the current suggestion's item and request a new pick.
     * Must not touch {@link Client} — the button runs on the Swing EDT, and Client
     * access from the wrong thread is swallowed by {@code buildButton}.
     */
    public synchronized boolean skipCurrentSuggestion() {
        Suggestion suggestion = suggestionManager.getSuggestion();
        if (suggestion == null) {
            return false;
        }

        // 0 is always in the past vs the live tick, so a GE slot being open does not
        // block the follow-up fetch for a full tick.
        suggestion.actionedTick = 0;
        int itemId = suggestion.getItemId();
        if (itemId <= 0) {
            itemId = suggestion.getId();
        }
        skipItem(itemId);
        skipOffer(itemId);
        skipSuggestion = itemId;
        if (itemId <= 0) {
            log.warn("Skip clicked but suggestion has no item id (type={})", suggestion.getType());
        } else {
            log.info("skipping suggestion item {}", itemId);
        }
        telemetry.logSuggestionDecision(null, suggestion, "skip", suggestion.getPickSource());
        suggestionManager.setSuggestionRefreshPending(true);
        suggestionManager.setSuggestionNeeded(true);
        return true;
    }

    /**
     * Session-skip an item so BUY/SELL will not re-pick it until the TTL expires.
     * Used by the Skip button and when we suggest ABORT, so aborting a dead offer
     * does not immediately list the same item again.
     */
    public synchronized void skipItem(int itemId) {
        if (itemId <= 0) {
            return;
        }
        skippedItemUntil.put(itemId, System.currentTimeMillis() + SKIP_TTL_MS);
    }

    /**
     * User Skip (not auto-abort): do not ABORT/MODIFY this item either — leave the
     * offer and suggest something else.
     */
    public synchronized void skipOffer(int itemId) {
        if (itemId <= 0) {
            return;
        }
        skipOfferUntil.put(itemId, System.currentTimeMillis() + SKIP_TTL_MS);
    }

    /**
     * We suggested BUY/SELL for this item — do not ABORT it on the next tick for
     * dead-margin. MODIFY is still allowed.
     */
    public synchronized void protectListing(int itemId) {
        if (itemId <= 0) {
            return;
        }
        protectAbortUntil.put(itemId, System.currentTimeMillis() + PROTECT_ABORT_TTL_MS);
    }

    public synchronized Set<Integer> getSkippedItemIds() {
        pruneExpiredSkips();
        return new HashSet<>(skippedItemUntil.keySet());
    }

    public synchronized Set<Integer> getSkipOfferItemIds() {
        pruneExpiredSkips();
        return new HashSet<>(skipOfferUntil.keySet());
    }

    public synchronized Set<Integer> getProtectAbortItemIds() {
        pruneExpiredSkips();
        return new HashSet<>(protectAbortUntil.keySet());
    }

    private void pruneExpiredSkips() {
        long now = System.currentTimeMillis();
        skippedItemUntil.entrySet().removeIf(e -> e.getValue() <= now);
        skipOfferUntil.entrySet().removeIf(e -> e.getValue() <= now);
        protectAbortUntil.entrySet().removeIf(e -> e.getValue() <= now);
    }

    public synchronized void reset() {
        skipSuggestion = -1;
        skippedItemUntil.clear();
        skipOfferUntil.clear();
        protectAbortUntil.clear();
    }
}
