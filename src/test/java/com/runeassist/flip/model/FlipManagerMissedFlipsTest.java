package com.runeassist.flip.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FlipManagerMissedFlipsTest {

    @Test
    public void missedFlipsTrackedSeparatelyFromPortfolio() {
        FlipManager fm = new FlipManager(null);
        fm.setPluginUserId(LocalFlipLedger.LOCAL_USER_ID);
        FlipV2 ghost = new FlipV2();
        ghost.setId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        ghost.setAccountId(7);
        ghost.setItemId(4151);
        ghost.setOpenedTime(100);
        ghost.setOpenedQuantity(5);
        ghost.setSpent(500);
        ghost.setStatus(FlipStatus.SELLING);
        ghost.setPortfolioId(PortfolioId.GHOST);
        ghost.setDeleted(false);
        ghost.setSeqNo(1);
        ghost.setUpdatedTime(100);
        ghost.setUserId(LocalFlipLedger.LOCAL_USER_ID);
        List<FlipV2> batch = new ArrayList<>();
        batch.add(ghost);
        fm.mergeFlips(batch, LocalFlipLedger.LOCAL_USER_ID);
        List<FlipV2> missed = fm.getMissedFlipsForAccount(7);
        assertEquals(1, missed.size());
        assertTrue(fm.isGhostFlip(7, ghost.getId()));
    }
}
