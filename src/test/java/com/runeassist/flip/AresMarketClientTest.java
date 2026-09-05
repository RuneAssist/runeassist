package com.runeassist.flip;

import com.google.gson.Gson;
import com.runeassist.flip.model.ComposeSuggestionRequest;
import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.model.SuggestionType;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The only scoring arithmetic left client-side: valuing a decant of stock already held.
 * Flip ranking, decant ranking and per-item health are server-side. Compose probes the
 * live Ares host once; a 404 is sticky for the JVM (endpoint not shipped yet).
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
    public void composeSuggestionReturnsNullWhenEndpointMissing() {
        AresMarketClient client = new AresMarketClient(new OkHttpClient(), new Gson());
        client.resetComposeEndpointProbeForTests();
        ComposeSuggestionRequest req = new ComposeSuggestionRequest();
        req.setCapital(1_000_000L);
        req.setTimeframeMinutes(5);
        req.setRisk("medium");
        req.setMaxSlots(8);
        req.setRemainingSlots(8);

        Suggestion first = client.composeSuggestion(req);
        // Live Ares currently 404s /v1/suggestion — client must not throw and must fall back.
        assertNull(first);
        assertFalse(client.lastFromCompose());
        assertTrue(client.composeEndpointMissing(),
            "404 should stick so we stop probing every suggestion cycle");

        // Second call skips the network after the sticky 404.
        assertNull(client.composeSuggestion(req));
        assertFalse(client.lastFromCompose());
    }

    @Test
    public void suggestionTypeApiValuesMatchComposeWireFormat() {
        assertEquals("buy", SuggestionType.BUY.apiValue());
        assertEquals("modify_sell", SuggestionType.MODIFY_SELL.apiValue());
        assertEquals("wait", SuggestionType.WAIT.apiValue());
    }
}
