package com.runeassist.flip;

import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.model.SuggestionType;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deliberate price-offset ladder experiments for building ML training data on fill
 * probability/time (see docs/... discussion -- price offset from market is the key feature
 * we can't reconstruct retroactively, so a few controlled probes are worth far more per
 * attempt than incidental variation in organic flips).
 *
 * <p><b>Gated to specific RSNs, hardcoded, no UI toggle for anyone else.</b> This
 * deliberately suggests off-market prices that lose expected value relative to a normal
 * flip -- fine for the account owner who asked for it and knows why, not something that
 * should ever surface for another user of this plugin. See {@link #isAllowed}.
 *
 * <p><b>Still just a suggestion.</b> Exactly like every other suggestion in this codebase,
 * this never places or confirms a GE offer itself -- the player still manually acts on it
 * via the game's own UI for every single rung. "Automatic" here means the ladder math and
 * rung advancement are automatic, not that any game input is.
 *
 * <p>One experiment active at a time, one rung live at a time. A rung is considered
 * resolved (advance to the next, or finish if it was the last) when {@link
 * #onOfferResolved} sees the tracked item+side reach a terminal offer state -- see
 * {@code RuneAssistPlugin.onGrandExchangeOfferChanged}, which calls it.
 */
@Slf4j
@Singleton
public class ExperimentService
{
    private static final Set<String> ALLOWED_RSNS = Set.of("bof118", "coldtyres");

    private static final double[] BUY_OFFSETS = {0.0, -0.01, -0.03, -0.05};
    private static final double[] SELL_OFFSETS = {0.0, 0.01, 0.03, 0.05};

    public static final class Experiment
    {
        public final int itemId;
        public final String itemName;
        public final boolean buy;
        public final int qty;
        public final long startedAt = System.currentTimeMillis();
        public int rung = 0;

        Experiment(int itemId, String itemName, boolean buy, int qty)
        {
            this.itemId = itemId;
            this.itemName = itemName;
            this.buy = buy;
            this.qty = qty;
        }

        public double[] offsets() { return buy ? BUY_OFFSETS : SELL_OFFSETS; }
        boolean isLastRung() { return rung >= offsets().length - 1; }
    }

    private final Map<String, Experiment> active = new ConcurrentHashMap<>();

    public static boolean isAllowed(String displayName)
    {
        return displayName != null && ALLOWED_RSNS.contains(displayName.trim().toLowerCase(Locale.ROOT));
    }

    public boolean hasActive(String displayName)
    {
        return displayName != null && active.containsKey(key(displayName));
    }

    public Experiment get(String displayName)
    {
        return displayName == null ? null : active.get(key(displayName));
    }

    /** Starts a new ladder, replacing any experiment already running for this account. */
    public void start(String displayName, int itemId, String itemName, boolean buy, int qty)
    {
        if (!isAllowed(displayName) || qty <= 0)
        {
            return;
        }
        active.put(key(displayName), new Experiment(itemId, itemName, buy, qty));
        log.info("experiment started: acct={} item={} buy={} qty={}", displayName, itemName, buy, qty);
    }

    public void stop(String displayName)
    {
        if (displayName != null)
        {
            active.remove(key(displayName));
        }
    }

    /**
     * Call from the GE-offer-changed handler for every offer state change. If it matches the
     * active experiment's item+side and has reached a terminal state, advances to the next
     * rung (or ends the experiment if that was the last one).
     */
    public void onOfferResolved(String displayName, int itemId, boolean buySide, String state)
    {
        Experiment e = get(displayName);
        if (e == null || e.itemId != itemId || e.buy != buySide)
        {
            return;
        }
        boolean terminal = "BOUGHT".equals(state) || "SOLD".equals(state)
                || "CANCELLED_BUY".equals(state) || "CANCELLED_SELL".equals(state);
        if (!terminal)
        {
            return;
        }
        if (e.isLastRung())
        {
            log.info("experiment finished: acct={} item={} rungs={}", displayName, e.itemName, e.offsets().length);
            stop(displayName);
        }
        else
        {
            e.rung++;
            log.info("experiment advanced: acct={} item={} rung={}/{}", displayName, e.itemName, e.rung + 1, e.offsets().length);
        }
    }

    /**
     * Builds the current rung's suggestion from a live market quote, or null if there's no
     * active experiment, no live price, or the previous rung's offer is still open (an active
     * offer already covers this rung -- nothing new to suggest). Caller (RuneAssistSuggestionSource)
     * is expected to check {@link #hasActive} first and give this priority over normal scoring,
     * same pattern as the decant check.
     */
    public Suggestion buildSuggestion(String displayName, FlipScorer flipScorer, boolean hasOpenOfferForItem)
    {
        Experiment e = get(displayName);
        if (e == null || hasOpenOfferForItem)
        {
            return null;
        }
        Map<String, Object> q = flipScorer.quote(e.itemId);
        if (q == null || !(q.get(e.buy ? "buy_at" : "sell_at") instanceof Number))
        {
            return null;
        }
        long market = ((Number) q.get(e.buy ? "buy_at" : "sell_at")).longValue();
        double offset = e.offsets()[e.rung];
        long price = Math.max(1, Math.round(market * (1.0 + offset)));

        Suggestion s = new Suggestion();
        s.setType(e.buy ? SuggestionType.BUY : SuggestionType.SELL);
        s.setItemId(e.itemId);
        s.setId(e.itemId);
        s.setName(e.itemName);
        s.setPrice(price);
        s.setQuantity(e.qty);
        s.setExpectedProfit(0.0); // not a real flip -- the point is the data, not the margin
        s.setWhy(String.format(Locale.ROOT,
                "[EXPERIMENT %d/%d] %s at %+.0f%% vs market (%,d gp) — price-offset ladder, not a real flip suggestion",
                e.rung + 1, e.offsets().length, e.buy ? "Buy" : "Sell", offset * 100, price));
        return s;
    }

    private static String key(String displayName)
    {
        return displayName.trim().toLowerCase(Locale.ROOT);
    }
}
