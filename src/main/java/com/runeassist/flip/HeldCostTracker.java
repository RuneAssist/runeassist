package com.runeassist.flip;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.runeassist.flip.controller.Persistance;
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

/** FIFO cost-basis tracker for held GE stock; persisted per OSRS display name. */
@Slf4j
@Singleton
public class HeldCostTracker
{
    private static final String GROUP = "runeassistflip";
    private static final String KEY_PREFIX = "heldcost_"; // ConfigManager rejects ':' in keys
    private static final long LIMIT_WINDOW_MS = 4L * 60 * 60 * 1000;

    @Inject private ConfigManager configManager;
    @Inject private Gson gson;

    private final Map<String, HeldCostLots.Account> accounts = new HashMap<>();

    private synchronized HeldCostLots.Account account(String displayName)
    {
        String key = Persistance.hashDisplayName(displayName == null ? "" : displayName);
        return accounts.computeIfAbsent(key, k -> new HeldCostLots.Account());
    }

    public synchronized void onOffer(String displayName, int slot, GrandExchangeOfferState state, int itemId,
                                     int price, int totalQty, int qtySold, int spent)
    {
        HeldCostLots.Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        if (state == null || state == GrandExchangeOfferState.EMPTY || itemId <= 0) return;
        boolean buy = state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.BOUGHT
            || state == GrandExchangeOfferState.CANCELLED_BUY;

        HeldCostLots.Slot prev = acc.slots.get(slot);
        int baseQty = 0; long baseSpent = 0;
        boolean sameInstance = prev != null && prev.itemId == itemId && prev.buy == buy
            && qtySold >= prev.qty && spent >= prev.spent;
        if (sameInstance)
        {
            baseQty = prev.qty; baseSpent = prev.spent;
        }
        int dQty = qtySold - baseQty;
        long dSpent = spent - baseSpent;
        long now = System.currentTimeMillis();
        long listed = sameInstance && prev.listedMs > 0L ? prev.listedMs : now;
        long lastProgress = dQty > 0 ? now
            : (sameInstance && prev.lastProgressMs > 0L ? prev.lastProgressMs : now);
        acc.slots.put(slot, new HeldCostLots.Slot(itemId, buy, qtySold, spent, listed, lastProgress));

        if (dQty > 0)
        {
            long unit = dSpent > 0 ? Math.max(1, dSpent / dQty) : price;
            if (buy)
            {
                HeldCostLots.addLot(acc, itemId, dQty, unit);
                acc.limitBuys.computeIfAbsent(itemId, k -> new ArrayList<>())
                    .add(new long[]{ dQty, System.currentTimeMillis() });
            }
            else HeldCostLots.consumeSell(acc, itemId, dQty);
        }
        save(displayName, acc);
    }

    /** Dose-conserving bank decant: carry FIFO cost from {@code fromItemId} into {@code toItemId}. */
    public synchronized void applyDecant(String displayName, int fromItemId, int fromQty, int toItemId, int toQty)
    {
        if (fromQty <= 0 || toQty <= 0 || fromItemId <= 0 || toItemId <= 0) return;
        HeldCostLots.Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        long[] consumed = HeldCostLots.consumeUpTo(acc, fromItemId, fromQty);
        long qtyConsumed = consumed[0], costConsumed = consumed[1];
        if (qtyConsumed <= 0) return;
        long producedQty = Math.max(1, (toQty * qtyConsumed) / fromQty);
        long unit = Math.max(1, costConsumed / producedQty);
        HeldCostLots.addLot(acc, toItemId, (int) producedQty, unit);
        save(displayName, acc);
    }

    /** Manual held lot (Add to portfolio); {@code unitCost} is typically a market quote estimate. */
    public synchronized void addManualLot(String displayName, int itemId, int qty, long unitCost)
    {
        if (itemId <= 0 || qty <= 0 || unitCost < 0) return;
        HeldCostLots.Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        HeldCostLots.addLot(acc, itemId, qty, unitCost);
        save(displayName, acc);
    }

