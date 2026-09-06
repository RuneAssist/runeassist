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
    /** GE modify cancels the slot first; hold that listing until confirm/skip/collect/close. */
    private OwnedModify ownedModify;

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

    /** Skip button: record item and refresh. EDT-safe — must not touch {@link Client}. */
    public synchronized boolean skipCurrentSuggestion() {
        Suggestion suggestion = suggestionManager.getSuggestion();
        if (suggestion == null) {
            return false;
        }
        suggestion.actionedTick = 0; // past vs live tick so GE slot open does not block fetch
        int itemId = suggestion.getItemId();
        if (itemId <= 0) {
            itemId = suggestion.getId();
        }
        skipItem(itemId);
        skipOffer(itemId);
        clearOwnedModify();
        skipSuggestion = itemId;
        if (itemId <= 0) {
            log.warn("Skip clicked but suggestion has no item id (type={})", suggestion.getType());
        } else {
            log.info("skipping suggestion item {}", itemId);
        }
        suggestionManager.setSuggestionRefreshPending(true);
        suggestionManager.setSuggestionNeeded(true);
        return true;
    }

    /** Session-skip item until TTL (Skip button / ABORT follow-up). */
    public synchronized void skipItem(int itemId) {
        if (itemId <= 0) {
            return;
        }
        skippedItemUntil.put(itemId, System.currentTimeMillis() + SKIP_TTL_MS);
    }

    /** User Skip: also suppress ABORT/MODIFY for this item. */
    public synchronized void skipOffer(int itemId) {
        if (itemId <= 0) {
            return;
        }
        skipOfferUntil.put(itemId, System.currentTimeMillis() + SKIP_TTL_MS);
    }

    /** Own a MODIFY listing until {@link #clearOwnedModify()}. */
    public synchronized void beginOwnedModify(Suggestion s) {
        beginOwnedModify(s, -1);
    }

    /** @param slotHint clicked/open GE slot (boxId can be stale after cancel-first modify). */
    public synchronized void beginOwnedModify(Suggestion s, int slotHint) {
        if (s == null || !s.isModifySuggestion() || s.getItemId() <= 0) {
            return;
        }
        if (ownedModify != null && ownedModify.itemId == s.getItemId()) {
            if (slotHint >= 0) {
                ownedModify.slot = slotHint;
            }
            return;
        }
        OwnedModify owned = new OwnedModify();
        owned.slot = slotHint >= 0 ? slotHint : s.getBoxId();
        owned.itemId = s.getItemId();
        owned.buy = s.getType() == SuggestionType.MODIFY_BUY;
        owned.targetPrice = s.getPrice();
        owned.quantity = s.getQuantity();
        owned.name = s.getName() == null ? "" : s.getName();
        try {
            GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
            if (offers != null) {
                int matched = -1;
                if (owned.slot >= 0 && owned.slot < offers.length) {
                    GrandExchangeOffer o = offers[owned.slot];
                    if (o != null && o.getItemId() == owned.itemId) {
                        matched = owned.slot;
                    }
                }
                if (matched < 0) {
                    for (int i = 0; i < offers.length; i++) {
                        GrandExchangeOffer o = offers[i];
                        if (o != null && o.getItemId() == owned.itemId) {
                            matched = i;
                            break;
                        }
                    }
                }
                if (matched >= 0) {
                    GrandExchangeOffer o = offers[matched];
                    owned.slot = matched;
                    owned.offerPrice = o.getPrice();
                    int remaining = Math.max(0, o.getTotalQuantity() - o.getQuantitySold());
                    if (remaining > 0) {
                        owned.quantity = remaining;
                    }
                    GrandExchangeOfferState st = o.getState();
                    if (st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.BOUGHT
                            || st == GrandExchangeOfferState.CANCELLED_BUY) {
                        owned.buy = true;
                    } else if (st == GrandExchangeOfferState.SELLING || st == GrandExchangeOfferState.SOLD
                            || st == GrandExchangeOfferState.CANCELLED_SELL) {
                        owned.buy = false;
                    }
                }
            }
        } catch (RuntimeException e) {
            log.debug("beginOwnedModify could not read live offers", e);
        }
        ownedModify = owned;
        protectListing(owned.itemId);
        log.debug("owned modify item {} slot {}", owned.itemId, owned.slot);
    }

    public synchronized void clearOwnedModify() {
        ownedModify = null;
    }

    /** Drop a MODIFY lock that is no longer being acted on. */
    public synchronized boolean releaseStaleOwnedModify(GrandExchangeOffer[] offers, boolean editorOpen) {
        if (ownedModify == null || ownedModify.itemId <= 0) {
            return false;
        }
        if (!editorOpen) {
            log.info("releasing stale owned modify item {} slot {} (editor closed)",
                    ownedModify.itemId, ownedModify.slot);
            ownedModify = null;
            return true;
        }
        if (offers == null) {
            return false;
        }
        int slot = ownedModify.slot;
        int itemId = ownedModify.itemId;
        if (slot >= 0 && slot < offers.length) {
            GrandExchangeOffer o = offers[slot];
            if (o != null && o.getItemId() > 0 && o.getItemId() != itemId
                    && isFillingState(o.getState())) {
                log.info("releasing owned modify item {} — slot {} is now item {}",
                        itemId, slot, o.getItemId());
                ownedModify = null;
                return true;
            }
        }
        return false;
    }

    /** Release MODIFY lock when slot emptied and editor closed. */
    public synchronized boolean releaseOwnedModifyIfSlotEmpty(int slot, boolean editorOpen) {
        if (ownedModify == null || ownedModify.itemId <= 0) {
            return false;
        }
        if (editorOpen || ownedModify.slot != slot) {
            return false;
        }
        log.info("releasing owned modify item {} — slot {} empty, editor closed",
                ownedModify.itemId, slot);
        ownedModify = null;
        return true;
    }

    private static boolean isFillingState(GrandExchangeOfferState st) {
        return st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.SELLING;
    }

    public synchronized boolean isOwnedModifyActive() {
        return ownedModify != null && ownedModify.itemId > 0;
    }

    public synchronized OwnedModify getOwnedModify() {
        return ownedModify;
    }

    /** Protect a just-suggested listing from immediate ABORT (~10 min). */
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
        ownedModify = null;
    }

    public static final class OwnedModify {
        public int slot = -1;
        public int itemId;
        public boolean buy;
        public long targetPrice;
        public int quantity;
        public String name = "";
        public long offerPrice;
    }
}
