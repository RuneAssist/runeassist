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
        assertEquals("1h 05m", OfferAgeOverlay.formatAge(65 * MINUTE, 1L));
        assertEquals("2d 3h", OfferAgeOverlay.formatAge((51 * 60 + 10) * MINUTE, 1L));
    }

    @Test
    void unknownListingTimeShowsNothing() {
        assertNull(OfferAgeOverlay.formatAge(5 * MINUTE, 0L));
        assertNull(OfferAgeOverlay.formatAge(-1L, 1L));
    }

    @Test
    void progressShowsTheEstimateWhenTheCardGaveOne() {
        assertEquals("12m / ~50m", OfferAgeOverlay.formatProgress("12m", 3000L));
        assertEquals("1h 05m / ~2h 40m", OfferAgeOverlay.formatProgress("1h 05m", 9600L));
        assertEquals("<1m / ~1m", OfferAgeOverlay.formatProgress("<1m", 20L));
        assertEquals("12m", OfferAgeOverlay.formatProgress("12m", 0L));
        assertNull(OfferAgeOverlay.formatProgress(null, 3000L));
    }
}
