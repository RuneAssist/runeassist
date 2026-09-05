package com.runeassist.flip.model;

import lombok.Data;

@Data
public class AccountSuggestionPreferences {
    public int timeframe = 5;
    public boolean buyAndHold = true;
    public boolean f2pOnlyMode = false;
    public RiskLevel riskLevel = RiskLevel.MEDIUM;
    public Integer reservedSlots = null;
    public boolean receiveDumpSuggestions = false;
    public Long minPredictedProfit = SuggestionPreferencesManager.DEFAULT_MIN_PREDICTED_PROFIT;
    public Long dumpMinPredictedProfit = null;
    public String selectedProfile = null;
    /**
     * Opt-in: when a live offer is older than {@link #timeBasedAbortMinutes} and the
     * market has moved away, compose may ABORT/MODIFY. Default off (conservative).
     */
    public boolean timeBasedAbortEnabled = false;
    /** Minutes before age policy may reprice/abort (default 15). */
    public int timeBasedAbortMinutes = SuggestionPreferencesManager.DEFAULT_TIME_BASED_ABORT_MINUTES;
}
