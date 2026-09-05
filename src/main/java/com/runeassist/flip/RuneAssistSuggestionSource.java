package com.runeassist.flip;

import com.runeassist.flip.model.AccountStatusManager;
import com.runeassist.flip.model.ComposeSuggestionRequest;
import com.runeassist.flip.model.ModifyStep;
import com.runeassist.flip.model.OsrsLoginManager;
import com.runeassist.flip.model.RiskLevel;
import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.model.SuggestionPreferencesManager;
import com.runeassist.flip.model.SuggestionType;
import com.runeassist.flip.util.ProfitCalculator;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.PluginManager;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Source of the next flip {@link Suggestion}. Composes via Ares {@code POST /v1/suggestion}
 * (server ranks + composes). Soft-fails to WAIT when compose is unreachable. Snapshots
 * offers + coins on the client thread; ranking stays proprietary on Ares. Held-decant
 * candidates can still override a compose BUY/SELL/WAIT when more actionable.
 */
@Slf4j
@Singleton
public class RuneAssistSuggestionSource
{
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private AresMarketClient market;
    @Inject private HeldCostTracker heldCostTracker;
    @Inject private SuggestionPreferencesManager preferences;
    @Inject private AccountStatusManager accountStatusManager;
    @Inject private OsrsLoginManager osrsLoginManager;
    @Inject private PluginManager pluginManager;
    @Inject private com.runeassist.flip.model.SuggestionManager suggestionManager;
    @Inject private com.runeassist.flip.controller.GrandExchange grandExchange;
    @Inject private ExecutorService executor;

    // The decant we last told the player to make, watched until it happens (singleton, so it
    // survives across suggestion cycles). A decant never touches the GE, so this diff is the only
    // thing that observes one; without it HeldCostTracker never learns the new bottles exist and
    // they are missing from the portfolio. Watching the SUGGESTED pair rather than whatever the
    // current cycle happens to rank first matters: once the stock is converted the old family
    // stops being a candidate, so a cycle-scoped tracker resets and misses the very shift it was
    // waiting for. See applyPendingDecant/watchDecant.
    private int watchBuyItemId = -1, watchSellItemId = -1;
    private long watchBuyDose = 0, watchSellDose = 0;
    private long watchBuyQty = -1, watchSellQty = -1;