    /** Drop held stock for item; {@code qty <= 0} clears all lots. Returns qty removed. */
    public synchronized int removeLots(String displayName, int itemId, int qty)
    {
        if (itemId <= 0) return 0;
        HeldCostLots.Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        int before = HeldCostLots.heldQty(acc, itemId);
        if (before <= 0) return 0;
        if (qty <= 0) acc.positions.remove(itemId);
        else HeldCostLots.consumeSell(acc, itemId, qty);
        save(displayName, acc);
        return before - HeldCostLots.heldQty(acc, itemId);
    }

    /** Drop every tracked lot for this account. */
    public synchronized int clearLots(String displayName)
    {
        HeldCostLots.Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        int removed = 0;
        for (Deque<HeldCostLots.Lot> lots : acc.positions.values())
        {
            for (HeldCostLots.Lot l : lots) removed += l.qty;
        }
        acc.positions.clear();
        save(displayName, acc);
        return removed;
    }

    /**
     * Replace cost-basis lots from server held snapshot. Slots / 4h limit trackers unchanged.
     * @param held itemId -&gt; [qty, avgBuy]; null/empty clears positions
     */
    public synchronized void replaceServerHeld(String displayName, Map<Integer, long[]> held)
    {
        HeldCostLots.Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        acc.positions.clear();
        if (held != null)
        {
            for (Map.Entry<Integer, long[]> e : held.entrySet())
            {
                if (e.getKey() == null || e.getKey() <= 0 || e.getValue() == null || e.getValue().length < 2)
                {
                    continue;
                }
                int qty = (int) e.getValue()[0];
                long unit = e.getValue()[1];
                if (qty <= 0 || unit < 0) continue;
                Deque<HeldCostLots.Lot> q = new ArrayDeque<>();
                q.add(new HeldCostLots.Lot(qty, unit));
                acc.positions.put(e.getKey(), q);
            }
        }
        save(displayName, acc);
    }

    public synchronized Map<Integer, long[]> held(String displayName)
    {
        HeldCostLots.Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        return HeldCostLots.summarize(acc);
    }

    public synchronized long lastProgressMs(String displayName, int slot, int itemId)
    {
        if (itemId <= 0) return 0L;
        HeldCostLots.Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        HeldCostLots.Slot s = acc.slots.get(slot);
        return s == null || s.itemId != itemId ? 0L : s.lastProgressMs;
    }

    public synchronized long listedMs(String displayName, int slot, int itemId)
    {
        if (itemId <= 0) return 0L;
        HeldCostLots.Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        HeldCostLots.Slot s = acc.slots.get(slot);
        return s == null || s.itemId != itemId ? 0L : s.listedMs;
    }

    public synchronized int boughtInWindow(String displayName, int itemId)
    {
        HeldCostLots.Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        pruneLimitBuys(acc);
        List<long[]> list = acc.limitBuys.get(itemId);
        if (list == null) return 0;
        int sum = 0;
        for (long[] a : list) sum += (int) a[0];
        return sum;
    }

    public synchronized Map<Integer, Integer> boughtInWindowAll(String displayName)
    {
        HeldCostLots.Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        pruneLimitBuys(acc);
        Map<Integer, Integer> out = new HashMap<>();
        for (Map.Entry<Integer, List<long[]>> e : acc.limitBuys.entrySet())
        {
            int sum = 0;
            for (long[] a : e.getValue()) sum += (int) a[0];
            if (sum > 0) out.put(e.getKey(), sum);
        }
        return out;
    }

    /** Remaining for card display; -1 if wiki limit unknown or no live-fill tracker data. */
    public synchronized int remainingLimitOrUnknown(String displayName, int itemId, int geLimit)
    {
        if (geLimit <= 0) return -1;
        if (!hasLimitTrackerData(displayName, itemId)) return -1;
        return Math.max(0, geLimit - boughtInWindow(displayName, itemId));
    }

