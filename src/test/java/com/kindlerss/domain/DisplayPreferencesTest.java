package com.kindlerss.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayPreferencesTest {

    @Test
    void theDefaultIsTheFormatThisEditionWasAskedFor() {
        DisplayPreferences defaults = DisplayPreferences.DEFAULTS;

        assertEquals(DisplayPreferences.Theme.BLACK_BRIGHT, defaults.theme());
        assertTrue(defaults.keyPointsFirst());
        assertTrue(defaults.lineSpacing() > DisplayPreferences.MIN_LINE_SPACING, "lines should start roomy");
        assertEquals("theme-black-bright size-3 lines-2 font-sans", defaults.bodyClasses());
    }

    @Test
    void settingsSurviveARoundTripThroughTheCookie() {
        DisplayPreferences chosen = new DisplayPreferences(
                DisplayPreferences.Theme.BLACK_YELLOW, 5, 3, DisplayPreferences.Font.SERIF, true, false);

        assertEquals(chosen, DisplayPreferences.decode(chosen.encode()));
        assertEquals("theme-black-yellow size-5 lines-3 font-serif letters-wide", chosen.bodyClasses());
    }

    @Test
    void adamagedValueFallsBackInsteadOfFailing() {
        // An unreadable page is worse than an unexpected one, so nothing here throws.
        assertEquals(DisplayPreferences.DEFAULTS, DisplayPreferences.decode(null));
        assertEquals(DisplayPreferences.DEFAULTS, DisplayPreferences.decode(""));
        assertEquals(DisplayPreferences.DEFAULTS, DisplayPreferences.decode("nonsense"));

        DisplayPreferences partial = DisplayPreferences.decode("black-white.9.x.serif");
        assertEquals(DisplayPreferences.Theme.BLACK_WHITE, partial.theme());
        assertEquals(DisplayPreferences.MAX_TEXT_SIZE, partial.textSize());
        assertEquals(DisplayPreferences.DEFAULTS.lineSpacing(), partial.lineSpacing());
        assertEquals(DisplayPreferences.Font.SERIF, partial.font());
        assertEquals(DisplayPreferences.DEFAULTS.keyPointsFirst(), partial.keyPointsFirst());
    }

    @Test
    void textSizeStopsAtBothEnds() {
        DisplayPreferences smallest = DisplayPreferences.DEFAULTS.withTextSize(-4);
        DisplayPreferences largest = DisplayPreferences.DEFAULTS.withTextSize(99);

        assertEquals(DisplayPreferences.MIN_TEXT_SIZE, smallest.textSize());
        assertFalse(smallest.canShrink());
        assertTrue(smallest.canGrow());
        assertEquals(DisplayPreferences.MAX_TEXT_SIZE, largest.textSize());
        assertFalse(largest.canGrow());
        assertTrue(largest.canShrink());
    }
}
