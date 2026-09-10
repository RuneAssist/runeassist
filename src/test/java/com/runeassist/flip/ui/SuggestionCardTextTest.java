package com.runeassist.flip.ui;

import org.junit.jupiter.api.Test;
import java.awt.Font;
import javax.swing.JLabel;
import static org.junit.jupiter.api.Assertions.*;

class SuggestionCardTextTest {
    @Test void cardTextHasExplicitReadableSizesEvenWithATinyInheritedFont() {
        JLabel label = new JLabel("Waiting for a suitable flip");
        label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 8));
        SuggestionCardText.styleText(label, false);
        assertEquals(14, label.getFont().getSize());
        assertFalse(label.getFont().isBold());
        SuggestionCardText.styleText(label, true);
        assertEquals(16, label.getFont().getSize());
        assertTrue(label.getFont().isBold());
    }

    @Test void profitAndSizingReasonsStayOutOfTheMainCard() {
        assertEquals("Waiting for a suitable flip", SuggestionCardText.waitStatus(
                "Available safe batches fall below the 20,000 gp profit target."));
        assertEquals("Waiting for a suitable flip", SuggestionCardText.waitStatus(
                "No safely sized batch fits the available slot budget and liquidity."));
        assertEquals("Waiting for a suitable flip", SuggestionCardText.waitStatus(null));
    }

    @Test void actionableWaitStatesRemainDistinct() {
        assertEquals("Waiting for offers to fill", SuggestionCardText.waitStatus("All GE slots are full."));
        assertEquals("More coins needed", SuggestionCardText.waitStatus("Not enough coins for the next flip."));
        assertEquals("Waiting for buy limits", SuggestionCardText.waitStatus("Buy limits exhausted."));
        assertEquals("Connection unavailable", SuggestionCardText.waitStatus("Ares is unreachable — no flip candidates."));
        assertEquals("Waiting for fresh prices", SuggestionCardText.waitStatus("Market data is temporarily unavailable."));
    }

    @Test void detailsAreRetainedEscapedAndDeduplicated() {
        assertEquals("Reason<br>4/8 slots", SuggestionCardText.details("Reason", "4/8 slots"));
        assertEquals("Reason", SuggestionCardText.details("Reason", "Reason"));
        assertEquals("&lt;html&gt; &amp;", SuggestionCardText.details(null, "<html> &"));
        assertEquals("", SuggestionCardText.details(null, null));
    }
}
