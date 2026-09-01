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

    /** Compute the next suggestion and hand it to {@code consumer} on the client thread. */
    public void getSuggestionAsync(Consumer<Suggestion> consumer)
    {
        final long[][] offersBySlot = readOffers();
        final Map<Integer, long[]> held = readHeld(offersBySlot);
        final long coins = inventoryCoins();

        new Thread(() ->
        {
            List<Map<String, Object>> scored;
            try { scored = flipScorer.topFlips(coins > 0 ? coins : 1_000_000L); }
            catch (Exception e) { scored = java.util.Collections.emptyList(); }
            final List<Map<String, Object>> fscored = scored;

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

    /**
     * Items currently held as a completed buy awaiting sale: BOUGHT offers not yet collected,
     * keyed itemId -&gt; {qty, avgBuy}. (Inventory-held stock without a tracked cost basis is
     * omitted for now — sell suggestions with a real cost basis need FC's tracking, wired
     * next.) Read on the client thread.
     */
    private Map<Integer, long[]> readHeld(long[][] offersBySlot)
    {
        Map<Integer, long[]> held = new HashMap<>();
        for (long[] o : offersBySlot)
        {
            // A completed BUY (buy==1, sold==total, not filling) is stock ready to sell.
            if (o != null && o[1] == 1 && o[5] == 0 && o[3] > 0 && o[3] >= o[4])
            {
                int id = (int) o[0];
                held.merge(id, new long[]{ o[3], o[2] }, (a, b) -> new long[]{ a[0] + b[0], a[1] });
            }
        }
        return held;
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
