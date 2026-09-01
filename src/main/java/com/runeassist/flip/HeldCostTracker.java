package com.runeassist.flip;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight, self-contained FIFO cost-basis tracker for the RuneAssist flipping fork.
 * Watches GE offer changes, records the average buy price of stock you're accumulating, and
 * consumes those lots (FIFO) as you sell — so the fork can suggest selling held stock at a
 * real profit/loss. Persisted in RuneLite config (group {@code runeassistflip}) with per-slot
 * cumulative counters so a relog re-emitting existing offers doesn't double-count.
 *
 * <p>Fed from the fork plugin's {@code onGrandExchangeOfferChanged}. Read via {@link #held()}.
 */
@Slf4j
@Singleton
public class HeldCostTracker
{
    private static final String GROUP = "runeassistflip";
    private static final String KEY = "heldcost";

    @Inject private ConfigManager configManager;
    @Inject private Gson gson;

    private static final class Lot { int qty; long unit; Lot(int q, long u){ qty=q; unit=u; } }
    private static final class Slot { int itemId; boolean buy; int qty; long spent;
        Slot(int i, boolean b, int q, long s){ itemId=i; buy=b; qty=q; spent=s; } }

    private final Map<Integer, Deque<Lot>> positions = new LinkedHashMap<>();
    private final Map<Integer, Slot> slots = new HashMap<>();
    private boolean loaded = false;

    // ── ingest ──────────────────────────────────────────────────────────────────

    public synchronized void onOffer(int slot, GrandExchangeOfferState state, int itemId,
                                     int price, int totalQty, int qtySold, int spent)
    {
        ensureLoaded();
        if (state == null || state == GrandExchangeOfferState.EMPTY || itemId <= 0) return;
        boolean buy = state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.BOUGHT
            || state == GrandExchangeOfferState.CANCELLED_BUY;

        Slot prev = slots.get(slot);
        int baseQty = 0; long baseSpent = 0;
        if (prev != null && prev.itemId == itemId && prev.buy == buy
            && qtySold >= prev.qty && spent >= prev.spent)
        {
            baseQty = prev.qty; baseSpent = prev.spent;
        }
        int  dQty   = qtySold - baseQty;
        long dSpent = spent - baseSpent;
        slots.put(slot, new Slot(itemId, buy, qtySold, spent));

        if (dQty > 0)
        {
            long unit = dSpent > 0 ? Math.max(1, dSpent / dQty) : price;
            if (buy) positions.computeIfAbsent(itemId, k -> new ArrayDeque<>()).add(new Lot(dQty, unit));
            else consumeSell(itemId, dQty);
        }
        save();
    }

    private void consumeSell(int itemId, int qty)
    {
        Deque<Lot> lots = positions.get(itemId);
        int remaining = qty;
        while (remaining > 0 && lots != null && !lots.isEmpty())
        {
            Lot lot = lots.peekFirst();
            int take = Math.min(remaining, lot.qty);
            lot.qty -= take;
            remaining -= take;
            if (lot.qty == 0) lots.pollFirst();
        }
        if (lots != null && lots.isEmpty()) positions.remove(itemId);
    }

    // ── read ────────────────────────────────────────────────────────────────────

    /** itemId -&gt; {qty, avgBuy} for stock currently held with a known cost basis. */
    public synchronized Map<Integer, long[]> held()
    {
        ensureLoaded();
        Map<Integer, long[]> out = new HashMap<>();
        for (Map.Entry<Integer, Deque<Lot>> e : positions.entrySet())
        {
            long qty = 0, cost = 0;
            for (Lot l : e.getValue()) { qty += l.qty; cost += (long) l.qty * l.unit; }
            if (qty > 0) out.put(e.getKey(), new long[]{ qty, cost / qty });
        }
        return out;
    }

    // ── persistence ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void ensureLoaded()
    {
        if (loaded) return;
        loaded = true;
        try
        {
            String json = configManager.getConfiguration(GROUP, KEY);
            if (json == null || json.isEmpty()) return;
            Map<String, Object> saved = gson.fromJson(json, new TypeToken<Map<String, Object>>(){}.getType());
            if (saved == null) return;
            Map<String, Object> pos = (Map<String, Object>) saved.get("positions");
            if (pos != null) for (Map.Entry<String, Object> e : pos.entrySet())
            {
                Deque<Lot> q = new ArrayDeque<>();
                for (Object o : (List<Object>) e.getValue())
                {
                    List<Object> a = (List<Object>) o;
                    q.add(new Lot(((Number) a.get(0)).intValue(), ((Number) a.get(1)).longValue()));
                }
                positions.put(Integer.parseInt(e.getKey()), q);
            }
            Map<String, Object> sl = (Map<String, Object>) saved.get("slots");
            if (sl != null) for (Map.Entry<String, Object> e : sl.entrySet())
            {
                List<Object> a = (List<Object>) e.getValue();
                slots.put(Integer.parseInt(e.getKey()), new Slot(((Number) a.get(0)).intValue(),
                    Boolean.TRUE.equals(a.get(1)), ((Number) a.get(2)).intValue(), ((Number) a.get(3)).longValue()));
            }
        }
        catch (Exception e) { log.warn("held-cost load failed: {}", e.getMessage()); }
    }

    private void save()
    {
        try
        {
            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Object> pos = new LinkedHashMap<>();
            for (Map.Entry<Integer, Deque<Lot>> e : positions.entrySet())
            {
                List<long[]> list = new java.util.ArrayList<>();
                for (Lot l : e.getValue()) list.add(new long[]{ l.qty, l.unit });
                if (!list.isEmpty()) pos.put(String.valueOf(e.getKey()), list);
            }
            out.put("positions", pos);
            Map<String, Object> sl = new LinkedHashMap<>();
            for (Map.Entry<Integer, Slot> e : slots.entrySet())
            {
                Slot s = e.getValue();
                sl.put(String.valueOf(e.getKey()), new Object[]{ s.itemId, s.buy, s.qty, s.spent });
            }
            out.put("slots", sl);
            configManager.setConfiguration(GROUP, KEY, gson.toJson(out));
        }
        catch (Exception e) { log.warn("held-cost save failed: {}", e.getMessage()); }
    }
}
