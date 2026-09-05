package com.runeassist.flip.model;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class VisualizeFlipResponseTest {

    private final Gson gson = new Gson();

    @Test
    public void fromJsonReadsLotsAndGraph() {
        String raw = "{"
                + "\"buyTimes\":[100,110],"
                + "\"buyVolumes\":[5,5],"
                + "\"buyPrices\":[1000,1010],"
                + "\"sellTimes\":[200],"
                + "\"sellVolumes\":[10],"
                + "\"sellPrices\":[1200],"
                + "\"graphData\":{\"itemId\":4151,\"high1hTimes\":[1],\"high1hPrices\":[10]}"
                + "}";
        JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
        VisualizeFlipResponse r = VisualizeFlipResponse.fromJson(o, gson);
        assertArrayEquals(new int[]{100, 110}, r.buyTimes);
        assertArrayEquals(new int[]{5, 5}, r.buyVolumes);
        assertArrayEquals(new long[]{1000, 1010}, r.buyPrices);
        assertArrayEquals(new int[]{200}, r.sellTimes);
        assertArrayEquals(new int[]{10}, r.sellVolumes);
        assertArrayEquals(new long[]{1200}, r.sellPrices);
        assertNotNull(r.graphData);
        assertEquals(4151, r.graphData.itemId);
        assertNull(r.message);
    }

    @Test
    public void fromJsonReadsMessageWhenNoGraph() {
        JsonObject o = JsonParser.parseString(
                "{\"buyTimes\":[],\"sellTimes\":[],\"message\":\"No price data available for this item.\"}"
        ).getAsJsonObject();
        VisualizeFlipResponse r = VisualizeFlipResponse.fromJson(o, gson);
        assertEquals("No price data available for this item.", r.message);
        assertNull(r.graphData);
        assertEquals(0, r.buyTimes.length);
    }

    @Test
    public void fromLocalLotsFallsBackToFlipAggregates() {
        FlipV2 flip = new FlipV2();
        flip.setId(UUID.randomUUID());
        flip.setOpenedTime(50);
        flip.setOpenedQuantity(3);
        flip.setSpent(300);
        flip.setClosedTime(90);
        flip.setClosedQuantity(3);
        flip.setReceivedPostTax(280);
        flip.setTaxPaid(20);
        VisualizeFlipResponse r = VisualizeFlipResponse.fromLocalLots(null, flip, Collections.emptyList());
        assertArrayEquals(new int[]{50}, r.buyTimes);
        assertArrayEquals(new int[]{3}, r.buyVolumes);
        assertArrayEquals(new long[]{100}, r.buyPrices);
        assertArrayEquals(new int[]{90}, r.sellTimes);
        assertArrayEquals(new int[]{3}, r.sellVolumes);
        assertArrayEquals(new long[]{100}, r.sellPrices);
    }
}
