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
 * Compose mapping / wire-format checks. Flip ranking and typed composition are server-side;
 * the client soft-fails to WAIT when compose is unreachable (no local override path).
 */
public class AresMarketClientTest {

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
        assertEquals("decant", SuggestionType.DECANT.apiValue());
    }
}
