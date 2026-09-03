package com.runeassist.flip.controller;

import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;

/**
 * Leftover Flipping Copilot dump-alert stream. Hard-disabled: RuneAssist does
 * not subscribe to that backend. Kept as a Guice type so PreferencesPanel still
 * constructs.
 */
@Slf4j
@Singleton
public class DumpsStreamController {

    @Inject
    public DumpsStreamController() {
    }

    public void ensureUnsubscribed() {
    }
}
