package com.runeassist.flip.model;

import com.runeassist.flip.ui.graph.model.Data;
import com.google.gson.annotations.SerializedName;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import java.text.NumberFormat;
import java.time.Instant;
import java.util.*;

@Setter
@Getter
@AllArgsConstructor
@ToString
@NoArgsConstructor
@Slf4j
public class Suggestion {
    private SuggestionType type;
    private int boxId;
    private int itemId;
    private long price;
    private int quantity;
    private String name;
    private int id;
    private String serverSuggestionId = "";
    private String message = "";
    private String why = "";
    private Double expectedProfit;
    private String profitEstimateBasis;
    private Double potentialProfitGp;
    private Long targetSellPrice;
    private Long targetSellTaxGp;
    private String profitModelVersion;
    private Double expectedDuration;
    private Double estimatedBuyHours;
    @SerializedName("is_hold")
    private boolean isHold;
    private Map<Integer, Integer> bankItems;
    private List<PortfolioItem> portfolioItems;
    private Data graphData;
    private Instant timeIssued;
    private int geLimit;
    private int remainingLimit = -1;
    private boolean limitKnown;
    private String pickSource = "";
    private List<String> flags = new ArrayList<>();

    @Setter
    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PortfolioItem {
        public int itemId;

        public int portfolioAmount;
        public long portfolioSellValue;
        public long portfolioBuySpend;
        public int portfolioHeldMinutes;
        public int personalAmount;
        public long personalSellValue;
        public long personalBuySpend;
        public int personalHeldMinutes;
        public int ghostAmount;
        public long ghostSellValue;
        public long ghostBuySpend;
        public int ghostHeldMinutes;

        public int getAmount() {
            return portfolioAmount + personalAmount;
        }

        public long getSellValue() {
            return portfolioSellValue + personalSellValue;
        }

        public long getBuySpend() {
            return portfolioBuySpend + personalBuySpend;
        }

        public int getHeldMinutes() {
            return Math.max(portfolioHeldMinutes, personalHeldMinutes);
        }

        public long getPostTaxSellUnitPrice() {
            int amt = getAmount();
            if (amt > 0) {
                return getSellValue() / amt;
            }
            if (ghostAmount > 0) {
                return ghostSellValue / ghostAmount;
            }
            return 0L;
        }

        public long getUnitBuyPrice() {
            int amt = getAmount();
            if (amt > 0) {
                return getBuySpend() / amt;
            }
            if (ghostAmount > 0) {
                return ghostBuySpend / ghostAmount;
            }
            return 0L;
        }

            }

    public volatile Instant dumpAlertReceived = Instant.now();
    public volatile boolean isDumpAlert;
    public volatile int actionedTick = -1;


    public boolean equals(Suggestion other) {
        return this.type == other.type
                && this.itemId == other.itemId
                && this.name.equals(other.name);
    }

    public boolean isWaitSuggestion() {
        return type == SuggestionType.WAIT;
    }

    public boolean isAbortSuggestion() {
        return type == SuggestionType.ABORT;
    }

    public boolean isBuySuggestion() {
        return type == SuggestionType.BUY || type == SuggestionType.MODIFY_BUY;
    }

    public boolean isSellSuggestion() {
        return type == SuggestionType.SELL || type == SuggestionType.MODIFY_SELL;
    }

    public long offerFillSeconds() {
        if (type == SuggestionType.BUY && estimatedBuyHours != null && estimatedBuyHours > 0) {
            return Math.round(estimatedBuyHours * 3600);
        }
        if (type == SuggestionType.SELL && expectedDuration != null && expectedDuration > 0) {
            return Math.round(expectedDuration);
        }
        return 0L;
    }

    public boolean isModifySuggestion() {
        return type == SuggestionType.MODIFY_BUY || type == SuggestionType.MODIFY_SELL;
    }

    public String offerType() {
        if (isBuySuggestion()) {
            return "buy";
        }
        if (isSellSuggestion()) {
            return "sell";
        }
        return null;
    }

    public boolean matchesOffer(String confirmedOfferType, int confirmedItemId) {
        String side = offerType();
        return side != null && side.equals(confirmedOfferType) && itemId == confirmedItemId;
    }

    public boolean isRecentUnActionedDumpAlert() {
        return isDumpAlert && actionedTick == -1;
    }

    public boolean isBuyDumpSuggestion() {
        return isDumpAlert && type == SuggestionType.BUY;
    }

    public String toMessage() {
        NumberFormat formatter = NumberFormat.getNumberInstance();
        String string = isDumpAlert ? "DUMP ALERT!! " : "RuneAssist: ";
        if (type == null) {
            return string + "Unknown suggestion type";
        }
        switch (type) {
            case BUY:
                string += String.format("%s %s %s for %s gp",
                        isHold ? "Buy and hold" : "Buy",
                        formatter.format(quantity), name, formatter.format(price));
                break;
            case MODIFY_BUY:
                string += String.format("Modify buy offer for %s %s to %s gp",
                        formatter.format(quantity), name, formatter.format(price));
                break;
            case SELL:
                string += String.format("Sell %s %s for %s gp",
                        formatter.format(quantity), name, formatter.format(price));
                break;
            case MODIFY_SELL:
                string += String.format("Modify sell offer for %s %s to %s gp",
                        formatter.format(quantity), name, formatter.format(price));
                break;
            case ABORT:
                string += "Abort " + name;
                break;
            case WAIT:
                string += "Wait";
                break;
            default:
                string += "Unknown suggestion type";
                break;
        }
        return string;
    }

}
