package com.runeassist.flip;

import com.runeassist.flip.model.AccountStatusManager;
import com.runeassist.flip.model.ComposeSuggestionRequest;
import com.runeassist.flip.model.ModifyStep;
import com.runeassist.flip.model.OsrsLoginManager;
import com.runeassist.flip.model.RiskLevel;
import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.model.SuggestionPreferencesManager;
import com.runeassist.flip.model.SuggestionType;
import com.runeassist.flip.controller.BugReportClient;
import com.runeassist.flip.util.ProfitCalculator;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
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

    /** Source of the next flip {@link Suggestion}. Composes via Ares {@code POST /v1/suggestion} */
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
    @Inject private ConfigManager configManager;
    @Inject private com.runeassist.flip.model.SuggestionManager suggestionManager;
    @Inject private com.runeassist.flip.controller.GrandExchange grandExchange;
    @Inject private ExecutorService executor;

    /** Compute the next suggestion and hand it to {@code consumer} on the client thread. */
    public void getSuggestionAsync(Consumer<Suggestion> consumer)
    {
        getSuggestionAsync(consumer, true);
    }

    /**
     * @param includeGraph when true, ask Ares to bundle {@code /v1/graph}-shaped data on
     *                     the compose response (skipped in low-data mode).
     */
    public void getSuggestionAsync(Consumer<Suggestion> consumer, boolean includeGraph)
    {
        // HeldCostTracker is scoped per account (display name) -- a RuneLite profile is not
        // the same thing as an OSRS account, and this must not mix two accounts' cost basis.
        final net.runelite.api.Player localPlayer = client.getLocalPlayer();
        final String displayName = localPlayer != null ? localPlayer.getName() : null;
        final long[][] offersBySlot = readOffers(displayName);
        // Real held stock with cost basis (FIFO from actual GE buys), so sells are profit-aware.
        final Map<Integer, long[]> held = heldCostTracker.held(displayName);
        final long coins = inventoryCoins();
        // grandExchange.isSlotOpen()/getOpenSlot() hit client.getVarbitValue(...), which is
        // client-thread-only — snapshot here before the background compose thread runs.
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
                offersBySlot, held, ownedModifySnap, includeGraph,
                clientDeviceId(), preferences.isTimeBasedAbortEnabled(),
                preferences.getTimeBasedAbortMinutes());
            try
            {
                suggestion = market.composeSuggestion(composeReq);
            }
            catch (Exception e)
            {
                log.warn("composeSuggestion failed; soft-fail to WAIT", e);
                suggestion = null;
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

    /** Stamp GE limit + remaining 4h buy-limit onto a built suggestion for the card. */
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

    /** Held stock as portfolio items so unrealized profit / portfolio value update. */
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

    /** Reads GE-slot state (client-thread-only: {@code grandExchange.isSlotOpen()}/ */
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
            OwnedModifySnapshot ownedModifySnap, boolean includeGraph,
            String clientDeviceId, boolean timeBasedAbortEnabled, int timeBasedAbortMinutes)
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
        req.setIncludeGraph(includeGraph);
        req.setClientDeviceId(clientDeviceId != null ? clientDeviceId : "");
        req.setTimeBasedAbortEnabled(timeBasedAbortEnabled);
        req.setTimeBasedAbortMinutes(timeBasedAbortMinutes > 0 ? timeBasedAbortMinutes : 15);
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

    /** Stable id for server top-K jitter: pairing device token, else account hash. */
    private String clientDeviceId()
    {
        String token = configManager.getConfiguration(
            BugReportClient.CONFIG_GROUP, BugReportClient.KEY_DEVICE_TOKEN);
        if (token != null && !token.isEmpty())
        {
            return token;
        }
        Long hash = osrsLoginManager.getAccountHash();
        return hash != null ? String.valueOf(hash) : "";
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

    private long inventoryCoins()
    {
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv == null) return 0;
        for (Item item : inv.getItems())
            if (item.getId() == 995) return item.getQuantity();
        return 0;
    }
}
