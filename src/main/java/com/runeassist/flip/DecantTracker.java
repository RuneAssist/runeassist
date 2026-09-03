package com.runeassist.flip;

import net.runelite.api.ItemComposition;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;

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
 *
 * <p>Wiki / GE item ids are always the unnoted form. Notes collected from the GE (the usual
 * way a finished buy lands in inventory) have a different id, so the snapshot must collapse
 * noted ids onto their unnoted counterparts before {@link #phaseFor} runs — otherwise held
 * Super defence(4) notes look like "no 4s" and the suggestion stays on buy-3s-to-decant.</p>
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

    /**
     * Merge noted and unnoted stacks of the same item so dose matching uses wiki/GE ids.
     * {@code toUnnoted} maps a raw container item id to its unnoted linked id (identity if
     * the item is already unnoted).
     */
    static Map<Integer, Long> collapseToUnnoted(Map<Integer, Long> raw, IntUnaryOperator toUnnoted)
    {
        Map<Integer, Long> out = new HashMap<>();
        if (raw == null || raw.isEmpty() || toUnnoted == null) return out;
        for (Map.Entry<Integer, Long> e : raw.entrySet())
        {
            if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0L) continue;
            int id = toUnnoted.applyAsInt(e.getKey());
            if (id <= 0) continue;
            out.merge(id, e.getValue(), Long::sum);
        }
        return out;
    }

    /** Unnoted GE/wiki id for a container item, or {@code itemId} if it is not a note. */
    static int unnotedId(ItemComposition composition, int itemId)
    {
        if (composition != null && composition.getNote() != -1 && composition.getLinkedNoteId() > 0)
        {
            return composition.getLinkedNoteId();
        }
        return itemId;
    }
}
