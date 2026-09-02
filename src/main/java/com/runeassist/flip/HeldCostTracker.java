package com.runeassist.flip;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
 * <p>Also records units bought in a rolling 4-hour window so suggestions can respect the
 * remaining GE buy limit. Fed from the fork plugin's {@code onGrandExchangeOfferChanged}.
 * Read via {@link #held()} and {@link #limitRemaining(int, int)}.
 */
@Slf4j
@Singleton
public class HeldCostTracker
{
    private static final String GROUP = "runeassistflip";
    private static final String KEY = "heldcost";
    private static final long LIMIT_WINDOW_MS = 4L * 60 * 60 * 1000; // GE buy limit resets every 4h

    @Inject private ConfigManager configManager;
    @Inject private Gson gson;

    private static final class Lot { int qty; long unit; Lot(int q, long u){ qty=q; unit=u; } }
    private static final class Slot { int itemId; boolean buy; int qty; long spent;
        Slot(int i, boolean b, int q, long s){ itemId=i; buy=b; qty=q; spent=s; } }

    private final Map<Integer, Deque<Lot>> positions = new LinkedHashMap<>();
    private final Map<Integer, Slot> slots = new HashMap<>();
    private final Map<Integer, List<long[]>> limitBuys = new LinkedHashMap<>(); // itemId -> [qty, time]
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
            if (buy)
            {
                positions.computeIfAbsent(itemId, k -> new ArrayDeque<>()).add(new Lot(dQty, unit));
                limitBuys.computeIfAbsent(itemId, k -> new ArrayList<>())
                    .add(new long[]{ dQty, System.currentTimeMillis() });
            }
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

    /** Units of {@code itemId} bought in the last 4h (GE buy-limit window). */
    public synchronized int boughtInWindow(int itemId)
    {
        ensureLoaded();
        pruneLimitBuys();
        List<long[]> list = limitBuys.get(itemId);
        if (list == null) return 0;
        int sum = 0;
        for (long[] a : list) sum += (int) a[0];
        return sum;
    }

    /** itemId -> units bought in the last 4h, for items with any window usage. */
    public synchronized Map<Integer, Integer> boughtInWindowAll()
    {
        ensureLoaded();
        pruneLimitBuys();
        Map<Integer, Integer> out = new HashMap<>();
        for (Map.Entry<Integer, List<long[]>> e : limitBuys.entrySet())
        {
            int sum = 0;
            for (long[] a : e.getValue()) sum += (int) a[0];
            if (sum > 0) out.put(e.getKey(), sum);
        }
        return out;
    }

    /**
     * Remaining 4h GE buy-limit for an item. Returns {@code geLimit} minus live fills we
     * observed this window. {@code geLimit <= 0} → {@code -1} (unknown wiki limit).
     * This is not reconstructed from GE history (those rows usually have no timestamps).
     * When there are no live fills, this returns the full wiki cap — use
     * {@link #remainingLimitOrUnknown(int, int)} for display so that is not implied known.
     */
    public synchronized int limitRemaining(int itemId, int geLimit)
    {
        if (geLimit <= 0) return -1;
        return Math.max(0, geLimit - boughtInWindow(itemId));
    }

    /**
     * Remaining we can honestly show on the card: wiki limit minus live fills this window.
     * {@code -1} if the wiki limit is unknown or we have no live-fill tracker data
     * (do not treat an unused tracker as "full 4h limit left").
     */
    public synchronized int remainingLimitOrUnknown(int itemId, int geLimit)
    {
        if (geLimit <= 0) return -1;
        if (!hasLimitTrackerData(itemId)) return -1;
        return Math.max(0, geLimit - boughtInWindow(itemId));
    }

    /** True when we have observed at least one buy fill of this item in the current 4h window. */
    public synchronized boolean hasLimitTrackerData(int itemId)
    {
        ensureLoaded();
        pruneLimitBuys();
        List<long[]> list = limitBuys.get(itemId);
        return list != null && !list.isEmpty();
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
            Map<String, Object> lim = (Map<String, Object>) saved.get("limitBuys");
            if (lim != null) for (Map.Entry<String, Object> e : lim.entrySet())
            {
                List<long[]> list = new ArrayList<>();
                for (Object o : (List<Object>) e.getValue())
                {
                    List<Object> a = (List<Object>) o;
                    list.add(new long[]{ ((Number) a.get(0)).longValue(), ((Number) a.get(1)).longValue() });
                }
                if (!list.isEmpty()) limitBuys.put(Integer.parseInt(e.getKey()), list);
            }
            pruneLimitBuys();
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
            pruneLimitBuys();
            Map<String, Object> lim = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<long[]>> e : limitBuys.entrySet())
            {
                if (!e.getValue().isEmpty()) lim.put(String.valueOf(e.getKey()), e.getValue());
            }
            out.put("limitBuys", lim);
            configManager.setConfiguration(GROUP, KEY, gson.toJson(out));
        }
        catch (Exception e) { log.warn("held-cost save failed: {}", e.getMessage()); }
    }

    private void pruneLimitBuys()
    {
        long cutoff = System.currentTimeMillis() - LIMIT_WINDOW_MS;
        limitBuys.entrySet().removeIf(e ->
        {
            e.getValue().removeIf(a -> a[1] < cutoff);
            return e.getValue().isEmpty();
        });
    }
}
