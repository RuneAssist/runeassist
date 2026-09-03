package com.runeassist.flip;

import com.runeassist.flip.model.AccountStatusManager;
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
import java.util.function.Consumer;

/**
 * Self-contained source of the next flip {@link Suggestion} for the RuneAssist flipping
 * plugin — the local replacement for Flipping Copilot's server. Account state stays local;
 * market picking comes from Ares via {@link FlipScorer} (local wiki fallback if Ares is down).
 * Reads GE offers + coins on the client thread, scores off-thread, picks the
 * action with {@link LocalSuggestionEngine}, and delivers the Suggestion back on the client
 * thread. No FC account.
 */
@Slf4j
@Singleton
public class RuneAssistSuggestionSource
{
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private FlipScorer flipScorer;
    @Inject private HeldCostTracker heldCostTracker;
    @Inject private SuggestionPreferencesManager preferences;
    @Inject private AccountStatusManager accountStatusManager;
    @Inject private OsrsLoginManager osrsLoginManager;
    @Inject private PluginManager pluginManager;
    @Inject private com.runeassist.flip.model.SuggestionManager suggestionManager;
    @Inject private com.runeassist.flip.controller.GrandExchange grandExchange;
    @Inject private ExperimentService experimentService;

    // Decant-detection state (singleton, so this persists across suggestion cycles): the
    // opportunity we last watched, and owned qty of each leg at that time. A dose-conserving
    // qty shift between cycles for the SAME pair means a bank decant just happened, so
    // HeldCostTracker can carry the real cost basis over instead of estimating it fresh each
    // time a sell suggestion is built. See buildDecantCandidate/detectAndApplyDecant.
    private int trackedBuyItemId = -1, trackedSellItemId = -1;
    private long lastBuyQty = -1, lastSellQty = -1;

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

        if (HubFlippingCopilot.isEnabled(pluginManager))
        {
            Suggestion wait = LocalSuggestionEngine.waitFallback(
                HubFlippingCopilot.WAIT_MESSAGE, offersBySlot, maxSlots);
            wait.setWhy("");
            clientThread.invokeLater(() -> consumer.accept(wait));
            return;
        }

