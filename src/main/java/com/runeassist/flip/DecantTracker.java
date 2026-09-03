package com.runeassist.flip;

import java.util.Map;

/**
 * Live phase detection for a decant opportunity from {@link FlipScorer#topDecants()}, read
 * from an inventory+bank item-count snapshot — not {@link HeldCostTracker}'s FIFO ledger,
 * which never observes a bank decant (it only sees GE-offer fills), so it has no lot to
 * consume/produce across the dose-variant item ids involved.
 *
 * <p>Takes a plain {@code Map<Integer, Long>} rather than a {@link net.runelite.api.Client}
 * because {@code client.getItemContainer(...)} asserts it is called on the client thread, but
 * this is evaluated from the background suggestion-scoring thread — the caller must snapshot
 * the counts on the client thread first (see
 * {@code RuneAssistSuggestionSource.snapshotOwnedQty()}).</p>
 */
final class DecantTracker
{
    enum Phase
    {
        /** Not holding enough of the cheap dose yet — go buy it. */
        NEED_BUY,
        /** Holding enough of the cheap dose — go decant it at a bank. */
        NEED_DECANT,
        /** Holding the decanted (target-dose) stock — go sell it. */
        NEED_SELL
    }

    private DecantTracker()
    {
    }

    static Phase phaseFor(Map<Integer, Long> ownedQty, int buyItemId, long buyQty, int sellItemId)
    {
        if (ownedQty(ownedQty, sellItemId) > 0) return Phase.NEED_SELL;
        if (ownedQty(ownedQty, buyItemId) >= buyQty) return Phase.NEED_DECANT;
        return Phase.NEED_BUY;
    }

    static long ownedQty(Map<Integer, Long> ownedQty, int itemId)
    {
        Long qty = ownedQty.get(itemId);
        return qty != null ? qty : 0L;
    }
}
