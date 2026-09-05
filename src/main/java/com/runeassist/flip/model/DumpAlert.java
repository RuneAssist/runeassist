package com.runeassist.flip.model;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;

/**
 * One frame of the Ares dump-alert stream. Framing matches Flipping Copilot
 * ({@code uvarint} length + payload; length 0 = keepalive). Payload is UTF-8 JSON
 * {@code { "suggestion": {…compose DTO…} }} — not protobuf.
 */
public class DumpAlert {

    public Suggestion suggestion;

    public static DumpAlert decodeJson(byte[] bytes, Gson gson) {
        DumpAlert frame = new DumpAlert();
        if (bytes == null || bytes.length == 0 || gson == null) {
            return frame;
        }
        try {
            String json = new String(bytes, StandardCharsets.UTF_8);
            JsonObject root = gson.fromJson(json, JsonObject.class);
            if (root == null || !root.has("suggestion") || root.get("suggestion").isJsonNull()) {
                return frame;
            }
            ComposeSuggestionResponse.SuggestionDto dto =
                    gson.fromJson(root.get("suggestion"), ComposeSuggestionResponse.SuggestionDto.class);
            frame.suggestion = ComposeSuggestionMapper.toSuggestion(dto, "ares-dump");
        } catch (Exception ignored) {
            // malformed frame — caller logs
        }
        return frame;
    }
}