    /** Compute the next suggestion and hand it to {@code consumer} on the client thread. */
    public void getSuggestionAsync(Consumer<Suggestion> consumer)
    {
        // HeldCostTracker is scoped per account (display name) -- a RuneLite profile is not
        // the same thing as an OSRS account, and this must not mix two accounts' cost basis.
        final net.runelite.api.Player localPlayer = client.getLocalPlayer();
        final String displayName = localPlayer != null ? localPlayer.getName() : null;
        final long[][] offersBySlot = readOffers(displayName);
        // Real held stock with cost basis (FIFO from actual GE buys), so sells are profit-aware.
        final Map<Integer, long[]> held = heldCostTracker.held(displayName);
        final long coins = inventoryCoins();
        // client.getItemContainer(...) asserts client-thread-only -- must snapshot here, not
        // from the background scoring thread (see DecantTracker).
        final Map<Integer, Long> ownedQty = snapshotOwnedQty();
        // Potion families being carried, for the decant instruction: Bob Barter converts every
        // potion in the inventory, so anything else carried is swept up with it.
        final Set<String> carriedPotions = snapshotInventoryPotionFamilies();
        // grandExchange.isSlotOpen()/getOpenSlot() hit client.getVarbitValue(...), which is
        // also client-thread-only -- must snapshot here for the same reason as ownedQty above.
        final OwnedModifySnapshot ownedModifySnap = computeOwnedModify();
        final boolean membersWorld = osrsLoginManager.isMembersWorld();
        final boolean accountMember = osrsLoginManager.isAccountMember();
        final boolean f2pOnly = preferences.isF2pOnlyMode() || !membersWorld;
        final int timeframe = Math.max(1, preferences.getTimeframe());
        final RiskLevel risk = preferences.getRiskLevel() != null
            ? preferences.getRiskLevel() : RiskLevel.MEDIUM;
        final int maxSlots = Math.max(1, (membersWorld || accountMember ? 8 : 3)
            - preferences.getEffectiveReservedSlots());
        final Set<Integer> skipped = new HashSet<>(accountStatusManager.getSkippedItemIds());
        final Set<Integer> skipOffers = new HashSet<>(accountStatusManager.getSkipOfferItemIds());
        final Set<Integer> blocked = new HashSet<>(preferences.blockedItems());
        final long minProfit = preferences.getMinPredictedProfit() != null
            ? preferences.getMinPredictedProfit()
            : SuggestionPreferencesManager.DEFAULT_MIN_PREDICTED_PROFIT;
        final int usedSlots = countUsed(offersBySlot);
        final int remainingSlots = Math.max(0, maxSlots - usedSlots);

        if (HubPluginConflict.isEnabled(pluginManager))
        {
            Suggestion wait = WaitSuggestions.waitFallback(
                HubPluginConflict.WAIT_MESSAGE, offersBySlot, maxSlots);
            wait.setWhy("");
            clientThread.invokeLater(() -> consumer.accept(wait));
            return;
        }

        executor.execute(() ->
        {
            Suggestion suggestion = null;
            try
            {
            // Before anything else: did the decant we last suggested actually happen? This has
            // to run regardless of what is suggested now -- see applyPendingDecant.
            applyPendingDecant(displayName, ownedQty);

            Map<Integer, Integer> usedLimit = usedBuyLimit(displayName, offersBySlot);
            Map<Integer, Integer> remainingHint = new HashMap<>();
            for (Map.Entry<Integer, Integer> e : usedLimit.entrySet())
            {
                int ge = market.geLimit(e.getKey());
                if (ge > 0) remainingHint.put(e.getKey(), Math.max(0, ge - e.getValue()));
            }
            Set<Integer> protectAbort = new HashSet<>(accountStatusManager.getProtectAbortItemIds());

            ComposeSuggestionRequest composeReq = buildComposeRequest(
                coins, timeframe, risk, f2pOnly, maxSlots, remainingSlots, minProfit,
                remainingHint, usedLimit, blocked, skipped, skipOffers, protectAbort,
                offersBySlot, held, ownedModifySnap);
            try
            {
                suggestion = market.composeSuggestion(composeReq);
            }
            catch (Exception e)
            {
                log.warn("composeSuggestion failed; soft-fail to WAIT", e);
                suggestion = null;
            }

            try
            {
                Suggestion decant = buildDecantCandidate(displayName, offersBySlot, coins, remainingSlots, blocked, skipped, ownedQty, carriedPotions);
                if (decant != null && preferDecant(suggestion, decant))
                {
                    suggestion = decant;
                }
            }
            catch (Exception e)
            {
                log.warn("decant candidate failed", e);
            }
            if (suggestion != null
                    && (suggestion.getPickSource() == null || suggestion.getPickSource().isEmpty()))
            {
                suggestion.setPickSource(market.lastFromCompose()
                    ? "ares-compose"
                    : (market.lastFromAres() ? "ares" : "none"));
            }
            if (suggestion != null && suggestion.getItemId() > 0)
            {
                int id = suggestion.getItemId();
                SuggestionType t = suggestion.getType();
                if ((t == SuggestionType.BUY || t == SuggestionType.SELL) && skipped.contains(id))
                {
                    suggestion = null;
                }
                else if ((t == SuggestionType.ABORT || t == SuggestionType.MODIFY_BUY
                        || t == SuggestionType.MODIFY_SELL) && skipOffers.contains(id))
                {
                    suggestion = null;
                }
                else if (t == SuggestionType.BUY || t == SuggestionType.SELL
                        || t == SuggestionType.MODIFY_BUY || t == SuggestionType.MODIFY_SELL)
                {
                    // Own this listing / reprice for ~10 min so leftover qty is not aborted.
                    accountStatusManager.protectListing(id);
                }
            }
            // Aborting an item (buy or sell) session-skips it so the next pick is not
            // immediately BUY/SELL the same item (ruby necklace abort/list loop).
            if (suggestion != null && suggestion.isAbortSuggestion())
            {
                int abortId = suggestion.getItemId();
                if (abortId > 0) accountStatusManager.skipItem(abortId);
            }
            }
            catch (Exception e)
            {
                log.warn("suggestion source failed; falling back to Wait with slot status", e);
                suggestion = null;
            }
            Suggestion result;
            try {
                if (suggestion == null) {
                    boolean slotsFull = remainingSlots <= 0;
                    String waitMsg;
                    if (slotsFull) {
                        waitMsg = WaitSuggestions.WAIT_SLOTS_FULL;
                    } else if (market.lastComposeUnreachable() || market.lastAresUnreachable()) {
                        waitMsg = WaitSuggestions.WAIT_ARES_DOWN;
                    } else {
                        waitMsg = WaitSuggestions.WAIT_NO_CANDIDATES;
                    }
                    suggestion = WaitSuggestions.waitFallback(waitMsg, offersBySlot, maxSlots);
                }
                if (suggestion.isWaitSuggestion()) {
                    if (suggestion.getMessage() == null || suggestion.getMessage().isEmpty()) {
                        suggestion.setMessage(WaitSuggestions.WAIT_NO_MARGIN);
                    }
                    if ((market.lastComposeUnreachable() || market.lastAresUnreachable())
                            && WaitSuggestions.WAIT_NO_CANDIDATES.equals(suggestion.getMessage())) {
                        suggestion.setMessage(WaitSuggestions.WAIT_ARES_DOWN);
                    }
                }
                try {
                    suggestion.setPortfolioItems(portfolioItems(held, offersBySlot));
                } catch (Exception e) {
                    log.warn("portfolio items failed", e);
                }
                suggestion.setTimeIssued(Instant.now());
                if (suggestion.getPickSource() == null || suggestion.getPickSource().isEmpty())
                {
                    suggestion.setPickSource(market.lastFromCompose()
                        ? "ares-compose"
                        : (market.lastFromAres() ? "ares" : "none"));
                }
                try { stampLimitFields(displayName, suggestion, offersBySlot); }
                catch (Exception e) { log.warn("limit stamp failed", e); }
                result = suggestion;
            } catch (Exception e) {
                log.warn("failed to finalize suggestion; sending Wait", e);
                result = WaitSuggestions.waitFallback(
                    WaitSuggestions.WAIT_NO_MARGIN, offersBySlot, maxSlots);
                result.setTimeIssued(Instant.now());
            }
            final Suggestion delivered = result;
            clientThread.invokeLater(() -> consumer.accept(delivered));
        });
    }

