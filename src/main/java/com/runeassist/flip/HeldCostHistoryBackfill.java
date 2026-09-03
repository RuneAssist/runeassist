package com.runeassist.flip;

import com.runeassist.flip.model.GeHistoryRow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Reconstruct FIFO held lots from completed GE history rows. History is newest-first
 * and usually has no timestamps, so this never touches the 4h buy-limit window —
 * only cost basis for items (or leftover qty) the live tracker has not already booked.
 *
 * <p>Sells consume earlier buys; noted rows are skipped; in-progress live buy fills
 * are treated as already tracked so a BOUGHT slot that is also in history is not
 * double-counted.
 */
public final class HeldCostHistoryBackfill {

    private static final int COINS = 995;
    private static final int PLATINUM = 13204;

    private HeldCostHistoryBackfill() {}

    public static final class Lot {
        public final int itemId;
        public final int qty;
        public final long unit;

        public Lot(int itemId, int qty, long unit) {
            this.itemId = itemId;
            this.qty = qty;
            this.unit = unit;
        }
    }

    public static final class Result {
        public final String fingerprint;
        public final List<Lot> lots;
        /** True when this snapshot has not already been applied. */
        public final boolean changed;

        Result(String fingerprint, List<Lot> lots, boolean changed) {
            this.fingerprint = fingerprint;
            this.lots = lots;
            this.changed = changed;
        }
    }

    /**
     * @param newestFirst GE history widget order
     * @param alreadyHeldQty itemId -&gt; qty currently in {@link HeldCostTracker}
     * @param liveFillingBuyQty in-progress BUYING {@code quantitySold} (not yet in history)
     * @param previousFingerprint last applied snapshot, or null
     * @param unnote maps noted item ids to the GE tradeable id; null = identity
     */
    public static Result lotsToAdd(List<GeHistoryRow> newestFirst,
                                   Map<Integer, Long> alreadyHeldQty,
                                   Map<Integer, Long> liveFillingBuyQty,
                                   String previousFingerprint,
                                   IntUnaryOperator unnote) {
        List<GeHistoryRow> rows = newestFirst == null ? Collections.emptyList() : newestFirst;
        String fp = fingerprint(rows);
        if (fp.equals(previousFingerprint == null ? "" : previousFingerprint)) {
            return new Result(fp, Collections.emptyList(), false);
        }
        IntUnaryOperator toUnnoted = unnote == null ? id -> id : unnote;
        Map<Integer, Deque<Lot>> remaining = replayOldestFirst(rows, toUnnoted);
        List<Lot> add = new ArrayList<>();
        for (Map.Entry<Integer, Deque<Lot>> e : remaining.entrySet()) {
            int itemId = e.getKey();
            long held = getQty(alreadyHeldQty, itemId);
            long filling = getQty(liveFillingBuyQty, itemId);
            long alreadyCompleted = Math.max(0L, held - filling);
            add.addAll(peelNewest(e.getValue(), alreadyCompleted));
        }
        return new Result(fp, add, true);
    }

    static String fingerprint(List<GeHistoryRow> newestFirst) {
        if (newestFirst == null || newestFirst.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(newestFirst.size() * 24);
        for (GeHistoryRow row : newestFirst) {
            if (row == null || row.getItemId() <= 0 || row.getQuantity() <= 0 || row.getPrice() <= 0L) {
                continue;
            }
            sb.append(row.getItemId()).append(',')
                    .append(row.getQuantity()).append(',')
                    .append(row.getPrice()).append(',')
                    .append(row.isBuy() ? 'B' : 'S').append(';');
        }
        return sb.toString();
    }

    private static Map<Integer, Deque<Lot>> replayOldestFirst(List<GeHistoryRow> newestFirst,
                                                              IntUnaryOperator unnote) {
        Map<Integer, Deque<Lot>> positions = new LinkedHashMap<>();
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            GeHistoryRow row = newestFirst.get(i);
            if (row == null || skipRow(row)) {
                continue;
            }
            int itemId = unnote.applyAsInt(row.getItemId());
            if (itemId <= 0 || itemId == COINS || itemId == PLATINUM) {
                continue;
            }
            if (row.isBuy()) {
                long unit = Math.max(1L, row.getPrice());
                positions.computeIfAbsent(itemId, k -> new ArrayDeque<>())
                        .add(new Lot(itemId, row.getQuantity(), unit));
            } else {
                consumeSell(positions, itemId, row.getQuantity());
            }
        }
        return positions;
    }

    static boolean skipRow(GeHistoryRow row) {
        if (row.getItemId() <= 0 || row.getQuantity() <= 0 || row.getPrice() <= 0L) {
            return true;
        }
        String name = row.getName();
        if (name == null || name.isEmpty()) {
            return false;
        }
        return name.toLowerCase(Locale.ROOT).contains("(noted)");
    }

    private static void consumeSell(Map<Integer, Deque<Lot>> positions, int itemId, int qty) {
        Deque<Lot> lots = positions.get(itemId);
        int remaining = qty;
        while (remaining > 0 && lots != null && !lots.isEmpty()) {
            Lot lot = lots.peekFirst();
            int take = Math.min(remaining, lot.qty);
            if (take == lot.qty) {
                lots.pollFirst();
            } else {
                lots.pollFirst();
                lots.addFirst(new Lot(itemId, lot.qty - take, lot.unit));
            }
            remaining -= take;
        }
        if (lots != null && lots.isEmpty()) {
            positions.remove(itemId);
        }
    }

    /** Drop {@code skipQty} from the newest remaining lots (live tracker covers those). */
    static List<Lot> peelNewest(Deque<Lot> oldestFirst, long skipQty) {
        if (oldestFirst == null || oldestFirst.isEmpty()) {
            return Collections.emptyList();
        }
        List<Lot> lots = new ArrayList<>(oldestFirst);
        long skip = Math.max(0L, skipQty);
        for (int i = lots.size() - 1; i >= 0 && skip > 0L; i--) {
            Lot lot = lots.get(i);
            if (lot.qty <= skip) {
                skip -= lot.qty;
                lots.remove(i);
            } else {
                lots.set(i, new Lot(lot.itemId, lot.qty - (int) skip, lot.unit));
                skip = 0L;
            }
        }
        return lots;
    }

    private static long getQty(Map<Integer, Long> map, int itemId) {
        if (map == null) {
            return 0L;
        }
        Long v = map.get(itemId);
        return v == null || v < 0L ? 0L : v;
    }
}
