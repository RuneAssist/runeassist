package com.runeassist.flip.model;

import java.util.*;

public enum SuggestionType {
    BUY,
    SELL,
    ABORT,
    MODIFY_BUY,
    MODIFY_SELL,
    WAIT,
    DECANT;

    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return apiValue();
    }

}
