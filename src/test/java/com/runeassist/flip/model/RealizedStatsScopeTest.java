package com.runeassist.flip.model;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RealizedStatsScopeTest {
    @Test void accountAndCloseTimeFilterProfitAndOpenCapitalDoesNotDiluteRoi() {
        FlipManager manager = new FlipManager(null);
        manager.mergeFlips(new ArrayList<>(Arrays.asList(
                flip(1, 100, 200, 100, 20), flip(2, 100, 200, 200, 40),
                flip(1, 180, 0, 100_000, 0), flip(1, 100, 120, 100, 30))), 0);
        manager.setIntervalStartTime(150);
        manager.setIntervalAccount(1);
        Stats stats = manager.getRealizedIntervalStats();
        assertEquals(1, stats.flipsMade);
        assertEquals(20, stats.profit);
        assertEquals(100, stats.gross);
        assertEquals(0.2, stats.calculateRoi(), 0.0001);
        manager.setIntervalAccount(null);
        assertEquals(60, manager.getRealizedIntervalStats().profit);
    }

    private FlipV2 flip(int account, int opened, int closed, long spent, long profit) {
        FlipV2 flip = new FlipV2();
        flip.setId(UUID.randomUUID()); flip.setItemId(4151); flip.setAccountId(account);
        flip.setOpenedTime(opened); flip.setClosedTime(closed); flip.setSpent(spent); flip.setProfit(profit);
        flip.setOpenedQuantity(1); flip.setClosedQuantity(closed == 0 ? 0 : 1);
        flip.setStatus(closed == 0 ? FlipStatus.BUYING : FlipStatus.FINISHED);
        flip.setPortfolioId(1); flip.setUpdatedTime(200); flip.setSeqNo(1);
        return flip;
    }
}
