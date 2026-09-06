package com.runeassist.flip;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** FIFO lot / slot helpers for {@link HeldCostTracker}. */
final class HeldCostLots {

    private HeldCostLots() {
    }

    static final class Lot {
        int qty;
        long unit;

        Lot(int q, long u) {
            qty = q;
            unit = u;
        }
    }

    static final class Slot {
        int itemId;
        boolean buy;
        int qty;
        long spent;
        long listedMs;
        long lastProgressMs;

        Slot(int i, boolean b, int q, long s, long listed, long progress) {
            itemId = i;
            buy = b;
            qty = q;
            spent = s;
            listedMs = listed;
            lastProgressMs = progress;
        }
    }

    /** Per-account state — never shared across accounts under one RuneLite profile. */
    static final class Account {
        final Map<Integer, Deque<Lot>> positions = new LinkedHashMap<>();
        final Map<Integer, Slot> slots = new HashMap<>();
        final Map<Integer, List<long[]>> limitBuys = new LinkedHashMap<>();
        boolean loaded = false;
    }

    static void addLot(Account acc, int itemId, int qty, long unit) {
        acc.positions.computeIfAbsent(itemId, k -> new ArrayDeque<>()).add(new Lot(qty, unit));
    }

    static void consumeSell(Account acc, int itemId, int qty) {
        Deque<Lot> lots = acc.positions.get(itemId);
        int remaining = qty;
        while (remaining > 0 && lots != null && !lots.isEmpty()) {
            Lot lot = lots.peekFirst();
            int take = Math.min(remaining, lot.qty);
            lot.qty -= take;
            remaining -= take;
            if (lot.qty == 0) {
                lots.pollFirst();
            }
        }
        if (lots != null && lots.isEmpty()) {
            acc.positions.remove(itemId);
        }
    }

    /** Returns {qtyActuallyConsumed, totalCostOfThat}. */
    static long[] consumeUpTo(Account acc, int itemId, int qty) {
        Deque<Lot> lots = acc.positions.get(itemId);
        int remaining = qty;
        long cost = 0;
        long taken = 0;
        while (remaining > 0 && lots != null && !lots.isEmpty()) {
            Lot lot = lots.peekFirst();
            int take = Math.min(remaining, lot.qty);
            cost += (long) take * lot.unit;
            taken += take;
            lot.qty -= take;
            remaining -= take;
            if (lot.qty == 0) {
                lots.pollFirst();
            }
        }
        if (lots != null && lots.isEmpty()) {
            acc.positions.remove(itemId);
        }
        return new long[]{taken, cost};
    }

    static int heldQty(Account acc, int itemId) {
        Deque<Lot> lots = acc.positions.get(itemId);
        if (lots == null) {
            return 0;
        }
        int qty = 0;
        for (Lot l : lots) {
            qty += l.qty;
        }
        return qty;
    }

    /** itemId -> {qty, avgBuy} for stock with a known cost basis. */
    static Map<Integer, long[]> summarize(Account acc) {
        Map<Integer, long[]> out = new HashMap<>();
        for (Map.Entry<Integer, Deque<Lot>> e : acc.positions.entrySet()) {
            long qty = 0;
            long cost = 0;
            for (Lot l : e.getValue()) {
                qty += l.qty;
                cost += (long) l.qty * l.unit;
            }
            if (qty > 0) {
                out.put(e.getKey(), new long[]{qty, cost / qty});
            }
        }
        return out;
    }
}
