package com.runeassist.flip;

import net.runelite.api.ItemComposition;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Live phase detection for a decant opportunity from {@link AresMarketClient#topDecants()}, read
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
    /**
     * Whether a quantity shift looks like a decant: bottles consumed carry exactly as many
     * doses as the bottles produced. Exact match on purpose -- a partial conversion is left
     * alone rather than risk pairing an unrelated quantity change with it.
     */
    static boolean isDoseConservingShift(long buyDelta, long buyDose, long sellDelta, long sellDose)
    {
        if (buyDelta <= 0 || sellDelta <= 0 || buyDose <= 0 || sellDose <= 0) return false;
        // Same dose on both sides is not a conversion, whatever the arithmetic says: it is
        // some unrelated pair of quantity changes that happens to balance.
        if (buyDose == sellDose) return false;
        return buyDelta * buyDose == sellDelta * sellDose;
    }

    /**
     * Base name of a dose potion -- "Super strength(3)" gives "Super strength" -- or null if this
     * is not one. Used to tell which potions in the inventory a decant would sweep up: Bob Barter
     * converts every potion carried, not only the one being decanted.
     */
    static String doseFamily(String itemName)
    {
        if (itemName == null) return null;
        String name = itemName.trim();
        // Dose variants are named "X potion(N)". Parsed by hand rather than with a regex: the
        // shape is fixed and trivial, and it keeps the escaping out of it.
        if (name.length() < 4 || name.charAt(name.length() - 1) != ')') return null;
        int open = name.lastIndexOf('(');
        if (open <= 0 || open != name.length() - 3) return null;
        char digit = name.charAt(name.length() - 2);
        if (digit < '1' || digit > '9') return null;
        String family = name.substring(0, open).trim();
        return family.isEmpty() ? null : family;
    }

    static int unnotedId(ItemComposition composition, int itemId)
    {
        if (composition != null && composition.getNote() != -1 && composition.getLinkedNoteId() > 0)
        {
            return composition.getLinkedNoteId();
        }
        return itemId;
    }
}
