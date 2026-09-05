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
    private String message = "";
    /**
     * One-line honest reason this pick was chosen, from the scorer and live client
     * state (qty, price, stamped remaining limit, fill estimate, flags). Empty for
     * WAIT — those use {@link #message}.
     */
    private String why = "";
    private Double expectedProfit;
    private Double expectedDuration;
    @SerializedName("is_hold")
    private boolean isHold;
    private Map<Integer, Integer> bankItems;
    private List<PortfolioItem> portfolioItems;
    private Data graphData;
    private Instant timeIssued;
    /** Wiki GE buy-limit; 0 if unknown. */
    private int geLimit;
    /**
     * Remaining 4h buy-limit from live fills observed this window.
     * {@code -1} if unknown — do not treat as a reconstructed full limit.
     */
    private int remainingLimit = -1;
    /**
     * True only when {@link #geLimit} is known and remaining comes from live fills
     * (or pending offers already exhaust the wiki cap). False = guessing / unknown.
     */
    private boolean limitKnown;
    /** {@code ares} or {@code local} — which scorer produced this pick. Telemetry only. */
    private String pickSource = "";
    private List<String> flags = new ArrayList<>();

    @Setter
    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PortfolioItem {
        public int itemId;

        // Per-portfolio breakdowns. Only portfolio_id 0 (COFLIP_PORTFOLIO) and 1 (PERSONAL_PORTFOLIO)
        // count as "in portfolio" (getAmount / getSellValue / getBuySpend / getHeldMinutes).
        // Ghost (portfolio_id -1) is tracked separately and excluded from those totals; it only
        // surfaces as a fallback when computing per-unit market prices for items the user holds
        // client-side but has no visible portfolio/personal entry for.
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

    public boolean isModifySuggestion() {
        return type == SuggestionType.MODIFY_BUY || type == SuggestionType.MODIFY_SELL;
    }

    /** RuneAssist-only: "go decant at a bank" — advisory, no GE offer/widget behind it. */
    public boolean isDecantSuggestion() {
        return type == SuggestionType.DECANT;
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

    /**
     * Unactioned dump must stick until Confirm or Skip. A 10s recency window let
     * {@code shouldFetchNewSuggestion} overwrite the alert when the user clicked
     * Back from a sell setup more than 10s after the dump arrived.
     */
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
            case DECANT:
                string += message != null && !message.isEmpty() ? message : "Decant " + name;
                break;
            default:
                string += "Unknown suggestion type";
                break;
        }
        return string;
    }

        private static int clampToInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }
}
