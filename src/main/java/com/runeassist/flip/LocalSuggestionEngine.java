package com.runeassist.flip;

import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.model.SuggestionType;

import java.util.List;
import java.util.Map;

/**
 * Pure selection logic that turns our market scorer output plus current account
 * state into a single Flipping Copilot {@link Suggestion}.
 *
 * <p>This is a pure function: no {@code Client}, no I/O, fully deterministic.
 * It replaces FC's server as the source of the {@code Suggestion} object that
 * drives FC's GE UI/overlays.</p>
 */
public final class LocalSuggestionEngine {

    private LocalSuggestionEngine() {
    }

    // offersBySlot entry layout: {itemId, buyIs1, price, sold, total, fillingIs1}
    private static final int O_ITEM_ID = 0;
    private static final int O_BUY_IS_1 = 1;
    private static final int O_PRICE = 2;
    private static final int O_SOLD = 3;
    private static final int O_TOTAL = 4;
    private static final int O_FILLING_IS_1 = 5;

    /**
     * Choose the next suggestion.
     *
     * @param scoredFlips  our market flips, best first (each a Map of the documented keys)
     * @param offersBySlot length 8; each entry null (empty slot) or
     *                     {itemId, buyIs1, price, sold, total, fillingIs1}
     * @param held         itemId -> {qty, avgBuy} currently held
     * @param coins        available coins
     * @param maxSlots     maximum GE slots we may use
     * @return the first applicable Suggestion by priority (MODIFY, SELL, BUY, WAIT)
     */
    public static Suggestion next(
            List<Map<String, Object>> scoredFlips,
            long[][] offersBySlot,
            Map<Integer, long[]> held,
            long coins,
            int maxSlots) {

        // 1) MODIFY: reprice any still-filling active offer that our scorer now
        //    prices differently.
        if (offersBySlot != null) {
            for (int slot = 0; slot < offersBySlot.length; slot++) {
                long[] offer = offersBySlot[slot];
                if (offer == null || offer.length < 6) {
                    continue;
                }
                if (offer[O_FILLING_IS_1] != 1L) {
                    continue;
                }
                int offerItemId = (int) offer[O_ITEM_ID];
                Map<String, Object> flip = findFlip(scoredFlips, offerItemId);
                if (flip == null) {
                    continue;
                }
                long offerPrice = offer[O_PRICE];
                int remaining = (int) Math.max(0L, offer[O_TOTAL] - offer[O_SOLD]);
                String name = getString(flip, "name");

                if (offer[O_BUY_IS_1] == 1L) {
                    long buyAt = getLong(flip, "buy_at");
                    if (offerPrice < buyAt) {
                        return build(SuggestionType.MODIFY_BUY, slot, offerItemId,
                                buyAt, remaining, name, null);
                    }
                } else {
                    long sellAt = getLong(flip, "sell_at");
                    if (offerPrice > sellAt) {
                        return build(SuggestionType.MODIFY_SELL, slot, offerItemId,
                                sellAt, remaining, name, null);
                    }
                }
            }
        }

        int freeSlot = firstFreeSlot(offersBySlot, maxSlots);
        int usedSlots = countUsedSlots(offersBySlot);

        // 2) SELL held stock: highest scored "sell" entry we hold and have no
        //    active offer for, if a usable free slot exists.
        if (scoredFlips != null && freeSlot >= 0 && held != null) {
            for (Map<String, Object> flip : scoredFlips) {
                if (flip == null) {
                    continue;
                }
                if (!"sell".equals(getString(flip, "side"))) {
                    continue;
                }
                int itemId = getInt(flip, "id");
                long[] heldEntry = held.get(itemId);
                if (heldEntry == null || heldEntry.length < 1 || heldEntry[0] <= 0L) {
                    continue;
                }
                if (hasActiveOffer(offersBySlot, itemId)) {
                    continue;
                }
                long sellAt = getLong(flip, "sell_at");
                long heldQty = heldEntry[0];
                long suggestedQty = getLong(flip, "suggested_qty");
                long qty = suggestedQty > 0 ? Math.min(suggestedQty, heldQty) : heldQty;
                if (qty <= 0) {
                    continue;
                }
                Double profit = getNullableDouble(flip, "projected_profit");
                return build(SuggestionType.SELL, freeSlot, itemId, sellAt,
                        (int) qty, getString(flip, "name"), profit);
            }
        }

        // 3) BUY: highest scored non-sell entry we neither hold nor have an
        //    active offer for, if we have slot headroom and enough coins.
        if (scoredFlips != null && freeSlot >= 0 && usedSlots < maxSlots) {
            for (Map<String, Object> flip : scoredFlips) {
                if (flip == null) {
                    continue;
                }
                if ("sell".equals(getString(flip, "side"))) {
                    continue;
                }
                int itemId = getInt(flip, "id");
                if (held != null && held.containsKey(itemId)) {
                    continue;
                }
                if (hasActiveOffer(offersBySlot, itemId)) {
                    continue;
                }
                long buyAt = getLong(flip, "buy_at");
                if (buyAt <= 0 || coins < buyAt) {
                    continue;
                }
                long suggestedQty = getLong(flip, "suggested_qty");
                long affordable = coins / buyAt;
                long qty = Math.min(suggestedQty, affordable);
                if (qty <= 0) {
                    continue;
                }
                Double profit = getNullableDouble(flip, "projected_profit");
                return build(SuggestionType.BUY, freeSlot, itemId, buyAt,
                        (int) qty, getString(flip, "name"), profit);
            }
        }

        // 4) Nothing actionable.
        Suggestion wait = new Suggestion();
        wait.setType(SuggestionType.WAIT);
        wait.setBoxId(-1);
        wait.setName("");
        wait.setMessage("No actionable flip right now.");
        return wait;
    }