        new Thread(() ->
        {
            Suggestion suggestion = null;
            try
            {
            // Price-offset ladder experiments: hard-gated to specific RSNs (see ExperimentService's
            // doc comment), checked before any normal scoring so an active experiment always wins
            // the suggestion slot -- same priority pattern as the decant check below. quote() blocks
            // on HTTP, which is why this lives here and not in the client-thread prefix above.
            if (ExperimentService.isAllowed(displayName) && experimentService.hasActive(displayName))
            {
                ExperimentService.Experiment exp = experimentService.get(displayName);
                boolean hasOpenOfferForItem = hasOpenOfferFor(offersBySlot, exp.itemId, exp.buy);
                Suggestion expSuggestion = experimentService.buildSuggestion(displayName, flipScorer, hasOpenOfferForItem);
                if (expSuggestion != null)
                {
                    suggestion = expSuggestion;
                    clientThread.invokeLater(() -> consumer.accept(expSuggestion));
                    return;
                }
            }

            Map<Integer, Integer> usedLimit = usedBuyLimit(displayName, offersBySlot);
            Map<Integer, Integer> remainingHint = new HashMap<>();
            for (Map.Entry<Integer, Integer> e : usedLimit.entrySet())
            {
                int ge = flipScorer.geLimit(e.getKey());
                if (ge > 0) remainingHint.put(e.getKey(), Math.max(0, ge - e.getValue()));
            }

            List<Map<String, Object>> buys;
            try
            {
                buys = flipScorer.topFlips(coins > 0 ? coins : 0L, timeframe, risk,
                    !f2pOnly, remainingSlots, remainingHint, usedLimit, blocked, skipped,
                    minProfit);
            }
            catch (Exception e)
            {
                log.warn("topFlips failed; falling back to sell-only suggestions", e);
                buys = java.util.Collections.emptyList();
            }

            // Sell entries for held stock (side="sell"), so LocalSuggestionEngine can offer sells.
            List<Map<String, Object>> combined = new java.util.ArrayList<>(buildSells(held, skipped));
            if (buys != null) combined.addAll(buys);
            addMarketHealthForOffers(combined, offersBySlot, held, timeframe, risk, !f2pOnly);

            Map<Integer, Integer> remainingLimit = remainingLimits(displayName, combined, offersBySlot);

            LocalSuggestionEngine.Input in = new LocalSuggestionEngine.Input();
            in.scoredFlips = combined;
            in.offersBySlot = offersBySlot;
            in.held = held;
            in.coins = coins;
            in.maxSlots = maxSlots;
            in.skippedItemIds = skipped;
            in.blockedItemIds = blocked;
            in.skipOfferItemIds = skipOffers;
            in.protectAbortItemIds = new HashSet<>(accountStatusManager.getProtectAbortItemIds());
            applyOwnedModify(in, ownedModifySnap);
            in.remainingBuyLimit = remainingLimit;
            in.minPredictedProfit = minProfit;
            in.timeframeMinutes = timeframe;
            in.nowMs = System.currentTimeMillis();

            try { suggestion = LocalSuggestionEngine.next(in); }
            catch (Exception e) {
                log.warn("suggestion engine failed; falling back to Wait with slot status", e);
                suggestion = null;
            }
            if (suggestion != null && suggestion.isModifySuggestion()
                    && (skipOffers.contains(suggestion.getItemId())
                        || skipped.contains(suggestion.getItemId())
                        || blocked.contains(suggestion.getItemId())))
            {
                accountStatusManager.clearOwnedModify();
                in.ownedModifyItemId = 0;
                in.ownedModifySlot = -1;
                try { suggestion = LocalSuggestionEngine.next(in); }
                catch (Exception e) {
                    log.warn("suggestion engine retry after dropped MODIFY failed", e);
                    suggestion = null;
                }
            }
            try
            {
                Suggestion decant = buildDecantCandidate(displayName, offersBySlot, coins, remainingSlots, blocked, skipped, ownedQty);
                if (decant != null && preferDecant(suggestion, decant))
                {
                    suggestion = decant;
                }
            }
            catch (Exception e)
            {
                log.warn("decant candidate failed", e);
            }
            if (suggestion != null)
            {
                suggestion.setPickSource(flipScorer.lastFromAres() ? "ares" : "local");
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
                        waitMsg = LocalSuggestionEngine.WAIT_SLOTS_FULL;
                    } else if (flipScorer.lastAresUnreachable()) {
                        waitMsg = LocalSuggestionEngine.WAIT_ARES_DOWN;
                    } else {
                        waitMsg = LocalSuggestionEngine.WAIT_NO_CANDIDATES;
                    }
                    suggestion = LocalSuggestionEngine.waitFallback(waitMsg, offersBySlot, maxSlots);
                }
                if (suggestion.isWaitSuggestion()) {
                    if (suggestion.getMessage() == null || suggestion.getMessage().isEmpty()) {
                        suggestion.setMessage(LocalSuggestionEngine.WAIT_NO_MARGIN);
                    }
                    if (flipScorer.lastAresUnreachable()
                            && LocalSuggestionEngine.WAIT_NO_CANDIDATES.equals(suggestion.getMessage())) {
                        suggestion.setMessage(LocalSuggestionEngine.WAIT_ARES_DOWN);
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
                    suggestion.setPickSource(flipScorer.lastFromAres() ? "ares" : "local");
                }
                try { stampLimitFields(displayName, suggestion, offersBySlot); }
                catch (Exception e) { log.warn("limit stamp failed", e); }
                result = suggestion;
            } catch (Exception e) {
                log.warn("failed to finalize suggestion; sending Wait", e);
                result = LocalSuggestionEngine.waitFallback(
                    LocalSuggestionEngine.WAIT_NO_MARGIN, offersBySlot, maxSlots);
                result.setTimeIssued(Instant.now());
            }
            final Suggestion delivered = result;
            clientThread.invokeLater(() -> consumer.accept(delivered));
        }, "runeassist-suggestion").start();
    }

    /**
     * Stamp wiki GE limit + remaining 4h buy-limit onto a built suggestion for the card.
     * Remaining is live fills in HeldCostTracker only (GE history has no timestamps).
     * Unknown when wiki cap is missing, remaining is -1, or we have no live-fill data
     * unless pending buy offers already exhaust the wiki cap.
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
            try { ge = flipScorer.geLimit(itemId); }
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
        for (Map.Entry<Integer, long[]> e : merged.entrySet())
        {
            int id = e.getKey();
            long[] hv = e.getValue();
            if (hv == null || hv.length < 2 || hv[0] <= 0L) continue;
            int qty = hv[0] > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) hv[0];
            long avgBuy = hv[1];
            Map<String, Object> q;
            try { q = flipScorer.sellQuote(id); } catch (Exception ex) { q = null; }
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

    /**
     * Remaining 4h buy-limit per scored item, minus units still filling on buy offers.
     * Missing key = unknown (no live-fill tracker data) — do not treat as a full limit.
     * GE history is not used to reconstruct the window.
     */
    private Map<Integer, Integer> remainingLimits(String displayName, List<Map<String, Object>> scored, long[][] offers)
    {
        Map<Integer, Integer> pendingBuy = new HashMap<>();
        if (offers != null) for (long[] o : offers)
        {
            if (o == null || o.length < 6) continue;
            if (o[1] != 1L) continue; // not a buy
            int id = (int) o[0];
            int left = (int) Math.max(0L, o[4] - o[3]); // total - sold
            pendingBuy.merge(id, left, Integer::sum);
        }

        Map<Integer, Integer> out = new HashMap<>();
        for (Map<String, Object> flip : scored)
        {
            if (flip == null || "sell".equals(flip.get("side"))) continue;
            Object idObj = flip.get("id");
            Object limObj = flip.get("ge_limit");
            if (!(idObj instanceof Number) || !(limObj instanceof Number)) continue;
            int id = ((Number) idObj).intValue();
            int geLimit = ((Number) limObj).intValue();
            if (geLimit <= 0) geLimit = flipScorer.geLimit(id);
            int remaining = heldCostTracker.limitRemaining(displayName, id, geLimit);
            if (remaining < 0) continue; // unknown wiki limit
            int pending = pendingBuy.getOrDefault(id, 0);
            remaining = Math.max(0, remaining - pending);
            boolean tracked = heldCostTracker.hasLimitTrackerData(displayName, id);
            if (remaining == 0)
            {
                out.put(id, 0); // exhausted by live fills and/or pending buys
                continue;
            }
            if (!tracked) continue; // remaining unknown — don't cap as if the full limit is left
            out.put(id, remaining);
        }
        return out;
    }

    /** Local market health for filling offers and held stock so we can MODIFY/ABORT
     *  and skip listing items whose wiki margin is already dead. */
    private void addMarketHealthForOffers(List<Map<String, Object>> combined, long[][] offers,
                                          Map<Integer, long[]> held,
                                          int timeframe, RiskLevel risk, boolean membersItemsAllowed)
    {
        if (offers == null && (held == null || held.isEmpty())) return;
        Map<Integer, Map<String, Object>> marketById = new HashMap<>();
        for (Map<String, Object> flip : combined)
        {
            if (flip == null || "sell".equals(flip.get("side"))) continue;
            Object idObj = flip.get("id");
            if (idObj instanceof Number) marketById.putIfAbsent(((Number) idObj).intValue(), flip);
        }
        if (offers != null) for (long[] o : offers)
        {
            if (o == null || o.length < 6 || o[5] != 1L) continue;
            int id = (int) o[0];
            if (id <= 0) continue;
            mergeMarketHealth(combined, marketById, id, timeframe, risk, membersItemsAllowed);
        }
        if (held != null) for (Integer id : held.keySet())
        {
            if (id == null || id <= 0) continue;
            if (marketById.containsKey(id)) continue;
            mergeMarketHealth(combined, marketById, id, timeframe, risk, membersItemsAllowed);
        }
    }

    private void mergeMarketHealth(List<Map<String, Object>> combined,
                                   Map<Integer, Map<String, Object>> marketById, int id,
                                   int timeframe, RiskLevel risk, boolean membersItemsAllowed)
    {
        Map<String, Object> eval;
        try { eval = flipScorer.evaluateItem(id, timeframe, risk, membersItemsAllowed); }
        catch (Exception e) { eval = null; }
        if (eval == null) return;
        Map<String, Object> existing = marketById.get(id);
        if (existing != null)
        {
            if (Boolean.TRUE.equals(eval.get("dead")))
            {
                existing.put("dead", true);
                existing.put("dead_reason", eval.get("dead_reason"));
                existing.put("margin_post_tax", eval.get("margin_post_tax"));
                existing.put("flags", eval.get("flags"));
            }
            return;
        }
        combined.add(eval);
        marketById.put(id, eval);
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

    /** Client-thread-only snapshot of {@link #applyOwnedModify}'s inputs (see there). */
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

    /** Pure field copy onto {@code in} — no client-thread calls, safe from the background thread. */
    private static void applyOwnedModify(LocalSuggestionEngine.Input in, OwnedModifySnapshot snap)
    {
        if (snap.itemId <= 0)
        {
            return;
        }
        in.ownedModifySlot = snap.slot;
        in.ownedModifyItemId = snap.itemId;
        in.ownedModifyBuy = snap.buy;
        in.ownedModifyTargetPrice = snap.targetPrice;
        in.ownedModifyQuantity = snap.quantity;
        in.ownedModifyName = snap.name;
        in.ownedModifyOfferPrice = snap.offerPrice;
    }

    private boolean openSlotIsOwnedModify(int itemId, int modifySlot)
    {
        int open = grandExchange.getOpenSlot();
        if (open < 0)
        {
            return false;
        }
        if (open == modifySlot)
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

    /** Turn held stock into sell suggestions (side="sell"), best profit first. */
    private List<Map<String, Object>> buildSells(Map<Integer, long[]> held, Set<Integer> skipped)
    {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map.Entry<Integer, long[]> e : held.entrySet())
        {
            int id = e.getKey();
            if (skipped != null && skipped.contains(id)) continue;
            long qty = e.getValue()[0], avgBuy = e.getValue()[1];
            Map<String, Object> q;
            try { q = flipScorer.sellQuote(id); } catch (Exception ex) { q = null; }
            if (q == null) continue;
            Object sellObj = q.get("sell_at");
            Object taxObj = q.get("tax_at_sell");
            if (!(sellObj instanceof Number) || !(taxObj instanceof Number)) continue;
            long sell = ((Number) sellObj).longValue();
            long tax = ((Number) taxObj).longValue();
            long marginEa = sell - tax - avgBuy;   // may be negative (underwater / cut loss)
            if (marginEa <= 0) continue; // never list held stock that fails post-tax vs cost basis
            Map<String, Object> s = new java.util.LinkedHashMap<>();
            s.put("id", id);
            s.put("name", q.get("name"));
            s.put("side", "sell");
            s.put("loss", marginEa < 0);
            s.put("buy_at", avgBuy);
            s.put("sell_at", sell);
            s.put("margin_post_tax", marginEa);
            s.put("margin_pct", avgBuy > 0 ? Math.round(marginEa * 1000.0 / avgBuy) / 10.0 : 0.0);
            s.put("suggested_qty", qty);
            s.put("ge_limit", 0);
            s.put("projected_profit", marginEa * qty);
            s.put("flags", new java.util.ArrayList<String>());
            s.put("score", marginEa * qty);
            out.add(s);
        }
        out.sort((a, b) -> Long.compare(((Number) b.get("score")).longValue(),
                                        ((Number) a.get("score")).longValue()));
        return out;
    }

    /**
     * The current best decant opportunity's suggestion, at whichever phase (buy the cheap
     * dose, decant it, or sell the result) the player is actually in right now — read from
     * live inventory/bank state via {@link DecantTracker}, not {@link HeldCostTracker}'s FIFO
     * ledger (which never observes a bank decant). Returns null if there's no viable
     * opportunity, or the relevant item is blocked/skipped/already on an active offer.
     */
    private Suggestion buildDecantCandidate(String displayName, long[][] offersBySlot, long coins,
                                            int remainingSlots, Set<Integer> blocked, Set<Integer> skipped,
                                            Map<Integer, Long> ownedQty)
    {
        List<Map<String, Object>> decants;
        try { decants = flipScorer.topDecants(1); }
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

        detectAndApplyDecant(displayName, buyItemId, sellItemId, buyDose, sellDose, ownedQty);

        DecantTracker.Phase phase = DecantTracker.phaseFor(ownedQty, buyItemId, buyQtyTarget, sellItemId);

        if (phase == DecantTracker.Phase.NEED_SELL)
        {
            if (hasActiveOffer(offersBySlot, sellItemId)) return null;
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
            s.setMessage("Close the GE, right-click Bob Barter (SW corner) -> Decant -> " + family
                    + " -> " + sellDose + " doses. Converts " + buyQtyTarget + "x " + buyName + " into "
                    + sellQtyAfterDecant + "x " + sellName + ", then sell for ~+" + projectedProfit + " gp");
            return s;
        }

        // NEED_BUY
        if (hasActiveOffer(offersBySlot, buyItemId)) return null;
        if (remainingSlots <= 0 || buyAt <= 0 || coins < buyAt) return null;
        int geLimit = flipScorer.geLimit(buyItemId);
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
     * Notice when a bank decant has actually happened, between this suggestion cycle and the
     * last, for whichever opportunity we were watching — and if so, carry the real FIFO cost
     * basis over in {@link HeldCostTracker}. A decant never touches the Grand Exchange, so
     * there's no offer-fill event for it; this is the only place that observes it, by diffing
     * live owned qty of the same two items across cycles.
     *
     * <p>Detection is scoped to one specific (buyItemId, sellItemId) pair at a time — whichever
     * opportunity {@link FlipScorer#topDecants} currently ranks first — rather than scanning
     * every potion family every cycle. If the top opportunity changes, tracking just resets to
     * the new pair with no false trigger (a qty delta across two unrelated items pairs is
     * meaningless). The dose-conservation check (consumed buy-doses exactly match produced
     * sell-doses) is what distinguishes an actual decant from, say, an unrelated GE purchase of
     * the sell-dose item landing in the same tick.</p>
     */
    private void detectAndApplyDecant(String displayName, int buyItemId, int sellItemId,
                                      long buyDose, long sellDose, Map<Integer, Long> ownedQty)
    {
        long buyQty = DecantTracker.ownedQty(ownedQty, buyItemId);
        long sellQty = DecantTracker.ownedQty(ownedQty, sellItemId);

        boolean samePair = trackedBuyItemId == buyItemId && trackedSellItemId == sellItemId;
        if (samePair && lastBuyQty >= 0 && lastSellQty >= 0 && buyDose > 0 && sellDose > 0)
        {
            long buyDelta = lastBuyQty - buyQty;   // bottles of buyItemId consumed
            long sellDelta = sellQty - lastSellQty; // bottles of sellItemId produced
            // Dose conservation: total doses consumed must exactly equal total doses
            // produced (buyDelta bottles x buyDose doses each == sellDelta bottles x
            // sellDose doses each). Exact-match only, deliberately -- a decant that leaves
            // an uneven remainder (not a whole number of target bottles) won't be detected
            // here rather than risk a false positive from an unrelated qty change.
            if (buyDelta > 0 && sellDelta > 0 && buyDelta * buyDose == sellDelta * sellDose)
            {
                try { heldCostTracker.applyDecant(displayName, buyItemId, (int) buyDelta, sellItemId, (int) sellDelta); }
                catch (Exception e) { log.warn("applyDecant failed", e); }
            }
        }

        trackedBuyItemId = buyItemId;
        trackedSellItemId = sellItemId;
        lastBuyQty = buyQty;
        lastSellQty = sellQty;
    }

    /**
     * Whether the decant candidate should take the single suggestion slot over what
     * {@link LocalSuggestionEngine} picked. Never preempts an ABORT/MODIFY on a live offer —
     * those are urgent/necessary. A ready-to-decant reminder always wins over WAIT/BUY/SELL
     * (it's a quick, low-friction action); a buy/sell leg only wins if it's genuinely more
     * profitable than the engine's own pick.
     */
    private static boolean preferDecant(Suggestion normal, Suggestion decant)
    {
        if (normal == null) return true;
        if (normal.isAbortSuggestion() || normal.isModifySuggestion()) return false;
        if (decant.getType() == SuggestionType.DECANT) return true;
        if (normal.isWaitSuggestion()) return true;
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
     * Inventory + bank item counts, itemId -> qty. Must be read on the client thread (like
     * {@link #inventoryCoins()}/{@link #readOffers()} above) — {@code client.getItemContainer}
     * asserts client-thread-only, so this cannot be called from the background scoring thread.
     * Feeds {@link DecantTracker}, which has no direct {@link Client} access for that reason.
     */
    private Map<Integer, Long> snapshotOwnedQty()
    {
        Map<Integer, Long> out = new HashMap<>();
        addContainerQty(out, client.getItemContainer(InventoryID.INVENTORY));
        addContainerQty(out, client.getItemContainer(InventoryID.BANK));
        return out;
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
