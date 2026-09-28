package com.runeassist.flip.ui;

import com.runeassist.flip.model.ComposeSuggestionResponse;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OfferHealthOverlayTest {
    private static ComposeSuggestionResponse.OfferHealthDto dto(String verdict, Double gap, long vol, Double eta, boolean buy) {
        ComposeSuggestionResponse.OfferHealthDto d = new ComposeSuggestionResponse.OfferHealthDto();
        d.setVerdict(verdict);
        d.setGapPct(gap);
        d.setVolPerHour(vol);
        d.setEtaHours(eta);
        d.setBuy(buy);
        return d;
    }

    @Test
    void labelsAndColoursFollowTheVerdict() {
        assertEquals("Too high by 2.8%", OfferHealthOverlay.label(dto("above-market", 2.8, 21, null, false)));
        assertEquals("Too low by 3.6%", OfferHealthOverlay.label(dto("above-market", 3.6, 16, null, true)));
        assertEquals("Too high by 12%", OfferHealthOverlay.label(dto("above-market", 12.4, 0, null, false)));
        assertEquals("Should fill in ~24 min", OfferHealthOverlay.label(dto("on-track", 0.0, 22579, 0.4, false)));
        assertEquals("Should fill in ~12h", OfferHealthOverlay.label(dto("on-track", 0.0, 9, 12.0, false)));
        assertEquals("Filling", OfferHealthOverlay.label(dto("filling", 0.0, 9, 12.0, false)));
        assertEquals("No buyers right now", OfferHealthOverlay.label(dto("thin", 0.0, 0, null, false)));
        assertEquals("No sellers right now", OfferHealthOverlay.label(dto("thin", 0.0, 0, null, true)));
        assertEquals("one two three</br>four", OfferHealthOverlay.wrap("one two three four", 13));
        assertEquals("Done", OfferHealthOverlay.label(dto("complete", null, 0, null, false)));
        assertEquals("", OfferHealthOverlay.label(dto("unknown", null, 0, null, false)));
        Color above = OfferHealthOverlay.color(dto("above-market", 1.0, 1, null, false));
        Color ok = OfferHealthOverlay.color(dto("on-track", 0.0, 1, null, false));
        Color thin = OfferHealthOverlay.color(dto("thin", 0.0, 0, null, false));
        assertNotEquals(above, ok);
        assertNotEquals(ok, thin);
    }
}
