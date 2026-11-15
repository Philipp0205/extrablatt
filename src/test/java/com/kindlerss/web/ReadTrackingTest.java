package com.kindlerss.web;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadTrackingTest {

    @Test
    void everyCombinationOfTheTwoFlagsMapsToAMode() {
        assertEquals(ReadTracking.OPEN, ReadTracking.fromFlags(true, false));
        assertEquals(ReadTracking.SCROLL, ReadTracking.fromFlags(true, true));
        assertEquals(ReadTracking.NONE, ReadTracking.fromFlags(false, false));
    }

    @Test
    void trackingOffWithScrollOnIsShownAsNone() {
        assertEquals(ReadTracking.NONE, ReadTracking.fromFlags(false, true));
    }

    @Test
    void parametersParseToTheirModes() {
        assertEquals(Optional.of(ReadTracking.OPEN), ReadTracking.fromParameter("open"));
        assertEquals(Optional.of(ReadTracking.SCROLL), ReadTracking.fromParameter("scroll"));
        assertEquals(Optional.of(ReadTracking.NONE), ReadTracking.fromParameter("none"));
    }

    @Test
    void nullAndUnknownParametersAreEmpty() {
        assertTrue(ReadTracking.fromParameter(null).isEmpty());
        assertTrue(ReadTracking.fromParameter("").isEmpty());
        assertTrue(ReadTracking.fromParameter("OPEN").isEmpty());
        assertTrue(ReadTracking.fromParameter("sideways").isEmpty());
    }

    @Test
    void parametersRoundTrip() {
        for (ReadTracking mode : ReadTracking.values()) {
            assertEquals(Optional.of(mode), ReadTracking.fromParameter(mode.parameter()));
        }
    }

    @Test
    void eachModeMapsBackToFlagsThatGiveTheSameMode() {
        for (ReadTracking mode : ReadTracking.values()) {
            assertEquals(mode, ReadTracking.fromFlags(mode.track(), mode.scroll()));
        }
    }

    @Test
    void modesStoreTheDocumentedFlags() {
        assertTrue(ReadTracking.OPEN.track());
        assertFalse(ReadTracking.OPEN.scroll());
        assertTrue(ReadTracking.SCROLL.track());
        assertTrue(ReadTracking.SCROLL.scroll());
        assertFalse(ReadTracking.NONE.track());
        assertFalse(ReadTracking.NONE.scroll());
    }
}