    private static Suggestion build(SuggestionType type, int boxId, int itemId,
                                    long price, int quantity, String name, Double expectedProfit) {
        Suggestion s = new Suggestion();
        s.setType(type);
        s.setBoxId(boxId);
        s.setItemId(itemId);
        s.setPrice(price);
        s.setQuantity(quantity);
        s.setName(name == null ? "" : name);
        if (expectedProfit != null) {
            s.setExpectedProfit(expectedProfit);
        }
        return s;
    }

    private static Map<String, Object> findFlip(List<Map<String, Object>> scoredFlips, int itemId) {
        if (scoredFlips == null) {
            return null;
        }
        for (Map<String, Object> flip : scoredFlips) {
            if (flip != null && getInt(flip, "id") == itemId) {
                return flip;
            }
        }
        return null;
    }

    private static boolean hasActiveOffer(long[][] offersBySlot, int itemId) {
        if (offersBySlot == null) {
            return false;
        }
        for (long[] offer : offersBySlot) {
            if (offer != null && offer.length > O_ITEM_ID && (int) offer[O_ITEM_ID] == itemId) {
                return true;
            }
        }
        return false;
    }

    private static int firstFreeSlot(long[][] offersBySlot, int maxSlots) {
        if (offersBySlot == null) {
            return -1;
        }
        int limit = Math.min(maxSlots, offersBySlot.length);
        for (int i = 0; i < limit; i++) {
            if (offersBySlot[i] == null) {
                return i;
            }
        }
        return -1;
    }

    private static int countUsedSlots(long[][] offersBySlot) {
        if (offersBySlot == null) {
            return 0;
        }
        int count = 0;
        for (long[] offer : offersBySlot) {
            if (offer != null) {
                count++;
            }
        }
        return count;
    }

    private static long getLong(Map<String, Object> map, String key) {
        if (map == null) {
            return 0L;
        }
        Object v = map.get(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        return 0L;
    }

    private static int getInt(Map<String, Object> map, String key) {
        return (int) getLong(map, key);
    }

    private static Double getNullableDouble(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        return null;
    }

    private static String getString(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        return v instanceof String ? (String) v : null;
    }
}
