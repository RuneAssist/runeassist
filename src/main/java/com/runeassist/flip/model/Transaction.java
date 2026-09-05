package com.runeassist.flip.model;

import lombok.*;

import java.time.Instant;
import java.util.UUID;


@Getter
@AllArgsConstructor
@NoArgsConstructor
@Setter
public class Transaction {

    private UUID id;
    private OfferStatus type;
    private int itemId;
    private long price;
    private int quantity;
    private int boxId;
    private long amountSpent;
    private Instant timestamp;
    private boolean copilotPriceUsed;
    private boolean wasCopilotSuggestion;
    private boolean login;
    private boolean consistent;

    public boolean equals(Transaction other) {
        return this.type == other.type &&
                this.itemId == other.itemId &&
                this.price == other.price &&
                this.quantity == other.quantity &&
                this.boxId == other.boxId &&
                this.amountSpent == other.amountSpent;
    }

        @Override
    public String toString() {
        return String.format("%s %d %d on slot %d", type, quantity, itemId, boxId);
    }
}
