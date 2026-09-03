package com.runeassist.flip.rs;

import com.runeassist.flip.config.RuneAssistConfig;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class RuneAssistConfigRS extends ReactiveStateImpl<RuneAssistConfig> {

    @Inject
    public RuneAssistConfigRS(RuneAssistConfig config) {
        super(config);
        registerListener(current -> log.debug("RuneAssistConfigRS changed"));
    }
}
