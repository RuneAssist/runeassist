package com.runeassist.flip.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class PremiumInstanceController {

    public void loadAndOpenPremiumInstanceDialog() {
        log.debug("legacy copilot premium-instance dialog is disabled");
    }
}
