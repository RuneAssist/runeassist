package com.runeassist.flip;

import com.runeassist.flip.model.Suggestion;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.callback.ClientThread;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Self-contained source of the next flip {@link Suggestion} for the RuneAssist flipping
 * plugin — the local replacement for Flipping Copilot's server. It depends only on core
 * RuneLite (Client) and the fork's own {@link FlipScorer} (wiki-price based), so it lives
 * entirely inside this plugin's injector. Reads GE offers + coins on the client thread,
 * scores off-thread, picks the action with {@link LocalSuggestionEngine}, and delivers the
 * Suggestion back on the client thread. No FC account, no external suggestion server.
 */
@Singleton
public class RuneAssistSuggestionSource
{
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private FlipScorer flipScorer;
    @Inject private HeldCostTracker heldCostTracker;

    /** Compute the next suggestion and hand it to {@code consumer} on the client thread. */
    public void getSuggestionAsync(Consumer<Suggestion> consumer)
    {
        final long[][] offersBySlot = readOffers();
        // Real held stock with cost basis (FIFO from actual GE buys), so sells are profit-aware.
        final Map<Integer, long[]> held = heldCostTracker.held();
        final long coins = inventoryCoins();

        new Thread(() ->
        {
            List<Map<String, Object>> buys;
            try { buys = flipScorer.topFlips(coins > 0 ? coins : 1_000_000L); }
            catch (Exception e) { buys = java.util.Collections.emptyList(); }

            // Sell entries for held stock (side="sell"), so LocalSuggestionEngine can offer sells.
            List<Map<String, Object>> combined = new java.util.ArrayList<>(buildSells(held));
            if (buys != null) combined.addAll(buys);
            final List<Map<String, Object>> fscored = combined;

            Suggestion suggestion;
            try { suggestion = LocalSuggestionEngine.next(fscored, offersBySlot, held, coins, 8); }
            catch (Exception e) { suggestion = null; }
            final Suggestion result = suggestion;
            clientThread.invokeLater(() -> consumer.accept(result));
        }, "runeassist-suggestion").start();
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

    /** Turn held stock into sell suggestions (side="sell"), best profit first. */
    private List<Map<String, Object>> buildSells(Map<Integer, long[]> held)
    {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map.Entry<Integer, long[]> e : held.entrySet())
        {
            int id = e.getKey();
            long qty = e.getValue()[0], avgBuy = e.getValue()[1];
            Map<String, Object> q;
            try { q = flipScorer.sellQuote(id); } catch (Exception ex) { q = null; }
            if (q == null) continue;
            long sell = ((Number) q.get("sell_at")).longValue();
            long tax = ((Number) q.get("tax_at_sell")).longValue();
            long marginEa = sell - tax - avgBuy;   // may be negative (underwater / cut loss)
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
