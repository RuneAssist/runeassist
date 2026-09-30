package com.runeassist.flip.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeHistoryReaderTest {
    @Test void readsThePriceTheTabShows() {
        assertEquals(10_128_288L, GeHistoryReader.parsePrice("<col=ffffff>10,128,288 coins</col>", 1));
        assertEquals(1_612L, GeHistoryReader.parsePrice("<col=ffffff>= 1,612 each</col>", 54));
        assertEquals(500L, GeHistoryReader.parsePrice("<col=ff0000>(5,000 - 4,500)</col> 4,500 coins", 10), "a struck-out total is per item");
        assertEquals(0L, GeHistoryReader.parsePrice("Cancelled", 1));
        assertEquals(0L, GeHistoryReader.parsePrice(null, 1));
    }
}
