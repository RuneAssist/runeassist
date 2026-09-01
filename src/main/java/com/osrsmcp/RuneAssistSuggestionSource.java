package com.osrsmcp;

import com.runeassist.flip.model.Suggestion;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.callback.ClientThread;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * RuneAssist's replacement for Flipping Copilot's server as the SOURCE of a flip
 * {@link Suggestion}. Reads the live GE offers + held positions on the client thread, runs
 * our market flip scorer off-thread, feeds both to {@link LocalSuggestionEngine} to pick the
 * next action, and delivers the resulting FC {@link Suggestion} back on the client thread.
 * Everything downstream (FC's highlight controller, offer editor, keybinds, panels) is
 * unchanged — it just consumes the Suggestion we produce instead of one from FC's backend.
 */
@Singleton
public class RuneAssistSuggestionSource
{
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private PlayerDataService playerDataService;
    @Inject private FlipTrackerService flipTracker;
    @Inject private OsrsMcpConfig config;

    /**
     * Compute the next suggestion and hand it to {@code consumer} on the client thread.
     * Call from the client thread (SuggestionController does).
     */
    public void getSuggestionAsync(Consumer<Suggestion> consumer)
    {
        // Snapshot client + tracker state now (client thread), then score off-thread.
        final long[][] offersBySlot = readOffers();
        final Map<Integer, long[]> held = readHeld();
        final long coins = Math.max(0, playerDataService.cachedCoins());
        final int maxSlots = config != null ? Math.max(1, Math.min(8, config.geSlots())) : 8;

        new Thread(() ->
        {
            List<Map<String, Object>> scored;
            try
            {
                Map<String, Object> r = playerDataService.buildFlipSuggestions(coins > 0 ? coins : 1_000_000L, 0, 0, 12);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> s = r != null ? (List<Map<String, Object>>) r.get("suggestions") : null;
                // Prepend held-item sell suggestions so LocalSuggestionEngine can offer SELLs.
                List<Map<String, Object>> sells = playerDataService.buildHeldSellSuggestions(heldToPositions(held));
                java.util.List<Map<String, Object>> combined = new java.util.ArrayList<>();
                if (sells != null) combined.addAll(sells);
                if (s != null) combined.addAll(s);
                scored = combined;
            }
            catch (Exception e) { scored = java.util.Collections.emptyList(); }

            final List<Map<String, Object>> fscored = scored;
            Suggestion suggestion;
            try { suggestion = LocalSuggestionEngine.next(fscored, offersBySlot, held, coins, maxSlots); }
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

    /** itemId -> {qty, avgBuy} from the flip tracker's open positions (client-free snapshot). */
    private Map<Integer, long[]> readHeld()
    {
        Map<Integer, long[]> held = new HashMap<>();
        try
        {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> open = (List<Map<String, Object>>) flipTracker.snapshot().get("open_positions");
            if (open != null) for (Map<String, Object> p : open)
            {
                int id = num(p.get("item_id"));
                long qty = numL(p.get("qty")), avg = numL(p.get("avg_buy"));
                if (id > 0 && qty > 0) held.put(id, new long[]{ qty, avg });
            }
        }
        catch (Exception ignored) {}
        return held;
    }

    /** Rebuild the open_positions list shape buildHeldSellSuggestions expects, from the held map. */
    private List<Map<String, Object>> heldToPositions(Map<Integer, long[]> held)
    {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map.Entry<Integer, long[]> e : held.entrySet())
        {
            Map<String, Object> p = new HashMap<>();
            p.put("item_id", e.getKey());
            p.put("qty", e.getValue()[0]);
            p.put("avg_buy", e.getValue()[1]);
            out.add(p);
        }
        return out;
    }

    private static int num(Object o) { return o instanceof Number ? ((Number) o).intValue() : 0; }
    private static long numL(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0; }
}
