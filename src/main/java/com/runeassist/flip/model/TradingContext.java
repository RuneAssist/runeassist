package com.runeassist.flip.model;

import java.util.Objects;

/** Local-only suggestion session. This state is never included in network requests. */
public final class TradingContext {
    private Long accountHash;
    private boolean validLogin;
    private boolean geOpen;
    private boolean paused;
    private boolean initialized;
    private long generation;

    public synchronized boolean update(Long account, boolean valid, boolean open, boolean manuallyPaused) {
        boolean changed = !initialized || !Objects.equals(accountHash, account)
                || validLogin != valid || geOpen != open || paused != manuallyPaused;
        if (changed) {
            initialized = true;
            accountHash = account;
            validLogin = valid;
            geOpen = open;
            paused = manuallyPaused;
            generation++;
        }
        return changed;
    }

    public synchronized void invalidate() {
        initialized = false;
        validLogin = false;
        generation++;
    }

    public synchronized boolean canRequest() {
        return initialized && accountHash != null && validLogin && geOpen && !paused;
    }

    public synchronized boolean sameAccount(Long account) {
        return initialized && validLogin && Objects.equals(accountHash, account);
    }

    public synchronized boolean isAway() {
        return initialized && validLogin && !geOpen && !paused;
    }

    public synchronized long generation() { return generation; }

    public synchronized boolean accepts(long requestGeneration) {
        return canRequest() && generation == requestGeneration;
    }
}
