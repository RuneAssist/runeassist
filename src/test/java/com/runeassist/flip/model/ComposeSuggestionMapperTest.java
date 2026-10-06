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

    @Test public void sendsSellOnlyModeAsAnExplicitBoolean() {
        ComposeSuggestionRequest request = new ComposeSuggestionRequest();
        for (boolean enabled : new boolean[]{false, true, false}) {
            request.setSellOnlyMode(enabled);
            com.google.gson.JsonElement flag = gson.toJsonTree(request).getAsJsonObject().get("sellOnlyMode");
            assertTrue(flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean());
            assertEquals(enabled, flag.getAsBoolean());
        }
    }

    @Test public void preservesForecastBasisAndOriginalExitWithoutInventingLegacyFields() {
        String json = "{\"ok\":true,\"suggestion\":{\"type\":\"buy\",\"expectedProfit\":21000,"
                + "\"profitEstimateBasis\":\"heuristic_net\",\"potentialProfitGp\":24000,"
                + "\"targetSellPrice\":1234,\"targetSellTaxGp\":24,\"profitModelVersion\":\"quote-net-v1\"}}";
        Suggestion suggestion = ComposeSuggestionMapper.toSuggestion(gson.fromJson(json, ComposeSuggestionResponse.class));
        assertEquals("heuristic_net", suggestion.getProfitEstimateBasis());
        assertEquals(24000.0, suggestion.getPotentialProfitGp());
        assertEquals(1234L, suggestion.getTargetSellPrice());
        assertEquals(24L, suggestion.getTargetSellTaxGp());
        assertEquals("quote-net-v1", suggestion.getProfitModelVersion());
        Suggestion legacy = ComposeSuggestionMapper.toSuggestion(gson.fromJson(
                "{\"ok\":true,\"suggestion\":{\"type\":\"buy\"}}", ComposeSuggestionResponse.class));
        assertNull(legacy.getTargetSellPrice());
        assertNull(legacy.getProfitEstimateBasis());
    }

    @Test
    public void mapsBuySuggestionFromJson()
    {
        String json = "{"
            + "\"ok\":true,"
            + "\"source\":\"ares\","
            + "\"suggestionId\":\"11111111-1111-1111-1111-111111111111\","
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
        assertEquals("11111111-1111-1111-1111-111111111111", s.getServerSuggestionId());
        assertNull(s.getGraphData());
    }

    @Test
    public void mapsBundledGraphOntoSuggestion()
    {
        String json = "{"
            + "\"ok\":true,"
            + "\"source\":\"ares\","
            + "\"suggestion\":{"
            + "\"type\":\"buy\","
            + "\"boxId\":0,"
            + "\"itemId\":4151,"
            + "\"price\":100,"
            + "\"quantity\":1,"
            + "\"name\":\"Abyssal whip\""
            + "},"
            + "\"graph\":{"
            + "\"itemId\":4151,"
            + "\"buyPrice\":90,"
            + "\"sellPrice\":110,"
            + "\"lowLatestTimes\":[1,2],"
            + "\"lowLatestPrices\":[90,91]"
            + "}"
            + "}";
        ComposeSuggestionResponse parsed = gson.fromJson(json, ComposeSuggestionResponse.class);
        Suggestion s = ComposeSuggestionMapper.toSuggestion(parsed);
        assertNotNull(s);
        assertNotNull(s.getGraphData());
        assertEquals(4151, s.getGraphData().itemId);
        assertEquals(90L, s.getGraphData().buyPrice);
        assertEquals(110L, s.getGraphData().sellPrice);
        assertNotNull(s.getGraphData().lowLatestTimes);
        assertEquals(2, s.getGraphData().lowLatestTimes.length);
    }

    @Test
    public void requestSerializesIncludeGraph()
    {
        ComposeSuggestionRequest req = new ComposeSuggestionRequest();
        req.setCapital(1_000_000L);
        req.setIncludeGraph(false);
        String json = gson.toJson(req);
        assertTrue(json.contains("\"includeGraph\":false"));
        ComposeSuggestionRequest roundTrip = gson.fromJson(json, ComposeSuggestionRequest.class);
        assertFalse(roundTrip.isIncludeGraph());
    }

    @Test
    public void requestRequiresExplicitContributionAndSerializesLinkedAccountWhenEnabled()
    {
        ComposeSuggestionRequest req = new ComposeSuggestionRequest();
        assertFalse(req.isContributeTrainingData());
        req.setContributeTrainingData(true);
        req.setOsrsAccountId("22222222-2222-2222-2222-222222222222");
        ComposeSuggestionRequest roundTrip = gson.fromJson(gson.toJson(req), ComposeSuggestionRequest.class);
        assertTrue(roundTrip.isContributeTrainingData());
        assertEquals("22222222-2222-2222-2222-222222222222", roundTrip.getOsrsAccountId());
    }

    @Test
    public void offerSnapshotSerializesSuggestionOrigin()
    {
        ComposeSuggestionRequest req = new ComposeSuggestionRequest();
        ComposeSuggestionRequest.OfferSnapshot offer = new ComposeSuggestionRequest.OfferSnapshot();
        offer.setSlot(1);
        offer.setItemId(4151);
        offer.setSuggestionId("11111111-1111-1111-1111-111111111111");
        offer.setOrigin("runeassist");
        req.getOffers().add(offer);
        String json = gson.toJson(req);
        assertTrue(json.contains("\"origin\":\"runeassist\""));
        assertTrue(json.contains("\"suggestionId\":\"11111111-1111-1111-1111-111111111111\""));
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
    public void retiredAgedOfferPreferencesAreIgnoredOnLoadAndNotSent()
    {
        String legacy = "{\"timeBasedAbortEnabled\":true,\"timeBasedAbortMinutes\":30,"
                + "\"timeframe\":120,\"riskLevel\":\"HIGH\"}";
        AccountSuggestionPreferences prefs = gson.fromJson(legacy, AccountSuggestionPreferences.class);
        assertEquals(120, prefs.getTimeframe());
        assertEquals(RiskLevel.HIGH, prefs.getRiskLevel());
        assertFalse(gson.toJson(prefs).contains("timeBasedAbort"));
        ComposeSuggestionRequest req = gson.fromJson(legacy, ComposeSuggestionRequest.class);
        req.setClientDeviceId("synthetic-device");
        String json = gson.toJson(req);
        assertFalse(json.contains("timeBasedAbort"));
        assertEquals("synthetic-device", gson.fromJson(json, ComposeSuggestionRequest.class).getClientDeviceId());
    }

    @Test
    public void requestSerializesSchemaTwoWithoutClientState()
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
        req.getModifyDismissedMs().put("4151", 654321L);

        String json = gson.toJson(req);
        assertTrue(json.contains("\"capital\":5000000"));
        assertTrue(json.contains("\"itemId\":4151"));
        assertTrue(json.contains("\"schema\":2"));
        for (String serverOwned : new String[]{"held", "usedBuyLimit", "remainingBuyLimit", "listedMs", "lastProgressMs", "lastPriceChangeMs"}) {
            assertFalse(json.contains("\"" + serverOwned + "\""), serverOwned + " is the server's to decide");
        }
        assertTrue(json.contains("\"modifyDismissedMs\":{\"4151\":654321}"));
        ComposeSuggestionRequest roundTrip = gson.fromJson(json, ComposeSuggestionRequest.class);
        assertEquals(5_000_000L, roundTrip.getCapital());
        assertEquals(1, roundTrip.getOffers().size());
        assertEquals(4151, roundTrip.getOffers().get(0).getItemId());
        assertTrue(roundTrip.getOffers().get(0).isBuy());
        assertFalse(roundTrip.isF2pOnly());
    }
}
