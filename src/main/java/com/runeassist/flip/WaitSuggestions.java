package com.runeassist.flip;

import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.model.SuggestionType;

/**
 * Soft-fail WAIT cards when Ares compose is unreachable or the panel must yield
 * (e.g. Hub Flipping Copilot conflict). Not a local composition engine.
 */
final class WaitSuggestions
{
    static final String WAIT_SLOTS_FULL =
        "All GE slots are full. Wait for a fill, or modify a mispriced offer.";
    static final String WAIT_NO_MARGIN =
        "No clean margin — nothing passed the filters.";
    static final String WAIT_NO_CANDIDATES =
        "Ares returned no flip candidates.";
    static final String WAIT_ARES_DOWN =
        "Ares is unreachable — no flip candidates.";

    private WaitSuggestions()
    {
    }

    /** WAIT with reason + {@code used/max} slot status in {@code why}. */
    static Suggestion waitFallback(String message, long[][] offersBySlot, int maxSlots)
    {
        Suggestion wait = new Suggestion();
        wait.setType(SuggestionType.WAIT);
        wait.setBoxId(-1);
        wait.setName("");
        wait.setMessage(message == null || message.isEmpty() ? WAIT_NO_MARGIN : message);
        int cap = maxSlots > 0 ? maxSlots : 8;
        int used = 0;
        if (offersBySlot != null)
        {
            for (long[] o : offersBySlot)
            {
                if (o != null)
                {
                    used++;
                }
            }
        }
        wait.setWhy(used + "/" + cap + " slots");
        return wait;
    }
}
