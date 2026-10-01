package com.runeassist.flip.model;

import com.runeassist.flip.ui.graph.model.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ComposeSuggestionResponse
{
    private boolean ok;
    private String source = "";
    private String suggestionId = "";
    private SuggestionDto suggestion;
    private Data graph;
    private String error;

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
        private String profitEstimateBasis;
        private Double potentialProfitGp;
        private Long targetSellPrice;
        private Long targetSellTaxGp;
        private String profitModelVersion;
        private Double expectedDuration;
        private int geLimit;
        private int remainingLimit = -1;
        private boolean limitKnown;
        private List<String> flags = new ArrayList<>();
    }
}
