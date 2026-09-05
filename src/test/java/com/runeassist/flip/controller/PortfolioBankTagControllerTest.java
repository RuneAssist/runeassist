package com.runeassist.flip.controller;

import com.runeassist.flip.model.BankState;
import com.runeassist.flip.model.PortfolioItemCardData;
import com.runeassist.flip.model.PortfolioState;
import com.runeassist.flip.model.PortfolioSummaryData;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Local Bank Tags portfolio membership — items that are both in the portfolio bank
 * quantity and present in the observed bank snapshot.
 */
public class PortfolioBankTagControllerTest {

    private static final int WHIP = 4151;
    private static final int ANTIFIRE = 2452;

    @Test
    public void selectsOnlyBankedPortfolioItems() {
        Map<Integer, PortfolioItemCardData> cards = new HashMap<>();
        // portfolio qty 10, none on GE/inv, suggestion bank 10 → banked
        cards.put(WHIP, card(WHIP, /*ge*/0, /*inv*/0, /*sugBank*/10, /*portfolio*/10));
        // portfolio qty all on GE → not banked
        cards.put(ANTIFIRE, card(ANTIFIRE, /*ge*/50, /*inv*/0, /*sugBank*/0, /*portfolio*/50));

        Map<Integer, Integer> bank = new HashMap<>();
        bank.put(WHIP, 10);
        bank.put(ANTIFIRE, 50);

        Set<Integer> selected = PortfolioBankTagController.selectBankedPortfolioItemIds(
                new PortfolioState(true, cards, emptySummary()),
                new BankState(true, bank, 1L),
                id -> id);

        assertEquals(Collections.singleton(WHIP), selected);
    }

    @Test
    public void emptyWhenBankOrPortfolioNotLoaded() {
        assertTrue(PortfolioBankTagController.selectBankedPortfolioItemIds(
                PortfolioState.empty(),
                new BankState(true, Collections.singletonMap(WHIP, 1), 1L),
                id -> id).isEmpty());

        assertTrue(PortfolioBankTagController.selectBankedPortfolioItemIds(
                new PortfolioState(true, Collections.emptyMap(), emptySummary()),
                BankState.empty(),
                id -> id).isEmpty());
    }

    @Test
    public void excludesPortfolioItemMissingFromBankSnapshot() {
        Map<Integer, PortfolioItemCardData> cards = new HashMap<>();
        cards.put(WHIP, card(WHIP, 0, 0, 5, 5));

        Set<Integer> selected = PortfolioBankTagController.selectBankedPortfolioItemIds(
                new PortfolioState(true, cards, emptySummary()),
                new BankState(true, Collections.emptyMap(), 1L),
                id -> id);

        assertTrue(selected.isEmpty());
    }

    private static PortfolioItemCardData card(int itemId, int ge, int inv, int sugBank, int portfolio) {
        return new PortfolioItemCardData(
                itemId,
                "item-" + itemId,
                ge,
                inv,
                sugBank,
                /*openFlips*/0,
                /*postTaxSell*/100,
                /*unitBuy*/90,
                /*unrealized*/10L,
                /*heldMinutes*/0,
                portfolio);
    }

    private static PortfolioSummaryData emptySummary() {
        return new PortfolioSummaryData(0L, 0L, 0L, 0L, 0L);
    }
}
