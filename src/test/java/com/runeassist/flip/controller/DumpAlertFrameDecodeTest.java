package com.runeassist.flip.controller;

import com.google.gson.Gson;
import com.runeassist.flip.model.Suggestion;
import com.runeassist.flip.model.SuggestionType;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DumpAlertFrameDecodeTest {

    private final ApiRequestHandler handler = new ApiRequestHandler(new OkHttpClient(), new Gson());

    @Test
    public void decodesComposeShapedDumpFrame() {
        String json = "{"
                + "\"suggestion\":{"
                + "\"type\":\"buy\","
                + "\"itemId\":11934,"
                + "\"price\":6500,"
                + "\"quantity\":100,"
                + "\"name\":\"Dark crab\","
                + "\"message\":\"DUMP\","
                + "\"why\":\"Dump: 5m low 7.1% under 1h book\","
                + "\"expectedProfit\":200000,"
                + "\"expectedDuration\":1800,"
                + "\"geLimit\":10000,"
                + "\"flags\":[\"dump\"]"
                + "}}";
        Suggestion s = handler.decodeDumpSuggestionFrame(json.getBytes(StandardCharsets.UTF_8));
        assertNotNull(s);
        assertEquals(SuggestionType.BUY, s.getType());
        assertEquals(11934, s.getItemId());
        assertEquals(6500, s.getPrice());
        assertEquals(100, s.getQuantity());
        assertEquals("Dark crab", s.getName());
        assertEquals("ares-dump", s.getPickSource());
        assertTrue(s.getFlags().contains("dump"));
    }

    @Test
    public void emptyOrInvalidPayloadReturnsNull() {
        assertNull(handler.decodeDumpSuggestionFrame(new byte[0]));
        assertNull(handler.decodeDumpSuggestionFrame("{}".getBytes(StandardCharsets.UTF_8)));
        assertNull(handler.decodeDumpSuggestionFrame("not-json".getBytes(StandardCharsets.UTF_8)));
    }
}
