package com.runeassist.flip;

import com.google.gson.Gson;
import com.runeassist.flip.model.ComposeSuggestionRequest;
import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.model.SuggestionType;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The only scoring arithmetic left client-side: valuing a decant of stock already held.
 * Flip ranking, decant ranking, per-item health, and typed composition are server-side.
 */
public class AresMarketClientTest {

    @Test
    public void decantingHeldStockBeatsSellingItAsIs() {
        // 208x Super strength(3) held: sell-as-is vs decant-to-4 then sell.
        long gain = AresMarketClient.decantGainOverRawSell(208, 3, 4, 2403, 48, 3300, 66);
        assertEquals(504_504L - 489_840L, gain);
        assertTrue(gain > 0);
    }

    @Test
    public void decantGainIsNegativeWhenConvertingLosesValue() {
        assertTrue(AresMarketClient.decantGainOverRawSell(208, 3, 4, 2403, 48, 3000, 60) < 0);
    }

    @Test
    public void decantGainFloorsPartialBottlesAndRejectsNonsense() {
        assertEquals(3 * (3300 - 66) - 5 * (2403 - 48),
            AresMarketClient.decantGainOverRawSell(5, 3, 4, 2403, 48, 3300, 66));
        assertEquals(0, AresMarketClient.decantGainOverRawSell(0, 3, 4, 2403, 48, 3300, 66));
        assertEquals(0, AresMarketClient.decantGainOverRawSell(208, 3, 0, 2403, 48, 3300, 66));
    }

    @Test
    public void composeSuggestionMapsLiveEndpoint() {
        AresMarketClient client = new AresMarketClient(new OkHttpClient(), new Gson());
        ComposeSuggestionRequest req = new ComposeSuggestionRequest();
        req.setCapital(10_000_000L);
        req.setTimeframeMinutes(5);
        req.setRisk("medium");
        req.setMembersItemsAllowed(true);
        req.setF2pOnly(false);
        req.setMaxSlots(8);
        req.setRemainingSlots(8);

        Suggestion suggestion = client.composeSuggestion(req);
        assertNotNull(suggestion, "live /v1/suggestion should return a mappable suggestion");
        assertNotNull(suggestion.getType());
        assertTrue(client.lastFromCompose());
        assertFalse(client.lastComposeUnreachable());
    }

    @Test
    public void suggestionTypeApiValuesMatchComposeWireFormat() {
        assertEquals("buy", SuggestionType.BUY.apiValue());
        assertEquals("modify_sell", SuggestionType.MODIFY_SELL.apiValue());
        assertEquals("wait", SuggestionType.WAIT.apiValue());
    }
}