    /**
     * Stamp GE limit + remaining 4h buy-limit onto a built suggestion for the card.
     * Remaining is live fills in HeldCostTracker only (GE history has no timestamps).
     * Unknown when the Ares limit map has no cap, remaining is -1, or we have no
     * live-fill data unless pending buy offers already exhaust the cap.
     */
    private void stampLimitFields(String displayName, Suggestion suggestion, long[][] offers)
    {
        if (suggestion == null) return;
        int itemId = suggestion.getItemId();
        if (itemId <= 0)
        {
            suggestion.setGeLimit(0);
            suggestion.setRemainingLimit(-1);
            suggestion.setLimitKnown(false);
            return;
        }
        int ge = suggestion.getGeLimit();
        if (ge <= 0)
        {
            try { ge = market.geLimit(itemId); }
            catch (Exception e) { ge = 0; }
        }
        int remaining = heldCostTracker.remainingLimitOrUnknown(displayName, itemId, ge);
        int pending = pendingBuyRemainder(itemId, offers);
        if (remaining >= 0)
        {
            remaining = Math.max(0, remaining - pending);
        }
        else if (ge > 0 && pending >= ge)
        {
            remaining = 0;
        }
        boolean known = ge > 0 && remaining >= 0;
        suggestion.setGeLimit(ge);
        suggestion.setRemainingLimit(known ? remaining : -1);
        suggestion.setLimitKnown(known);
    }

    private static int pendingBuyRemainder(int itemId, long[][] offers)
    {
        int pending = 0;
        if (offers == null) return 0;
        for (long[] o : offers)
        {
            if (o == null || o.length < 6) continue;
            if (o[1] != 1L) continue;
            if ((int) o[0] != itemId) continue;
            pending += (int) Math.max(0L, o[4] - o[3]);
        }
        return pending;
    }

