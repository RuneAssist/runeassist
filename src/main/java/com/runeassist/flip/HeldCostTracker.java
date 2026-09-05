package com.runeassist.flip;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.runeassist.flip.controller.Persistance;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.config.ConfigManager;

import com.runeassist.flip.model.GeHistoryRow;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Lightweight, self-contained FIFO cost-basis tracker for the RuneAssist flipping fork.
 * Watches GE offer changes, records the average buy price of stock you're accumulating, and
 * consumes those lots (FIFO) as you sell — so the fork can suggest selling held stock at a
 * real profit/loss. Persisted in RuneLite config (group {@code runeassistflip}) with per-slot
 * cumulative counters so a relog re-emitting existing offers doesn't double-count.
 *
 * <p>Scoped per OSRS account (display name), same as {@link com.runeassist.flip.model.LocalFlipLedger}'s
 * per-account ledger files — a RuneLite settings profile is not the same thing as a game
 * account. Before this scoping existed, everything here was one shared bucket keyed only to
 * the active RuneLite profile: switching between two OSRS accounts under one profile mixed
 * their cost-basis data together (a purchase on one account could surface as a phantom sell
 * suggestion on the other, with no way to tell them apart).
 *
 * <p>Also records units bought in a rolling 4-hour window so suggestions can respect the
 * remaining GE buy limit. Fed from the fork plugin's {@code onGrandExchangeOfferChanged}.
 * Read via {@link #held(String)} and {@link #limitRemaining(String, int, int)}.
 */
@Slf4j
@Singleton
public class HeldCostTracker
{
    private static final String GROUP = "runeassistflip";
    // Must not contain ':' -- ConfigManager.setConfiguration rejects any key containing one
    // (bare, message-less IllegalArgumentException), so the previous "heldcost:" prefix meant
    // every single save threw and the ledger never once persisted. Nothing is lost by renaming
    // it: no data could ever have been written under the old key.
    private static final String KEY_PREFIX = "heldcost_";
    private static final long LIMIT_WINDOW_MS = 4L * 60 * 60 * 1000; // GE buy limit resets every 4h

    @Inject private ConfigManager configManager;
    @Inject private Gson gson;

    private static final class Lot { int qty; long unit; Lot(int q, long u){ qty=q; unit=u; } }
    private static final class Slot { int itemId; boolean buy; int qty; long spent;
        long listedMs; long lastProgressMs;
        Slot(int i, boolean b, int q, long s, long listed, long progress) {
            itemId=i; buy=b; qty=q; spent=s; listedMs=listed; lastProgressMs=progress;
        } }

    /** Per-account state — never shared across accounts, even under one RuneLite profile. */
    private static final class Account
    {
        final Map<Integer, Deque<Lot>> positions = new LinkedHashMap<>();
        final Map<Integer, Slot> slots = new HashMap<>();
        final Map<Integer, List<long[]>> limitBuys = new LinkedHashMap<>(); // itemId -> [qty, time]
        String historyFp = "";
        boolean loaded = false;
    }

    private final Map<String, Account> accounts = new HashMap<>(); // hashed displayName -> state

    private synchronized Account account(String displayName)
    {
        String key = Persistance.hashDisplayName(displayName == null ? "" : displayName);
        return accounts.computeIfAbsent(key, k -> new Account());
    }

    // ── ingest ──────────────────────────────────────────────────────────────────

    public synchronized void onOffer(String displayName, int slot, GrandExchangeOfferState state, int itemId,
                                     int price, int totalQty, int qtySold, int spent)
    {
        Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        if (state == null || state == GrandExchangeOfferState.EMPTY || itemId <= 0) return;
        boolean buy = state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.BOUGHT
            || state == GrandExchangeOfferState.CANCELLED_BUY;

        Slot prev = acc.slots.get(slot);
        int baseQty = 0; long baseSpent = 0;
        boolean sameInstance = prev != null && prev.itemId == itemId && prev.buy == buy
            && qtySold >= prev.qty && spent >= prev.spent;
        if (sameInstance)
        {
            baseQty = prev.qty; baseSpent = prev.spent;
        }
        int  dQty   = qtySold - baseQty;
        long dSpent = spent - baseSpent;
        long now = System.currentTimeMillis();
        long listed = sameInstance && prev.listedMs > 0L ? prev.listedMs : now;
        long lastProgress;
        if (dQty > 0)
        {
            lastProgress = now;
        }
        else if (sameInstance && prev.lastProgressMs > 0L)
        {
            lastProgress = prev.lastProgressMs;
        }
        else
        {
            lastProgress = now;
        }
        acc.slots.put(slot, new Slot(itemId, buy, qtySold, spent, listed, lastProgress));

        if (dQty > 0)
        {
            long unit = dSpent > 0 ? Math.max(1, dSpent / dQty) : price;
            if (buy)
            {
                acc.positions.computeIfAbsent(itemId, k -> new ArrayDeque<>()).add(new Lot(dQty, unit));
                acc.limitBuys.computeIfAbsent(itemId, k -> new ArrayList<>())
                    .add(new long[]{ dQty, System.currentTimeMillis() });
            }
            else consumeSell(acc, itemId, dQty);
        }
        save(displayName, acc);
    }

    private void consumeSell(Account acc, int itemId, int qty)
    {
        Deque<Lot> lots = acc.positions.get(itemId);
        int remaining = qty;
        while (remaining > 0 && lots != null && !lots.isEmpty())
        {
            Lot lot = lots.peekFirst();
            int take = Math.min(remaining, lot.qty);
            lot.qty -= take;
            remaining -= take;
            if (lot.qty == 0) lots.pollFirst();
        }
        if (lots != null && lots.isEmpty()) acc.positions.remove(itemId);
    }

    /**
     * A bank decant just converted {@code fromQty} units of {@code fromItemId} into
     * {@code toQty} units of {@code toItemId} (potion doses conserved, e.g. 4x 1-dose ->
     * 1x 4-dose). This never touches the Grand Exchange, so {@link #onOffer} never sees it —
     * this is the fork's alternative entry point, called from live inventory/bank diffing
     * (see {@code RuneAssistSuggestionSource}'s decant detection) once a dose-conserving qty
     * shift between the two items is observed.
     *
     * <p>Carries the real (not estimated) blended FIFO cost of whatever tracked
     * {@code fromItemId} lots are actually consumed into a new {@code toItemId} lot. If only
     * part of {@code fromQty} has a tracked cost basis (e.g. some of it predates this
     * tracker), the produced lot is scaled down proportionally rather than fabricating a cost
     * for the untracked portion — the untracked slice simply stays untracked, same as it
     * would if it had been decanted before this existed.</p>
     */
    public synchronized void applyDecant(String displayName, int fromItemId, int fromQty, int toItemId, int toQty)
    {
        if (fromQty <= 0 || toQty <= 0 || fromItemId <= 0 || toItemId <= 0) return;
        Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        long[] consumed = consumeUpTo(acc, fromItemId, fromQty); // {qtyConsumed, costConsumed}
        long qtyConsumed = consumed[0], costConsumed = consumed[1];
        if (qtyConsumed <= 0) return; // nothing tracked to carry over
        long producedQty = Math.max(1, (toQty * qtyConsumed) / fromQty); // proportional to what we could cost
        long unit = Math.max(1, costConsumed / producedQty);
        acc.positions.computeIfAbsent(toItemId, k -> new ArrayDeque<>()).add(new Lot((int) producedQty, unit));
        save(displayName, acc);
    }

    /**
     * Manually register held stock this tracker never saw bought — a bank/inventory item the
     * player already had before RuneAssist was tracking, or one bought outside a tracked GE
     * offer. Right-click "Add to portfolio" in the game (see {@code MenuHandler}) feeds this,
     * so previously-untracked ("forgotten") items start showing up in sell suggestions and
     * portfolio value the same as anything bought through the GE while tracked.
     *
     * <p>{@code unitCost} is whatever the caller supplies as the cost basis (typically the
     * current market buy quote, since the real historical price paid is unknown) — this is
     * an estimate, not a tracked fact, same caveat as the decant carry-over cost.</p>
     */
    public synchronized void addManualLot(String displayName, int itemId, int qty, long unitCost)
    {
        if (itemId <= 0 || qty <= 0 || unitCost < 0) return;
        Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        acc.positions.computeIfAbsent(itemId, k -> new ArrayDeque<>()).add(new Lot(qty, unitCost));
        save(displayName, acc);
    }

    /**
     * Drop tracked held stock for {@code itemId} (portfolio panel "Remove from portfolio").
     * {@code qty <= 0} removes every lot for the item; otherwise oldest lots go first (FIFO).
     * Returns the quantity removed.
     *
     * <p>This forgets a cost basis; it does not sell, bank or otherwise touch the item in
     * game. Units still filling on a live buy offer are re-floored back into the portfolio on
     * the next suggestion cycle (see {@code mergeHeldWithOfferFills} in the suggestion
     * source), so removing those only sticks once the offer is collected.</p>
     */
    public synchronized int removeLots(String displayName, int itemId, int qty)
    {
        if (itemId <= 0) return 0;
        Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        int before = heldQty(acc, itemId);
        if (before <= 0) return 0;
        if (qty <= 0) acc.positions.remove(itemId);
        else consumeSell(acc, itemId, qty);
        save(displayName, acc);
        return before - heldQty(acc, itemId);
    }

    /** Drop every tracked lot for this account -- "Remove everything from portfolio". */
    public synchronized int clearLots(String displayName)
    {
        Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        int removed = 0;
        for (Deque<Lot> lots : acc.positions.values())
        {
            for (Lot l : lots) removed += l.qty;
        }
        acc.positions.clear();
        save(displayName, acc);
        return removed;
    }

    /**
     * Replace cost-basis lots from the server portfolio/held snapshot.
     * Once the client is linked, server state wins across restarts; live GE fills
     * may still update lots optimistically until the next sync. Slots and 4h
     * limit trackers are left alone (session-local offer progress).
     *
     * @param held itemId -&gt; [qty, avgBuy]; null or empty clears positions
     */
    public synchronized void replaceServerHeld(String displayName, Map<Integer, long[]> held)
    {
        Account acc = account(displayName);
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
                if (qty <= 0 || unit < 0)
                {
                    continue;
                }
                Deque<Lot> q = new ArrayDeque<>();
                q.add(new Lot(qty, unit));
                acc.positions.put(e.getKey(), q);
            }
        }
        save(displayName, acc);
    }

    private static int heldQty(Account acc, int itemId)
    {
        Deque<Lot> lots = acc.positions.get(itemId);
        if (lots == null) return 0;
        int qty = 0;
        for (Lot l : lots) qty += l.qty;
        return qty;
    }

    /**
     * When GE history opens, add FIFO lots for completed buys that are not already in
     * this tracker. Does not record 4h limit usage (history usually has no timestamps)
     * and does not autotype. Returns the number of lots added.
     */
    public synchronized int applyHistoryBackfill(String displayName, List<GeHistoryRow> newestFirst,
                                                 Map<Integer, Long> liveFillingBuyQty,
                                                 IntUnaryOperator unnote)
    {
        if (displayName == null || displayName.isEmpty() || newestFirst == null || newestFirst.isEmpty()) {
            return 0;
        }
        Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        Map<Integer, Long> heldQty = new HashMap<>();
        for (Map.Entry<Integer, Deque<Lot>> e : acc.positions.entrySet())
        {
            long qty = 0;
            for (Lot l : e.getValue()) qty += l.qty;
            if (qty > 0) heldQty.put(e.getKey(), qty);
        }
        Map<Integer, Long> filling = liveFillingBuyQty == null ? Collections.emptyMap() : liveFillingBuyQty;
        HeldCostHistoryBackfill.Result result = HeldCostHistoryBackfill.lotsToAdd(
            newestFirst, heldQty, filling, acc.historyFp, unnote);
        if (!result.changed) return 0;
        int added = 0;
        for (HeldCostHistoryBackfill.Lot lot : result.lots)
        {
            if (lot == null || lot.itemId <= 0 || lot.qty <= 0 || lot.unit < 0) continue;
            acc.positions.computeIfAbsent(lot.itemId, k -> new ArrayDeque<>())
                .add(new Lot(lot.qty, lot.unit));
            added++;
        }
        acc.historyFp = result.fingerprint == null ? "" : result.fingerprint;
        save(displayName, acc);
        return added;
    }

    /** Like {@link #consumeSell}, but returns {qtyActuallyConsumed, totalCostOfThat}. */
    private long[] consumeUpTo(Account acc, int itemId, int qty)
    {
        Deque<Lot> lots = acc.positions.get(itemId);
        int remaining = qty;
        long cost = 0, taken = 0;
        while (remaining > 0 && lots != null && !lots.isEmpty())
        {
            Lot lot = lots.peekFirst();
            int take = Math.min(remaining, lot.qty);
            cost += (long) take * lot.unit;
            taken += take;
            lot.qty -= take;
            remaining -= take;
            if (lot.qty == 0) lots.pollFirst();
        }
        if (lots != null && lots.isEmpty()) acc.positions.remove(itemId);
        return new long[]{ taken, cost };
    }

    // ── read ────────────────────────────────────────────────────────────────────

    /** itemId -&gt; {qty, avgBuy} for stock currently held with a known cost basis. */
    public synchronized Map<Integer, long[]> held(String displayName)
    {
        Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        Map<Integer, long[]> out = new HashMap<>();
        for (Map.Entry<Integer, Deque<Lot>> e : acc.positions.entrySet())
        {
            long qty = 0, cost = 0;
            for (Lot l : e.getValue()) { qty += l.qty; cost += (long) l.qty * l.unit; }
            if (qty > 0) out.put(e.getKey(), new long[]{ qty, cost / qty });
        }
        return out;
    }

    /** Epoch millis of last {@code quantitySold} increase (or listing time if never filled). 0 if unknown. */
    public synchronized long lastProgressMs(String displayName, int slot, int itemId)
    {
        if (itemId <= 0) return 0L;
        Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        Slot s = acc.slots.get(slot);
        if (s == null || s.itemId != itemId) return 0L;
        return s.lastProgressMs;
    }

    /** Epoch millis this slot's current offer instance was first listed. 0 if unknown. */
    public synchronized long listedMs(String displayName, int slot, int itemId)
    {
        if (itemId <= 0) return 0L;
        Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        Slot s = acc.slots.get(slot);
        if (s == null || s.itemId != itemId) return 0L;
        return s.listedMs;
    }

    /** Units of {@code itemId} bought in the last 4h (GE buy-limit window). */
    public synchronized int boughtInWindow(String displayName, int itemId)
    {
        Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        pruneLimitBuys(acc);
        List<long[]> list = acc.limitBuys.get(itemId);
        if (list == null) return 0;
        int sum = 0;
        for (long[] a : list) sum += (int) a[0];
        return sum;
    }

    /** itemId -> units bought in the last 4h, for items with any window usage. */
    public synchronized Map<Integer, Integer> boughtInWindowAll(String displayName)
    {
        Account acc = account(displayName);
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

    /**
     * Remaining 4h GE buy-limit for an item. Returns {@code geLimit} minus live fills we
     * observed this window. {@code geLimit <= 0} → {@code -1} (unknown wiki limit).
     * This is not reconstructed from GE history (those rows usually have no timestamps).
     * When there are no live fills, this returns the full wiki cap — use
     * {@link #remainingLimitOrUnknown(String, int, int)} for display so that is not implied known.
     */
    public synchronized int limitRemaining(String displayName, int itemId, int geLimit)
    {
        if (geLimit <= 0) return -1;
        return Math.max(0, geLimit - boughtInWindow(displayName, itemId));
    }

    /**
     * Remaining we can honestly show on the card: wiki limit minus live fills this window.
     * {@code -1} if the wiki limit is unknown or we have no live-fill tracker data
     * (do not treat an unused tracker as "full 4h limit left").
     */
    public synchronized int remainingLimitOrUnknown(String displayName, int itemId, int geLimit)
    {
        if (geLimit <= 0) return -1;
        if (!hasLimitTrackerData(displayName, itemId)) return -1;
        return Math.max(0, geLimit - boughtInWindow(displayName, itemId));
    }

    /** True when we have observed at least one buy fill of this item in the current 4h window. */
    public synchronized boolean hasLimitTrackerData(String displayName, int itemId)
    {
        Account acc = account(displayName);
        ensureLoaded(displayName, acc);
        pruneLimitBuys(acc);
        List<long[]> list = acc.limitBuys.get(itemId);
        return list != null && !list.isEmpty();
    }

    // ── persistence ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void ensureLoaded(String displayName, Account acc)
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
                Deque<Lot> q = new ArrayDeque<>();
                for (Object o : (List<Object>) e.getValue())
                {
                    List<Object> a = (List<Object>) o;
                    q.add(new Lot(((Number) a.get(0)).intValue(), ((Number) a.get(1)).longValue()));
                }
                acc.positions.put(Integer.parseInt(e.getKey()), q);
            }
            Map<String, Object> sl = (Map<String, Object>) saved.get("slots");
            if (sl != null) for (Map.Entry<String, Object> e : sl.entrySet())
            {
                List<Object> a = (List<Object>) e.getValue();
                acc.slots.put(Integer.parseInt(e.getKey()), new Slot(((Number) a.get(0)).intValue(),
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
            Object fp = saved.get("historyFp");
            if (fp instanceof String) acc.historyFp = (String) fp;
        }
        catch (Exception e) { log.warn("held-cost load failed: {}", e.getMessage()); }
    }

    private void save(String displayName, Account acc)
    {
        try
        {
            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Object> pos = new LinkedHashMap<>();
            for (Map.Entry<Integer, Deque<Lot>> e : acc.positions.entrySet())
            {
                List<long[]> list = new java.util.ArrayList<>();
                for (Lot l : e.getValue()) list.add(new long[]{ l.qty, l.unit });
                if (!list.isEmpty()) pos.put(String.valueOf(e.getKey()), list);
            }
            out.put("positions", pos);
            Map<String, Object> sl = new LinkedHashMap<>();
            for (Map.Entry<Integer, Slot> e : acc.slots.entrySet())
            {
                Slot s = e.getValue();
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
            if (acc.historyFp != null && !acc.historyFp.isEmpty()) out.put("historyFp", acc.historyFp);
            configManager.setConfiguration(GROUP, configKey(displayName), gson.toJson(out));
        }
        // Log the exception itself, not just getMessage() -- the failure this fixed threw a
        // message-less IllegalArgumentException, so the old one-liner logged only "null" and
        // gave nothing to act on.
        catch (Exception e) { log.warn("held-cost save failed", e); }
    }

    private static String configKey(String displayName)
    {
        return KEY_PREFIX + Persistance.hashDisplayName(displayName == null ? "" : displayName);
    }

    private void pruneLimitBuys(Account acc)
    {
        long cutoff = System.currentTimeMillis() - LIMIT_WINDOW_MS;
        acc.limitBuys.entrySet().removeIf(e ->
        {
            e.getValue().removeIf(a -> a[1] < cutoff);
            return e.getValue().isEmpty();
        });
    }
}