    public synchronized boolean hasLimitTrackerData(String displayName, int itemId)
    {
        HeldCostLots.Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        pruneLimitBuys(acc);
        List<long[]> list = acc.limitBuys.get(itemId);
        return list != null && !list.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private void ensureLoaded(String displayName, HeldCostLots.Account acc)
    {
        if (acc.loaded) return;
        acc.loaded = true;
        try
        {
            String json = configManager.getConfiguration(GROUP, configKey(displayName));
            if (json == null || json.isEmpty()) return;
            Map<String, Object> saved = gson.fromJson(json, new TypeToken<Map<String, Object>>(){}.getType());
            if (saved == null) return;
            Map<String, Object> pos = (Map<String, Object>) saved.get("positions");
            if (pos != null) for (Map.Entry<String, Object> e : pos.entrySet())
            {
                Deque<HeldCostLots.Lot> q = new ArrayDeque<>();
                for (Object o : (List<Object>) e.getValue())
                {
                    List<Object> a = (List<Object>) o;
                    q.add(new HeldCostLots.Lot(((Number) a.get(0)).intValue(), ((Number) a.get(1)).longValue()));
                }
                acc.positions.put(Integer.parseInt(e.getKey()), q);
            }
            Map<String, Object> sl = (Map<String, Object>) saved.get("slots");
            if (sl != null) for (Map.Entry<String, Object> e : sl.entrySet())
            {
                List<Object> a = (List<Object>) e.getValue();
                acc.slots.put(Integer.parseInt(e.getKey()), new HeldCostLots.Slot(((Number) a.get(0)).intValue(),
                    Boolean.TRUE.equals(a.get(1)), ((Number) a.get(2)).intValue(), ((Number) a.get(3)).longValue(),
                    a.size() > 4 ? ((Number) a.get(4)).longValue() : 0L,
                    a.size() > 5 ? ((Number) a.get(5)).longValue() : 0L));
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
                if (!list.isEmpty()) acc.limitBuys.put(Integer.parseInt(e.getKey()), list);
            }
            pruneLimitBuys(acc);
        }
        catch (Exception e) { log.warn("held-cost load failed: {}", e.getMessage()); }
    }

    private void save(String displayName, HeldCostLots.Account acc)
    {
        try
        {
            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Object> pos = new LinkedHashMap<>();
            for (Map.Entry<Integer, Deque<HeldCostLots.Lot>> e : acc.positions.entrySet())
            {
                List<long[]> list = new ArrayList<>();
                for (HeldCostLots.Lot l : e.getValue()) list.add(new long[]{ l.qty, l.unit });
                if (!list.isEmpty()) pos.put(String.valueOf(e.getKey()), list);
            }
            out.put("positions", pos);
            Map<String, Object> sl = new LinkedHashMap<>();
            for (Map.Entry<Integer, HeldCostLots.Slot> e : acc.slots.entrySet())
            {
                HeldCostLots.Slot s = e.getValue();
                sl.put(String.valueOf(e.getKey()), new Object[]{
                    s.itemId, s.buy, s.qty, s.spent, s.listedMs, s.lastProgressMs });
            }
            out.put("slots", sl);
            pruneLimitBuys(acc);
            Map<String, Object> lim = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<long[]>> e : acc.limitBuys.entrySet())
            {
                if (!e.getValue().isEmpty()) lim.put(String.valueOf(e.getKey()), e.getValue());
            }
            out.put("limitBuys", lim);
            configManager.setConfiguration(GROUP, configKey(displayName), gson.toJson(out));
        }
        catch (Exception e) { log.warn("held-cost save failed", e); }
    }

    private static String configKey(String displayName)
    {
        return KEY_PREFIX + Persistance.hashDisplayName(displayName == null ? "" : displayName);
    }

    private void pruneLimitBuys(HeldCostLots.Account acc)
    {
        long cutoff = System.currentTimeMillis() - LIMIT_WINDOW_MS;
        acc.limitBuys.entrySet().removeIf(e ->
        {
            e.getValue().removeIf(a -> a[1] < cutoff);
            return e.getValue().isEmpty();
        });
    }
}
