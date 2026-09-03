package com.runeassist.flip;

import com.runeassist.flip.model.GeHistoryRow;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HeldCostHistoryBackfillTest {

    private static final int WHIP = 4151;
    private static final int DUST = 3325;

    @Test
    public void untrackedBuyBecomesALot() {
        HeldCostHistoryBackfill.Result r = HeldCostHistoryBackfill.lotsToAdd(
                newestFirst(buy(WHIP, 10, 1000)),
                Collections.emptyMap(), Collections.emptyMap(), null, null);
        assertTrue(r.changed);
        assertEquals(1, r.lots.size());
        assertEquals(WHIP, r.lots.get(0).itemId);
        assertEquals(10, r.lots.get(0).qty);
        assertEquals(1000L, r.lots.get(0).unit);
    }

    @Test
    public void sellConsumesEarlierBuy() {
        // Widget is newest-first: sell, then the older buy.
        HeldCostHistoryBackfill.Result r = HeldCostHistoryBackfill.lotsToAdd(
                newestFirst(sell(WHIP, 4, 1200), buy(WHIP, 10, 1000)),
                Collections.emptyMap(), Collections.emptyMap(), null, null);
        assertEquals(1, r.lots.size());
        assertEquals(6, r.lots.get(0).qty);
        assertEquals(1000L, r.lots.get(0).unit);
    }

    @Test
    public void fullySoldBuyAddsNothing() {
        HeldCostHistoryBackfill.Result r = HeldCostHistoryBackfill.lotsToAdd(
                newestFirst(sell(WHIP, 10, 1200), buy(WHIP, 10, 1000)),
                Collections.emptyMap(), Collections.emptyMap(), null, null);
        assertTrue(r.changed);
        assertTrue(r.lots.isEmpty());
    }

    @Test
    public void sellWithoutBuyDoesNotInventALot() {
        HeldCostHistoryBackfill.Result r = HeldCostHistoryBackfill.lotsToAdd(
                newestFirst(sell(WHIP, 10, 1200)),
                Collections.emptyMap(), Collections.emptyMap(), null, null);
        assertTrue(r.lots.isEmpty());
    }

    @Test
    public void peelsNewestLotsAlreadyInTheTracker() {
        // History remaining: 10@1000 then 10@1100. Tracker already has the newer 10.
        HeldCostHistoryBackfill.Result r = HeldCostHistoryBackfill.lotsToAdd(
                newestFirst(buy(WHIP, 10, 1100), buy(WHIP, 10, 1000)),
                qty(WHIP, 10L), Collections.emptyMap(), null, null);
        assertEquals(1, r.lots.size());
        assertEquals(10, r.lots.get(0).qty);
        assertEquals(1000L, r.lots.get(0).unit);
    }

    @Test
    public void liveFillingBuyIsNotPeeledFromHistory() {
        // History remaining 20. Tracker has 10 of those plus 5 in-progress fills.
        Map<Integer, Long> held = qty(WHIP, 15L);
        Map<Integer, Long> filling = qty(WHIP, 5L);
        HeldCostHistoryBackfill.Result r = HeldCostHistoryBackfill.lotsToAdd(
                newestFirst(buy(WHIP, 20, 1000)),
                held, filling, null, null);
        assertEquals(1, r.lots.size());
        assertEquals(10, r.lots.get(0).qty);
        assertEquals(1000L, r.lots.get(0).unit);
    }

    @Test
    public void liveFillingOnlyDoesNotBlockUntrackedHistoryBuy() {
        Map<Integer, Long> held = qty(WHIP, 5L);
        Map<Integer, Long> filling = qty(WHIP, 5L);
        HeldCostHistoryBackfill.Result r = HeldCostHistoryBackfill.lotsToAdd(
                newestFirst(buy(WHIP, 10, 1000)),
                held, filling, null, null);
        assertEquals(1, r.lots.size());
        assertEquals(10, r.lots.get(0).qty);
        assertEquals(1000L, r.lots.get(0).unit);
    }

    @Test
    public void notedRowsAreSkipped() {
        GeHistoryRow noted = new GeHistoryRow(WHIP, 10, 1000, true, "Abyssal whip (noted)", null);
        HeldCostHistoryBackfill.Result r = HeldCostHistoryBackfill.lotsToAdd(
                newestFirst(noted, buy(DUST, 20, 250)),
                Collections.emptyMap(), Collections.emptyMap(), null, null);
        assertEquals(1, r.lots.size());
        assertEquals(DUST, r.lots.get(0).itemId);
        assertEquals(20, r.lots.get(0).qty);
    }

    @Test
    public void sameFingerprintIsANoOp() {
        List<GeHistoryRow> rows = newestFirst(buy(WHIP, 10, 1000));
        HeldCostHistoryBackfill.Result first = HeldCostHistoryBackfill.lotsToAdd(
                rows, Collections.emptyMap(), Collections.emptyMap(), null, null);
        HeldCostHistoryBackfill.Result second = HeldCostHistoryBackfill.lotsToAdd(
                rows, Collections.emptyMap(), Collections.emptyMap(), first.fingerprint, null);
        assertTrue(first.changed);
        assertFalse(second.changed);
        assertTrue(second.lots.isEmpty());
    }

    @Test
    public void unnoteMergesNotedIdIntoTradeable() {
        GeHistoryRow notedId = new GeHistoryRow(WHIP + 1, 10, 1000, true, "Abyssal whip", null);
        HeldCostHistoryBackfill.Result r = HeldCostHistoryBackfill.lotsToAdd(
                newestFirst(notedId),
                Collections.emptyMap(), Collections.emptyMap(), null, id -> id == WHIP + 1 ? WHIP : id);
        assertEquals(WHIP, r.lots.get(0).itemId);
    }

    @Test
    public void coinsAreNotBackfilled() {
        GeHistoryRow coins = new GeHistoryRow(995, 1000, 1, true, "Coins", null);
        HeldCostHistoryBackfill.Result r = HeldCostHistoryBackfill.lotsToAdd(
                newestFirst(coins),
                Collections.emptyMap(), Collections.emptyMap(), null, null);
        assertTrue(r.lots.isEmpty());
    }

    private static List<GeHistoryRow> newestFirst(GeHistoryRow... rows) {
        return Arrays.asList(rows);
    }

    private static GeHistoryRow buy(int itemId, int qty, long price) {
        return new GeHistoryRow(itemId, qty, price, true, "Item", null);
    }

    private static GeHistoryRow sell(int itemId, int qty, long price) {
        return new GeHistoryRow(itemId, qty, price, false, "Item", null);
    }

    private static Map<Integer, Long> qty(int itemId, long n) {
        Map<Integer, Long> m = new HashMap<>();
        m.put(itemId, n);
        return m;
    }
}
