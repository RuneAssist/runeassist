package com.runeassist.flip.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CustomTimeParseTest {
    @Test
    void parsesCommonFormats() {
        assertEquals(90, CustomTimeParse.minutes("90m"));
        assertEquals(90, CustomTimeParse.minutes("1h 30m"));
        assertEquals(90, CustomTimeParse.minutes("1:30"));
        assertEquals(120, CustomTimeParse.minutes("2h"));
        assertEquals(45, CustomTimeParse.minutes("45"));
    }

    @Test
    void rejectsInvalid() {
        assertNull(CustomTimeParse.minutes(""));
        assertNull(CustomTimeParse.minutes("abc"));
        assertNull(CustomTimeParse.minutes("1.5m"));
        assertNull(CustomTimeParse.minutes("1:70"));
    }
}