    /** Held stock as FC portfolio items so unrealized profit / portfolio value update. */
    private List<Suggestion.PortfolioItem> portfolioItems(Map<Integer, long[]> held, long[][] offers)
    {
        Map<Integer, long[]> merged = mergeHeldWithOfferFills(held, offers);
        List<Suggestion.PortfolioItem> out = new ArrayList<>();
        if (merged.isEmpty()) return out;
        Map<Integer, Map<String, Object>> quotes;
        try { quotes = market.quotes(merged.keySet()); }
        catch (Exception ex) { quotes = java.util.Collections.emptyMap(); }
        for (Map.Entry<Integer, long[]> e : merged.entrySet())
        {
            int id = e.getKey();
            long[] hv = e.getValue();
            if (hv == null || hv.length < 2 || hv[0] <= 0L) continue;
            int qty = hv[0] > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) hv[0];
            long avgBuy = hv[1];
            Map<String, Object> q = quotes.get(id);
            long sell = q != null && q.get("sell_at") instanceof Number
                ? ((Number) q.get("sell_at")).longValue() : avgBuy;
            long tax = q != null && q.get("tax_at_sell") instanceof Number
                ? ((Number) q.get("tax_at_sell")).longValue()
                : ProfitCalculator.getTaxAmount(id, sell);
            long postTax = Math.max(0L, sell - tax);
            Suggestion.PortfolioItem item = new Suggestion.PortfolioItem();
            item.itemId = id;
            item.personalAmount = qty;
            item.personalBuySpend = avgBuy * qty;
            item.personalSellValue = postTax * qty;
            out.add(item);
        }
        return out;
    }

    /**
     * Floor held qty with units already filled on live buy offers. Relogs can persist slot
     * counters without lots, which otherwise leaves unrealized at 0 while portfolio cash
     * still counts locked GE offers.
     */
    private static Map<Integer, long[]> mergeHeldWithOfferFills(Map<Integer, long[]> held, long[][] offers)
    {
        Map<Integer, long[]> merged = new HashMap<>();
        if (held != null)
        {
            for (Map.Entry<Integer, long[]> e : held.entrySet())
            {
                if (e.getKey() != null && e.getValue() != null) merged.put(e.getKey(), e.getValue());
            }
        }
        if (offers == null) return merged;
        Map<Integer, long[]> fills = new HashMap<>(); // qty, cost
        for (long[] o : offers)
        {
            if (o == null || o.length < 6) continue;
            if (o[1] != 1L) continue; // not a buy
            int sold = (int) Math.max(0L, o[3]);
            if (sold <= 0) continue;
            int id = (int) o[0];
            if (id <= 0) continue;
            long unit = o[2] > 0L ? o[2] : 1L;
            fills.merge(id, new long[]{ sold, unit * sold },
                (a, b) -> new long[]{ a[0] + b[0], a[1] + b[1] });
        }
        for (Map.Entry<Integer, long[]> e : fills.entrySet())
        {
            long[] h = merged.get(e.getKey());
            long heldQty = h != null && h.length > 0 ? h[0] : 0L;
            long fillQty = e.getValue()[0];
            if (fillQty > heldQty && fillQty > 0L)
            {
                merged.put(e.getKey(), new long[]{ fillQty, e.getValue()[1] / fillQty });
            }
        }
        return merged;
    }

    /** Units already counting against the 4h GE buy-limit: fills in-window + pending buy remainder. */
    private Map<Integer, Integer> usedBuyLimit(String displayName, long[][] offers)
    {
        Map<Integer, Integer> used = new HashMap<>(heldCostTracker.boughtInWindowAll(displayName));
        if (offers != null) for (long[] o : offers)
        {
            if (o == null || o.length < 6) continue;
            if (o[1] != 1L) continue;
            int id = (int) o[0];
            int left = (int) Math.max(0L, o[4] - o[3]);
            if (left > 0) used.merge(id, left, Integer::sum);
        }
        return used;
    }

    /** Client-thread-only snapshot of owned-modify state for the compose request. */
    private static final class OwnedModifySnapshot
    {
        int slot = -1;
        int itemId = 0;
        boolean buy;
        long targetPrice;
        int quantity;
        String name = "";
        long offerPrice;
    }

    /**
     * Reads GE-slot state (client-thread-only: {@code grandExchange.isSlotOpen()}/
     * {@code getOpenSlot()} hit {@code client.getVarbitValue(...)}) to snapshot the
     * in-progress MODIFY, if any, so an empty cancelled slot cannot become a BUY of a
     * different item. Must be called from the client-thread-synchronous prefix of
     * {@link #getSuggestionAsync}, not the background scoring thread — see the
     * {@code ownedQty}/{@code DecantTracker} comment above for the same constraint.
     */
    private OwnedModifySnapshot computeOwnedModify()
    {
        OwnedModifySnapshot snap = new OwnedModifySnapshot();
        if (!grandExchange.isSlotOpen())
        {
            return snap;
        }
        AccountStatusManager.OwnedModify owned = accountStatusManager.getOwnedModify();
        if (owned != null && owned.itemId > 0)
        {
            if (!openSlotIsOwnedModify(owned.itemId, owned.slot))
            {
                return snap;
            }
            snap.slot = owned.slot;
            snap.itemId = owned.itemId;
            snap.buy = owned.buy;
            snap.targetPrice = owned.targetPrice;
            snap.quantity = owned.quantity;
            snap.name = owned.name != null ? owned.name : "";
            snap.offerPrice = owned.offerPrice;
            return snap;
        }
        Suggestion current = suggestionManager.getSuggestion();
        if (current != null && current.isModifySuggestion() && current.getItemId() > 0
                && current.actionedTick == -1
                && openSlotIsOwnedModify(current.getItemId(), current.getBoxId()))
        {
            snap.slot = current.getBoxId();
            snap.itemId = current.getItemId();
            snap.buy = current.getType() == SuggestionType.MODIFY_BUY;
            snap.targetPrice = current.getPrice();
            snap.quantity = current.getQuantity();
            snap.name = current.getName() != null ? current.getName() : "";
        }
        return snap;
    }

    private static ComposeSuggestionRequest buildComposeRequest(
            long coins, int timeframe, RiskLevel risk, boolean f2pOnly,
            int maxSlots, int remainingSlots, long minProfit,
            Map<Integer, Integer> remainingHint, Map<Integer, Integer> usedLimit,
            Set<Integer> blocked, Set<Integer> skipped, Set<Integer> skipOffers,
            Set<Integer> protectAbort, long[][] offersBySlot, Map<Integer, long[]> held,
            OwnedModifySnapshot ownedModifySnap)
    {
        ComposeSuggestionRequest req = new ComposeSuggestionRequest();
        req.setCapital(coins > 0 ? coins : 0L);
        req.setTimeframeMinutes(Math.max(1, timeframe));
        req.setRisk(risk != null ? risk.toApiValue() : "medium");
        req.setMembersItemsAllowed(!f2pOnly);
        req.setF2pOnly(f2pOnly);
        req.setMaxSlots(maxSlots);
        req.setRemainingSlots(Math.max(0, remainingSlots));
        req.setMinPredictedProfit(minProfit);
        req.setRemainingBuyLimit(stringifyKeys(remainingHint));
        req.setUsedBuyLimit(stringifyKeys(usedLimit));
        if (blocked != null) req.setBlockedIds(new ArrayList<>(blocked));
        if (skipped != null) req.setSkippedIds(new ArrayList<>(skipped));
        if (skipOffers != null) req.setSkipOfferItemIds(new ArrayList<>(skipOffers));
        if (protectAbort != null) req.setProtectAbortItemIds(new ArrayList<>(protectAbort));
        req.setOffers(toOfferSnapshots(offersBySlot));
        req.setHeld(toHeldSnapshots(held));
        if (ownedModifySnap != null && ownedModifySnap.itemId > 0)
        {
            ComposeSuggestionRequest.OwnedModifySnapshot om =
                new ComposeSuggestionRequest.OwnedModifySnapshot();
            om.setSlot(ownedModifySnap.slot);
            om.setItemId(ownedModifySnap.itemId);
            om.setBuy(ownedModifySnap.buy);
            om.setTargetPrice(ownedModifySnap.targetPrice);
            om.setQuantity(ownedModifySnap.quantity);
            om.setName(ownedModifySnap.name != null ? ownedModifySnap.name : "");
            om.setOfferPrice(ownedModifySnap.offerPrice);
            req.setOwnedModify(om);
        }
        req.setNowMs(System.currentTimeMillis());
        return req;
    }

    private static Map<String, Integer> stringifyKeys(Map<Integer, Integer> in)
    {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        if (in == null) return out;
        for (Map.Entry<Integer, Integer> e : in.entrySet())
        {
            if (e.getKey() == null || e.getValue() == null) continue;
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static List<ComposeSuggestionRequest.OfferSnapshot> toOfferSnapshots(long[][] offersBySlot)
    {
        List<ComposeSuggestionRequest.OfferSnapshot> out = new ArrayList<>();
        if (offersBySlot == null) return out;
        for (int slot = 0; slot < offersBySlot.length; slot++)
        {
            long[] o = offersBySlot[slot];
            if (o == null || o.length < 6) continue;
            int itemId = (int) o[0];
            if (itemId <= 0) continue;
            ComposeSuggestionRequest.OfferSnapshot snap = new ComposeSuggestionRequest.OfferSnapshot();
            snap.setSlot(slot);
            snap.setItemId(itemId);
            snap.setBuy(o[1] == 1L);
            snap.setPrice(o[2]);
            snap.setSold((int) Math.max(0L, o[3]));
            snap.setTotal((int) Math.max(0L, o[4]));
            snap.setFilling(o[5] == 1L);
            if (o.length > 6) snap.setLastProgressMs(o[6]);
            if (o.length > 7) snap.setListedMs(o[7]);
            out.add(snap);
        }
        return out;
    }

    private static List<ComposeSuggestionRequest.HeldSnapshot> toHeldSnapshots(Map<Integer, long[]> held)
    {
        List<ComposeSuggestionRequest.HeldSnapshot> out = new ArrayList<>();
        if (held == null) return out;
        for (Map.Entry<Integer, long[]> e : held.entrySet())
        {
            if (e.getKey() == null || e.getValue() == null || e.getValue().length < 1) continue;
            if (e.getValue()[0] <= 0L) continue;
            ComposeSuggestionRequest.HeldSnapshot h = new ComposeSuggestionRequest.HeldSnapshot();
            h.setItemId(e.getKey());
            h.setQty(e.getValue()[0]);
            h.setAvgBuy(e.getValue().length > 1 ? e.getValue()[1] : 0L);
            out.add(h);
        }
        return out;
    }

    private boolean openSlotIsOwnedModify(int itemId, int modifySlot)
    {
        int open = grandExchange.getOpenSlot();
        if (open < 0)
        {
            return false;
        }
        if (ModifyStep.editorMatches(open, grandExchange.getCurrentItemId(), itemId, modifySlot))
        {
            return true;
        }
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        return offers != null && open < offers.length && offers[open] != null
                && offers[open].getItemId() > 0
                && offers[open].getItemId() == itemId;
    }

    /** offersBySlot[i] = null (empty) or {itemId, buyIs1, price, sold, total, fillingIs1, lastProgressMs, listedMs}. */
    private long[][] readOffers(String displayName)
    {
        long[][] out = new long[8][];
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null) return out;
        for (int i = 0; i < offers.length && i < 8; i++)
        {
            GrandExchangeOffer o = offers[i];
            if (o == null) continue;
            GrandExchangeOfferState st = o.getState();
            if (st == null || st == GrandExchangeOfferState.EMPTY) continue;
            boolean buy = st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.BOUGHT
                || st == GrandExchangeOfferState.CANCELLED_BUY;
            boolean filling = st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.SELLING;
            long lastProgress = heldCostTracker.lastProgressMs(displayName, i, o.getItemId());
            long listed = heldCostTracker.listedMs(displayName, i, o.getItemId());
            out[i] = new long[]{ o.getItemId(), buy ? 1 : 0, o.getPrice(), o.getQuantitySold(),
                o.getTotalQuantity(), filling ? 1 : 0, lastProgress, listed };
        }
        return out;
    }

    private static int countUsed(long[][] offers)
    {
        int n = 0;
        if (offers != null) for (long[] o : offers) if (o != null) n++;
        return n;
    }

    /**
     * How to actually perform the decant, given what is being carried.
     *
     * <p>Bob Barter offers no per-item choice: he decants every potion in the inventory to the
     * dose picked. Carrying anything else converts that too, and silently as far as cost basis
     * goes, since only the suggested pair is watched for the shift. So when other families are
     * carried this leads with banking them, naming them, instead of describing a per-item
     * selection that does not exist.
     */
    static String decantInstruction(String family, Set<String> carriedPotions, long targetDose)
    {
        String tail = "Carry only the " + family + ", then right-click Bob Barter at the GE and"
                + " choose Decant -> " + targetDose + " dose.";
        if (carriedPotions == null || carriedPotions.isEmpty())
        {
            return tail;
        }
        List<String> others = new ArrayList<>();
        for (String carried : carriedPotions)
        {
            if (carried != null && !carried.equalsIgnoreCase(family))
            {
                others.add(carried);
            }
        }
        if (others.isEmpty())
        {
            return tail;
        }
        String named = others.size() <= 3
                ? String.join(", ", others)
                : String.join(", ", others.subList(0, 3)) + " and " + (others.size() - 3) + " more";
        return "Bank your " + named + " first — Bob Barter decants every potion you are carrying,"
                + " so those would be converted too. " + tail;
    }

    /**
     * A "go decant what you're already holding" suggestion, for the best held dose-variant
     * stock worth converting — independent of whether that family is currently a good thing to
     * buy into, which is the only thing {@link AresMarketClient#topDecants} considers. Returns null
     * when nothing held is worth converting.
     *
     * <p>No GE slot is needed to decant (it's a bank action), so unlike the buy/sell legs this
     * is worth surfacing even with every offer slot busy.</p>
     */
    private Suggestion buildHeldDecantCandidate(String displayName, Set<Integer> blocked, Set<Integer> skipped,
                                                Map<Integer, Long> ownedQty, Set<String> carriedPotions)
    {
        if (ownedQty == null || ownedQty.isEmpty()) return null;

        Map<String, Object> best = null;
        long bestGain = 0;
        for (Map.Entry<Integer, Long> e : ownedQty.entrySet())
        {
            int itemId = e.getKey();
            Long qty = e.getValue();
            if (qty == null || qty <= 0 || blocked.contains(itemId) || skipped.contains(itemId)) continue;
            Map<String, Object> row;
            try { row = market.decantForHeld(itemId, qty); }
            catch (Exception ex) { continue; }
            if (row == null) continue;
            int sellItemId = ((Number) row.get("sellItemId")).intValue();
            if (blocked.contains(sellItemId) || skipped.contains(sellItemId)) continue;
            long gain = ((Number) row.get("projectedProfit")).longValue();
            if (gain > bestGain)
            {
                bestGain = gain;
                best = row;
            }
        }
        if (best == null) return null;

        long heldQty = ((Number) best.get("buyQty")).longValue();
        long sellQty = ((Number) best.get("sellQty")).longValue();
        long sellDose = ((Number) best.get("sellDose")).longValue();
        long heldDose = ((Number) best.get("buyDose")).longValue();
        int heldItemId = ((Number) best.get("buyItemId")).intValue();
        int sellItemId = ((Number) best.get("sellItemId")).intValue();
        String family = String.valueOf(best.get("family"));
        String heldName = String.valueOf(best.get("buyName"));
        String sellName = String.valueOf(best.get("sellName"));

        // Watch the pair we're actually telling them to convert, not whatever topDecants
        // happens to rank first -- this is what carries the real FIFO cost basis across the
        // decant once they do it (a decant never touches the GE, so nothing else observes it).
        watchDecant(heldItemId, sellItemId, heldDose, sellDose, ownedQty);

        Suggestion s = new Suggestion();
        s.setType(SuggestionType.DECANT);
        s.setItemId(heldItemId);
        s.setId(heldItemId);
        s.setName(family);
        s.setQuantity((int) Math.min(Integer.MAX_VALUE, heldQty));
        s.setExpectedProfit((double) bestGain);
        s.setMessage(decantInstruction(family, carriedPotions, sellDose)
                + " Converts the " + heldQty + "x " + heldName + " you already hold into "
                + sellQty + "x " + sellName + " — worth ~" + bestGain
                + " gp more than selling them as they are.");
        return s;
    }

    private Suggestion buildDecantCandidate(String displayName, long[][] offersBySlot, long coins,
                                            int remainingSlots, Set<Integer> blocked, Set<Integer> skipped,
                                            Map<Integer, Long> ownedQty, Set<String> carriedPotions)
    {
        // Finishing something already bought beats starting something new, so held stock is
        // checked first. topDecants() only ranks opportunities worth *starting*, and a family
        // routinely falls out of that ranking the moment you buy into it -- your own buying
        // pressure thins the very margin that ranked it. Without this, stock bought on the
        // plugin's own advice is stranded: never decanted, and eventually dumped unconverted
        // by the normal sell path for less than it was worth. See AresMarketClient.decantForHeld.
        Suggestion heldDecant = buildHeldDecantCandidate(displayName, blocked, skipped, ownedQty, carriedPotions);
        if (heldDecant != null) return heldDecant;

        List<Map<String, Object>> decants;
        try { decants = market.topDecants(1); }
        catch (Exception e) { return null; }
        if (decants == null || decants.isEmpty()) return null;
        Map<String, Object> d = decants.get(0);

        int buyItemId = ((Number) d.get("buyItemId")).intValue();
        int sellItemId = ((Number) d.get("sellItemId")).intValue();
        if (blocked.contains(buyItemId) || blocked.contains(sellItemId)
                || skipped.contains(buyItemId) || skipped.contains(sellItemId))
        {
            return null;
        }

        long buyAt = ((Number) d.get("buyAt")).longValue();
        long sellAt = ((Number) d.get("sellAt")).longValue();
        long buyDose = ((Number) d.get("buyDose")).longValue();
        long sellDose = ((Number) d.get("sellDose")).longValue();
        long buyQtyTarget = ((Number) d.get("buyQty")).longValue();
        long projectedProfit = ((Number) d.get("projectedProfit")).longValue();
        String buyName = String.valueOf(d.get("buyName"));
        String sellName = String.valueOf(d.get("sellName"));
        String family = String.valueOf(d.get("family"));

        watchDecant(buyItemId, sellItemId, buyDose, sellDose, ownedQty);

        DecantTracker.Phase phase = DecantTracker.phaseFor(ownedQty, buyItemId, buyQtyTarget, sellItemId);

        if (phase == DecantTracker.Phase.NEED_SELL)
        {
            if (hasActiveOffer(offersBySlot, sellItemId)) return null;
            if (remainingSlots <= 0) return null; // can't list without a free GE slot
            long ownedSellQty = DecantTracker.ownedQty(ownedQty, sellItemId);
            if (ownedSellQty <= 0) return null;
            int qty = ownedSellQty > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ownedSellQty;
            // Prefer the real FIFO-tracked cost basis (carried over by detectAndApplyDecant,
            // above) over the estimate -- only fall back to the estimate when no real lot
            // exists yet (e.g. this suggestion cycle is the same one the decant happened in
            // and detection hasn't produced a lot to read back, or the lot only partially
            // covers ownedSellQty).
            long[] realLot = heldCostTracker.held(displayName).get(sellItemId);
            boolean tracked = realLot != null && realLot.length >= 2 && realLot[0] > 0;
            long costPerBottle = tracked
                    ? realLot[1]
                    : (buyDose > 0 ? Math.round((double) buyAt / buyDose * sellDose) : 0);
            long tax = ProfitCalculator.getTaxAmount(sellItemId, sellAt);
            long marginEa = sellAt - tax - costPerBottle;
            if (marginEa <= 0) return null; // decanted stock isn't worth selling right now
            Suggestion s = new Suggestion();
            s.setType(SuggestionType.SELL);
            s.setItemId(sellItemId);
            s.setId(sellItemId);
            s.setName(sellName);
            s.setPrice(sellAt);
            s.setQuantity(qty);
            s.setExpectedProfit((double) (marginEa * qty));
            s.setWhy("Decanted " + family + " — sell " + qty + " x " + sellName + " at " + sellAt + " gp"
                    + (tracked ? " (tracked cost basis)" : " (estimated cost basis, not yet tracked)"));
            return s;
        }

        if (phase == DecantTracker.Phase.NEED_DECANT)
        {
            long sellQtyAfterDecant = sellDose > 0 ? (buyQtyTarget * buyDose) / sellDose : 0;
            Suggestion s = new Suggestion();
            s.setType(SuggestionType.DECANT);
            s.setItemId(buyItemId);
            s.setId(buyItemId);
            s.setName(family);
            s.setQuantity((int) Math.min(Integer.MAX_VALUE, buyQtyTarget));
            s.setExpectedProfit((double) projectedProfit);
            s.setMessage(decantInstruction(family, carriedPotions, sellDose)
                    + " Converts " + buyQtyTarget + "x " + buyName + " into "
                    + sellQtyAfterDecant + "x " + sellName + ", then sell for ~+"
                    + projectedProfit + " gp.");
            return s;
        }

        // NEED_BUY
        if (hasActiveOffer(offersBySlot, buyItemId)) return null;
        if (remainingSlots <= 0 || buyAt <= 0 || coins < buyAt) return null;
        int geLimit = market.geLimit(buyItemId);
        int remainingLimit = geLimit > 0 ? heldCostTracker.remainingLimitOrUnknown(displayName, buyItemId, geLimit) : -1;
        long qty = Math.min(buyQtyTarget, coins / buyAt);
        if (remainingLimit >= 0) qty = Math.min(qty, remainingLimit);
        if (qty < 1) return null;
        Suggestion s = new Suggestion();
        s.setType(SuggestionType.BUY);
        s.setItemId(buyItemId);
        s.setId(buyItemId);
        s.setName(buyName);
        s.setPrice(buyAt);
        s.setQuantity((int) Math.min(Integer.MAX_VALUE, qty));
        s.setExpectedProfit((double) projectedProfit);
        s.setWhy("Buy toward decant: " + qty + " x " + buyName + " -> decant to " + sellName
                + ", sell for ~+" + projectedProfit + " gp");
        return s;
    }

    /**
     * Apply the decant we asked for, once the player has actually made it.
     *
     * <p>Run every cycle, whatever is being suggested now. A decant never touches the Grand
     * Exchange, so diffing owned quantities is the only way to observe one, and the window in
     * which it can be seen is exactly the cycle it happens on. Watching the pair we suggested,
     * rather than whatever this cycle ranks first, is the point: converting the stock removes
     * that family from the candidates, so a tracker scoped to the current suggestion resets
     * itself and misses the shift -- which left the new bottles with no cost basis and absent
     * from the portfolio.
     *
     * <p>Dose conservation is required exactly: consumed bottles x their dose must equal
     * produced bottles x theirs. A partial conversion is left alone rather than risk pairing an
     * unrelated quantity change with it.
     */
    private void applyPendingDecant(String displayName, Map<Integer, Long> ownedQty)
    {
        if (watchBuyItemId <= 0 || watchSellItemId <= 0) return;
        long buyQty = DecantTracker.ownedQty(ownedQty, watchBuyItemId);
        long sellQty = DecantTracker.ownedQty(ownedQty, watchSellItemId);
        if (watchBuyQty >= 0 && watchSellQty >= 0 && watchBuyDose > 0 && watchSellDose > 0)
        {
            long buyDelta = watchBuyQty - buyQty;    // bottles consumed
            long sellDelta = sellQty - watchSellQty; // bottles produced
            if (DecantTracker.isDoseConservingShift(buyDelta, watchBuyDose, sellDelta, watchSellDose))
            {
                try
                {
                    heldCostTracker.applyDecant(displayName, watchBuyItemId, (int) buyDelta,
                        watchSellItemId, (int) sellDelta);
                    log.debug("decant applied: {}x {} -> {}x {}", buyDelta, watchBuyItemId,
                        sellDelta, watchSellItemId);
                }
                catch (Exception e) { log.warn("applyDecant failed", e); }
                watchBuyItemId = -1;
                watchSellItemId = -1;
                return;
            }
        }
        // Not yet: re-baseline so the next cycle measures from here.
        watchBuyQty = buyQty;
        watchSellQty = sellQty;
    }

    /** Remember the decant just suggested, so the conversion is recognised when it happens. */
    private void watchDecant(int buyItemId, int sellItemId, long buyDose, long sellDose,
                             Map<Integer, Long> ownedQty)
    {
        watchBuyItemId = buyItemId;
        watchSellItemId = sellItemId;
        watchBuyDose = buyDose;
        watchSellDose = sellDose;
        watchBuyQty = DecantTracker.ownedQty(ownedQty, buyItemId);
        watchSellQty = DecantTracker.ownedQty(ownedQty, sellItemId);
    }

    /**
     * Whether the decant candidate should take the single suggestion slot over what compose
     * returned. Never preempts an ABORT/MODIFY on a live offer — those are urgent/necessary.
     * A ready-to-decant reminder always wins over WAIT/BUY/SELL (it's a quick, low-friction
     * action). Listing already-held target-dose stock wins over WAIT/BUY so we do not keep
     * buying more cheap doses while 4-doses sit in inventory (and a BUY-toward-decant never
     * replaces a SELL of that held stock). Otherwise a buy/sell leg only wins if it is
     * genuinely more profitable than the compose pick.
     */
    static boolean preferDecant(Suggestion normal, Suggestion decant)
    {
        if (decant == null) return false;
        if (normal == null) return true;
        if (normal.isAbortSuggestion() || normal.isModifySuggestion()) return false;
        if (decant.getType() == SuggestionType.DECANT) return true;
        if (normal.isWaitSuggestion()) return true;
        // Already holding listable target-dose stock: sell it before buying more cheap doses.
        if (decant.getType() == SuggestionType.SELL && normal.getType() == SuggestionType.BUY)
        {
            return true;
        }
        // Don't replace a SELL of held stock with "buy more 3s to decant", even when the
        // full-batch buy-to-decant projects more profit than listing the bottles already held.
        if (decant.getType() == SuggestionType.BUY && normal.getType() == SuggestionType.SELL)
        {
            return false;
        }
        Double normalProfit = normal.getExpectedProfit();
        Double decantProfit = decant.getExpectedProfit();
        if (decantProfit == null) return false;
        if (normalProfit == null) return true;
        return decantProfit > normalProfit;
    }

    /** Filling offers with remaining qty only — finished BOUGHT/SOLD boxes do not block SELL. */
    private static boolean hasActiveOffer(long[][] offersBySlot, int itemId)
    {
        return hasOpenOfferFor(offersBySlot, itemId, true)
                || hasOpenOfferFor(offersBySlot, itemId, false);
    }

    private static boolean hasOpenOfferFor(long[][] offersBySlot, int itemId, boolean buy)
    {
        if (offersBySlot == null) return false;
        for (long[] o : offersBySlot)
        {
            if (o == null || o.length < 6 || (int) o[0] != itemId) continue;
            if ((o[1] == 1L) != buy) continue;
            boolean filling = o[5] == 1L;
            int remaining = (int) Math.max(0L, o[4] - o[3]);
            if (filling && remaining > 0) return true;
        }
        return false;
    }

    private long inventoryCoins()
    {
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv == null) return 0;
        for (Item item : inv.getItems())
            if (item.getId() == 995) return item.getQuantity();
        return 0;
    }

    /**
     * Inventory + bank item counts, unnoted itemId -> qty. Must be read on the client thread
     * (like {@link #inventoryCoins()}/{@link #readOffers()} above) —
     * {@code client.getItemContainer} asserts client-thread-only, so this cannot be called from
     * the background scoring thread. Feeds {@link DecantTracker}, which has no direct
     * {@link Client} access for that reason. Noted stacks are collapsed onto their unnoted
     * wiki/GE ids so holding Super defence(4) notes counts as holding Super defence(4).
     */
    private Map<Integer, Long> snapshotOwnedQty()
    {
        Map<Integer, Long> raw = new HashMap<>();
        addContainerQty(raw, client.getItemContainer(InventoryID.INVENTORY));
        addContainerQty(raw, client.getItemContainer(InventoryID.BANK));
        return DecantTracker.collapseToUnnoted(raw, this::toUnnotedItemId);
    }

    /**
     * Potion families carried in the inventory right now.
     *
     * <p>Bob Barter decants every potion in the inventory to the chosen dose, not just the one
     * being suggested, so anything else carried is converted too -- and silently, as far as
     * cost basis is concerned, since only the suggested pair is watched. This is what the
     * suggestion warns about. Client thread only, like the other container reads.
     */
    private Set<String> snapshotInventoryPotionFamilies()
    {
        Set<String> families = new java.util.LinkedHashSet<>();
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv == null) return families;
        for (Item item : inv.getItems())
        {
            if (item == null || item.getId() <= 0) continue;
            ItemComposition composition = client.getItemDefinition(item.getId());
            if (composition == null) continue;
            String family = DecantTracker.doseFamily(composition.getName());
            if (family != null) families.add(family);
        }
        return families;
    }

    private int toUnnotedItemId(int itemId)
    {
        ItemComposition composition = client.getItemDefinition(itemId);
        return DecantTracker.unnotedId(composition, itemId);
    }

    private static void addContainerQty(Map<Integer, Long> out, ItemContainer container)
    {
        if (container == null) return;
        for (Item item : container.getItems())
        {
            if (item == null || item.getId() <= 0) continue;
            out.merge(item.getId(), (long) item.getQuantity(), Long::sum);
        }
    }
}
