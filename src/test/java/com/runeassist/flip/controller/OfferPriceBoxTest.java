package com.runeassist.flip.controller;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OfferPriceBoxTest {
    @Test void readsTheFullFigureFromThePriceBox() {
        assertEquals(2_815_745L, GrandExchange.parseGp("2,815,745 coins"));
        assertEquals(279L, GrandExchange.parseGp("<col=ff981f>279</col> coins"));
        assertEquals(3_000_000_000L, GrandExchange.parseGp("3,000,000,000 coins"), "beyond max cash");
        assertEquals(0L, GrandExchange.parseGp(null));
        assertEquals(0L, GrandExchange.parseGp(""));
        assertEquals(0L, GrandExchange.parseGp("2.8M coins"), "an abbreviated figure is not a price");
    }
}
