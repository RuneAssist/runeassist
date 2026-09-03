package com.runeassist.flip.rs;

import com.runeassist.flip.config.FlippingCopilotConfig;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class FlippingCopilotConfigRS extends ReactiveStateImpl<FlippingCopilotConfig> {

    @Inject
    public FlippingCopilotConfigRS(FlippingCopilotConfig config) {
        super(config);
        registerListener(current -> log.debug("FlippingCopilotConfigRS changed"));
    }
}
