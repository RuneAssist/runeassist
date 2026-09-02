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

    /** Compute the next suggestion and hand it to {@code consumer} on the client thread. */
    public void getSuggestionAsync(Consumer<Suggestion> consumer)
    {
        final long[][] offersBySlot = readOffers();
        // Real held stock with cost basis (FIFO from actual GE buys), so sells are profit-aware.
        final Map<Integer, long[]> held = heldCostTracker.held();
        final long coins = inventoryCoins();
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
        final Long minProfit = preferences.getMinPredictedProfit();
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
            Map<Integer, Integer> usedLimit = usedBuyLimit(offersBySlot);
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
                    minProfit != null ? minProfit : 0L);
            }
            catch (Exception e) { buys = java.util.Collections.emptyList(); }

            // Sell entries for held stock (side="sell"), so LocalSuggestionEngine can offer sells.
            List<Map<String, Object>> combined = new java.util.ArrayList<>(buildSells(held, skipped));
            if (buys != null) combined.addAll(buys);
            addMarketHealthForOffers(combined, offersBySlot, held, timeframe, risk, !f2pOnly);

            Map<Integer, Integer> remainingLimit = remainingLimits(combined, offersBySlot);

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
            in.remainingBuyLimit = remainingLimit;
            in.minPredictedProfit = minProfit != null ? minProfit : 0L;

            try { suggestion = LocalSuggestionEngine.next(in); }
            catch (Exception e) {
                log.warn("suggestion engine failed; falling back to Wait with slot status", e);
                suggestion = null;
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
                else if (t == SuggestionType.BUY || t == SuggestionType.SELL)
                {
                    // Protect this listing from an immediate dead-margin ABORT next tick.
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
            if (suggestion == null) {
                boolean slotsFull = remainingSlots <= 0;
                suggestion = LocalSuggestionEngine.waitFallback(
                    slotsFull ? LocalSuggestionEngine.WAIT_SLOTS_FULL
                        : LocalSuggestionEngine.WAIT_NO_MARGIN,
                    offersBySlot, maxSlots);
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
            try { stampLimitFields(suggestion, offersBySlot); }
            catch (Exception e) { log.warn("limit stamp failed", e); }
            final Suggestion result = suggestion;
            clientThread.invokeLater(() -> consumer.accept(result));
        }, "runeassist-suggestion").start();
    }

    /**
     * Stamp wiki GE limit + remaining 4h buy-limit onto a built suggestion for the card.
     * Remaining is live fills in HeldCostTracker only (GE history has no timestamps).
     * Unknown when wiki cap is missing, remaining is -1, or we have no live-fill data
     * unless pending buy offers already exhaust the wiki cap.
     */
    private void stampLimitFields(Suggestion suggestion, long[][] offers)
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
        int remaining = heldCostTracker.remainingLimitOrUnknown(itemId, ge);
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
    private Map<Integer, Integer> remainingLimits(List<Map<String, Object>> scored, long[][] offers)
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
            int remaining = heldCostTracker.limitRemaining(id, geLimit);
            if (remaining < 0) continue; // unknown wiki limit
            int pending = pendingBuy.getOrDefault(id, 0);
            remaining = Math.max(0, remaining - pending);
            boolean tracked = heldCostTracker.hasLimitTrackerData(id);
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
    private Map<Integer, Integer> usedBuyLimit(long[][] offers)
    {
        Map<Integer, Integer> used = new HashMap<>(heldCostTracker.boughtInWindowAll());
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

    /** offersBySlot[i] = null (empty) or {itemId, buyIs1, price, sold, total, fillingIs1}. */
    private long[][] readOffers()
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
            out[i] = new long[]{ o.getItemId(), buy ? 1 : 0, o.getPrice(), o.getQuantitySold(),
                o.getTotalQuantity(), filling ? 1 : 0 };
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

    private long inventoryCoins()
    {
        ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
        if (inv == null) return 0;
        for (Item item : inv.getItems())
            if (item.getId() == 995) return item.getQuantity();
        return 0;
    }
}
