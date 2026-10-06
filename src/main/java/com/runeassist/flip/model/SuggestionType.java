package com.runeassist.flip.model;

import java.util.*;

public enum SuggestionType {
    BUY(1),
    SELL(2),
    ABORT(3),
    MODIFY_BUY(4),
    MODIFY_SELL(5),
    WAIT(6),
    DECANT(7);

    private final int protoInt;

    SuggestionType(int protoInt) {
        this.protoInt = protoInt;
    }

    public int protoInt() {
        return protoInt;
    }

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return apiValue();
    }

}
