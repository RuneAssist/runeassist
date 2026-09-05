package com.runeassist.flip.model;

import com.runeassist.flip.util.ProfitCalculator;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.util.*;

@Slf4j
@Data
public class FlipV2 {

    private UUID id;
    private int accountId;
    private int itemId;
    private int openedTime;
    private int openedQuantity;
    private long spent;
    private int closedTime;
    private int closedQuantity;
    private long receivedPostTax;
    private long profit;
    private long taxPaid;
    private FlipStatus status;
    private int updatedTime;
    private boolean deleted;
    private int portfolioId;
    private long seqNo;
    private int userId;

    private String cachedItemName;

    public FlipV2 setCachedItemName(String cachedItemName) {
        this.cachedItemName = cachedItemName;
        return this;
    }

    public long calculateProfit(Transaction transaction) {
        long amountToClose = Math.min(openedQuantity - closedQuantity, transaction.getQuantity());
        if(amountToClose <= 0 ){
            return 0;
        }
        long gpOut = (spent * amountToClose) / openedQuantity;
        long sellPrice = transaction.getAmountSpent() / transaction.getQuantity();
        long sellPricePostTax = ProfitCalculator.getPostTaxPrice(transaction.getItemId(), sellPrice);
        long gpIn = amountToClose * sellPricePostTax;
        return gpIn - gpOut;
    }

    public long getAvgBuyPrice() {
        if (spent == 0) {
            return 0;
        }
        return spent / openedQuantity ;
    }

    public long getAvgSellPrice() {
        if (receivedPostTax == 0) {
            return 0;
        }
        return (receivedPostTax  + taxPaid) / closedQuantity;
    }

                private static UUID decodeUuid(byte[] raw) {
        if (raw == null || raw.length != 16) {
            return null;
        }
        // Read UUID (16 bytes)
        ByteBuffer b = ByteBuffer.wrap(raw);
        return new UUID(b.getLong(), b.getLong());
    }

    public boolean isClosed() {
        return Objects.equals(status, FlipStatus.FINISHED);
    }

    public int lastTransactionTime() {
        return closedTime == 0 ? openedTime : closedTime;
    }

    public boolean isNewer(FlipV2 o) {
        if (updatedTime == o.updatedTime) {
            return closedQuantity > o.closedQuantity || (closedQuantity == o.closedQuantity && openedQuantity >= o.openedQuantity);
        }
        return updatedTime > o.updatedTime;
    }
}
