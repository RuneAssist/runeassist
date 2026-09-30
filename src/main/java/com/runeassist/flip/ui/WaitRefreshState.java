package com.runeassist.flip.ui;

import com.runeassist.flip.model.Suggestion;
import java.util.Objects;

final class WaitRefreshState {
    private Suggestion displayed;
    private Long account;

    synchronized void showing(Suggestion suggestion, Long accountHash) {
        clear();
        if (suggestion != null && suggestion.isWaitSuggestion() && accountHash != null) {
            displayed = suggestion;
            account = accountHash;
        }
    }

    synchronized boolean canKeepWhileRefreshing(Suggestion current, Long accountHash) {
        return displayed != null && displayed == current && current.isWaitSuggestion()
                && accountHash != null && Objects.equals(account, accountHash);
    }

    synchronized void clear() {
        displayed = null;
        account = null;
    }
}
