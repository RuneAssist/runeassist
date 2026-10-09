package com.runeassist.flip;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HeldSellValueTest {
    private static long[] sell(int itemId, long price, long sold, long total) {
        return new long[]{ itemId, 0, price, sold, total, 1, 0, 0, 0 };
    }

    private static long[] buy(int itemId, long price, long bought, long total) {
        return new long[]{ itemId, 1, price, bought, total, 1, 0, 0, 0 };
    }

    @Test
    void unlistedStockIsMarkedAtTheGivenPriceNetOfTax() {
        assertEquals(5_633_040L, RuneAssistSuggestionSource.heldSellValue(28997, 1, 5_748_000L, null));
        assertEquals(5_633_040L, RuneAssistSuggestionSource.heldSellValue(28997, 1, 5_748_000L, new long[8][]));
    }

    @Test
    void listedStockIsMarkedAtItsOwnAskNetOfTax() {
        long[][] offers = { sell(28997, 5_891_000L, 0, 1) };
        assertEquals(5_773_180L, RuneAssistSuggestionSource.heldSellValue(28997, 1, 5_748_000L, offers));
    }

    @Test
    void onlyTheUnsoldPartOfASellOfferCountsAndTheRestUsesTheMark() {
        long[][] offers = { sell(213, 2_114L, 1_000, 1_909) };
        assertEquals(909L * 2_072L, RuneAssistSuggestionSource.heldSellValue(213, 909, 2_101L, offers));
        assertEquals(909L * 2_072L + 100L * 2_059L, RuneAssistSuggestionSource.heldSellValue(213, 1_009, 2_101L, offers));
    }

    @Test
    void listedUnitsNeverExceedTheHeldQuantity() {
        long[][] offers = { sell(213, 2_114L, 0, 1_909), sell(213, 2_200L, 0, 500) };
        assertEquals(500L * 2_072L, RuneAssistSuggestionSource.heldSellValue(213, 500, 2_101L, offers));
    }

    @Test
    void buyOffersOtherItemsAndShortRowsAreIgnored() {
        long[][] offers = { buy(213, 2_114L, 10, 100), sell(1511, 2_114L, 0, 100), null, new long[]{ 213, 0, 2_114L } };
        assertEquals(10L * 2_059L, RuneAssistSuggestionSource.heldSellValue(213, 10, 2_101L, offers));
    }
}
