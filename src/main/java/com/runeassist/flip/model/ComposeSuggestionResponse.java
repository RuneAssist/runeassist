package com.runeassist.flip.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Successful body from Ares {@code POST /v1/suggestion}. {@link #suggestion} is required when
 * {@link #ok} is true; clients map it onto the local {@link Suggestion} model.
 */
@Getter
@Setter
public class ComposeSuggestionResponse
{
    private boolean ok;
    /** Ranker / compose provenance, e.g. {@code ares}. */
    private String source = "";
    private SuggestionDto suggestion;
    private String error;

    /**
     * Wire form of {@link Suggestion} for compose. Types are lowercase
     * ({@link SuggestionType#apiValue()}): buy, sell, abort, modify_buy, modify_sell, wait, decant.
     */
    @Getter
    @Setter
    public static class SuggestionDto
    {
        private String type;
        private int boxId = -1;
        private int itemId;
        private long price;
        private int quantity;
        private String name = "";
        private String message = "";
        private String why = "";
        private Double expectedProfit;
        /** Expected fill duration in <em>seconds</em> (same as local {@link Suggestion}). */
        private Double expectedDuration;
        private int geLimit;
        /** Remaining 4h buy-limit; {@code -1} if unknown. */
        private int remainingLimit = -1;
        private boolean limitKnown;
        private List<String> flags = new ArrayList<>();
    }
}
