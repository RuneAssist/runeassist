package com.runeassist.flip.model;

import lombok.Value;

@Value
public class HeldItemSyncState {
    int delayUntilTick;

    public static HeldItemSyncState empty() {
        return new HeldItemSyncState(0);
    }
}
