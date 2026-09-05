package com.runeassist.flip;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bob Barter decants every potion carried, so the instruction has to account for the rest. */
public class RuneAssistSuggestionSourceTest {

    private static Set<String> carrying(String... families) {
        return new LinkedHashSet<>(Arrays.asList(families));
    }

    @Test
    public void instructionJustDecantsWhenNothingElseIsCarried() {
        for (Set<String> carried : Arrays.asList(
                Collections.<String>emptySet(), carrying("Super strength"))) {
            String msg = RuneAssistSuggestionSource.decantInstruction("Super strength", carried, 4);
            assertTrue(msg.startsWith("Carry only the Super strength"), msg);
            assertTrue(msg.contains("Decant -> 4 dose"), msg);
            assertFalse(msg.contains("Bank your"), msg);
        }
    }

    @Test
    public void instructionLeadsWithBankingTheOthers() {
        String msg = RuneAssistSuggestionSource.decantInstruction(
                "Super strength", carrying("Super strength", "Prayer potion", "Super restore"), 4);
        assertTrue(msg.startsWith("Bank your Prayer potion, Super restore first"), msg);
        // The family being decanted is never listed as something to bank.
        assertFalse(msg.startsWith("Bank your Super strength"), msg);
        assertTrue(msg.contains("every potion you are carrying"), msg);
        assertTrue(msg.contains("Carry only the Super strength"), msg);
    }

    @Test
    public void instructionSummarisesWhenCarryingManyPotions() {
        String msg = RuneAssistSuggestionSource.decantInstruction("Super strength",
                carrying("Prayer potion", "Super restore", "Antifire potion", "Ranging potion", "Stamina potion"), 4);
        assertTrue(msg.contains("and 2 more"), msg);
    }
}
