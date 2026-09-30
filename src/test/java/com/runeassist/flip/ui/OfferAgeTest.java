package com.runeassist.flip.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OfferAgeTest {

    private static final long MINUTE = 60_000L;

    @Test
    void formatsAgeByScale() {
        assertEquals("under 1m", OfferAge.formatAge(59_000L, 1L));
        assertEquals("12m", OfferAge.formatAge(12 * MINUTE + 30_000L, 1L));
        assertEquals("1h05m", OfferAge.formatAge(65 * MINUTE, 1L));
        assertEquals("2d3h", OfferAge.formatAge((51 * 60 + 10) * MINUTE, 1L));
    }

    @Test
    void unknownListingTimeShowsNothing() {
        assertNull(OfferAge.formatAge(5 * MINUTE, 0L));
        assertNull(OfferAge.formatAge(-1L, 1L));
    }

    @Test
    void expectedTimeShowsOnlyWhenTheCardGaveOne() {
        assertEquals("50m", OfferAge.formatExpected(3000L));
        assertEquals("2h40m", OfferAge.formatExpected(9600L));
        assertEquals("1m", OfferAge.formatExpected(20L));
        assertNull(OfferAge.formatExpected(0L));
    }

    @Test
    void tooltipLineShowsAgeAgainstTheEstimate() {
        assertEquals("Listed under 1m ago, expected 1h40m", OfferAge.line("under 1m", 6000L));
        assertEquals("Listed 12m ago", OfferAge.line("12m", 0L));
        assertNull(OfferAge.line(null, 6000L));
    }
}
