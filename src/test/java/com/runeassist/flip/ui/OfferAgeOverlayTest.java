package com.runeassist.flip.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OfferAgeOverlayTest {

    private static final long MINUTE = 60_000L;

    @Test
    void formatsAgeByScale() {
        assertEquals("<1m", OfferAgeOverlay.formatAge(59_000L, 1L));
        assertEquals("12m", OfferAgeOverlay.formatAge(12 * MINUTE + 30_000L, 1L));
        assertEquals("1h05m", OfferAgeOverlay.formatAge(65 * MINUTE, 1L));
        assertEquals("2d3h", OfferAgeOverlay.formatAge((51 * 60 + 10) * MINUTE, 1L));
    }

    @Test
    void unknownListingTimeShowsNothing() {
        assertNull(OfferAgeOverlay.formatAge(5 * MINUTE, 0L));
        assertNull(OfferAgeOverlay.formatAge(-1L, 1L));
    }

    @Test
    void expectedTimeShowsOnlyWhenTheCardGaveOne() {
        assertEquals("50m", OfferAgeOverlay.formatExpected(3000L));
        assertEquals("2h40m", OfferAgeOverlay.formatExpected(9600L));
        assertEquals("1m", OfferAgeOverlay.formatExpected(20L));
        assertNull(OfferAgeOverlay.formatExpected(0L));
    }

    @Test
    void ageIsShownAgainstTheEstimateAsATarget() {
        assertEquals("<1m / 1h40m", OfferAgeOverlay.label("<1m", 6000L));
        assertEquals("12m", OfferAgeOverlay.label("12m", 0L));
        assertNull(OfferAgeOverlay.label(null, 6000L));
    }
}
