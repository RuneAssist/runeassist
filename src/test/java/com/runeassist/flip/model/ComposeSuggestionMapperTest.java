package com.runeassist.flip.model;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ComposeSuggestionMapperTest
{
    private final Gson gson = new Gson();

    @Test
    public void mapsBuySuggestionFromJson()
    {
        String json = "{"
            + "\"ok\":true,"
            + "\"source\":\"ares\","
            + "\"suggestion\":{"
            + "\"type\":\"buy\","
            + "\"boxId\":2,"
            + "\"itemId\":4151,"
            + "\"price\":12000000,"
            + "\"quantity\":1,"
            + "\"name\":\"Abyssal whip\","
            + "\"why\":\"Buy 1 Abyssal whip at 12m\","
            + "\"expectedProfit\":50000.0,"
            + "\"expectedDuration\":600.0,"
            + "\"geLimit\":70,"
            + "\"remainingLimit\":69,"
            + "\"limitKnown\":true,"
            + "\"flags\":[\"thin\"]"
            + "}}";
        ComposeSuggestionResponse parsed = gson.fromJson(json, ComposeSuggestionResponse.class);
        Suggestion s = ComposeSuggestionMapper.toSuggestion(parsed);
        assertNotNull(s);
        assertEquals(SuggestionType.BUY, s.getType());
        assertEquals(2, s.getBoxId());
        assertEquals(4151, s.getItemId());
        assertEquals(4151, s.getId());
        assertEquals(12_000_000L, s.getPrice());
        assertEquals(1, s.getQuantity());
        assertEquals("Abyssal whip", s.getName());
        assertEquals(50_000.0, s.getExpectedProfit(), 0.01);
        assertEquals(600.0, s.getExpectedDuration(), 0.01);
        assertEquals(70, s.getGeLimit());
        assertEquals(69, s.getRemainingLimit());
        assertTrue(s.isLimitKnown());
        assertEquals(Collections.singletonList("thin"), s.getFlags());
        assertEquals("ares", s.getPickSource());
    }

    @Test
    public void acceptsModifyBuySnakeAndWait()
    {
        assertEquals(SuggestionType.MODIFY_BUY, ComposeSuggestionMapper.parseType("modify_buy"));
        assertEquals(SuggestionType.MODIFY_BUY, ComposeSuggestionMapper.parseType("MODIFY_BUY"));
        assertEquals(SuggestionType.WAIT, ComposeSuggestionMapper.parseType("wait"));
        assertNull(ComposeSuggestionMapper.parseType("nope"));
    }

    @Test
    public void rejectsUnusableResponses()
    {
        assertNull(ComposeSuggestionMapper.toSuggestion((ComposeSuggestionResponse) null));
        ComposeSuggestionResponse bad = new ComposeSuggestionResponse();
        bad.setOk(false);
        assertNull(ComposeSuggestionMapper.toSuggestion(bad));
        bad.setOk(true);
        assertNull(ComposeSuggestionMapper.toSuggestion(bad));
    }

    @Test
    public void requestSerializesOffersAndHeld()
    {
        ComposeSuggestionRequest req = new ComposeSuggestionRequest();
        req.setCapital(5_000_000L);
        req.setRisk("medium");
        ComposeSuggestionRequest.OfferSnapshot offer = new ComposeSuggestionRequest.OfferSnapshot();
        offer.setSlot(1);
        offer.setItemId(4151);
        offer.setBuy(true);
        offer.setPrice(11_000_000L);
        offer.setSold(0);
        offer.setTotal(1);
        offer.setFilling(true);
        req.getOffers().add(offer);
        ComposeSuggestionRequest.HeldSnapshot held = new ComposeSuggestionRequest.HeldSnapshot();
        held.setItemId(561);
        held.setQty(100);
        held.setAvgBuy(180);
        req.getHeld().add(held);

        String json = gson.toJson(req);
        assertTrue(json.contains("\"capital\":5000000"));
        assertTrue(json.contains("\"itemId\":4151"));
        assertTrue(json.contains("\"avgBuy\":180"));
        ComposeSuggestionRequest roundTrip = gson.fromJson(json, ComposeSuggestionRequest.class);
        assertEquals(5_000_000L, roundTrip.getCapital());
        assertEquals(1, roundTrip.getOffers().size());
        assertEquals(4151, roundTrip.getOffers().get(0).getItemId());
        assertTrue(roundTrip.getOffers().get(0).isBuy());
        assertEquals(1, roundTrip.getHeld().size());
        assertEquals(180L, roundTrip.getHeld().get(0).getAvgBuy());
        assertFalse(roundTrip.isF2pOnly());
    }
}
