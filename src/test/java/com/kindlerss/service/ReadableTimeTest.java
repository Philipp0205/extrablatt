package com.kindlerss.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReadableTimeTest {

    private static final Instant NOW = Instant.parse("2026-03-14T12:00:00Z");

    private final ReadableTime time = new ReadableTime(ZoneOffset.UTC);

    @Test
    void freshnessIsSaidRatherThanSpelledOutInDigits() {
        assertEquals("just now", time.describe(NOW.minusSeconds(20), NOW));
        assertEquals("1 minute ago", time.describe(NOW.minusSeconds(90), NOW));
        assertEquals("40 minutes ago", time.describe(NOW.minus(40, ChronoUnit.MINUTES), NOW));
        assertEquals("1 hour ago", time.describe(NOW.minus(1, ChronoUnit.HOURS), NOW));
        assertEquals("5 hours ago", time.describe(NOW.minus(5, ChronoUnit.HOURS), NOW));
        assertEquals("yesterday", time.describe(NOW.minus(30, ChronoUnit.HOURS), NOW));
        assertEquals("4 days ago", time.describe(NOW.minus(4, ChronoUnit.DAYS), NOW));
    }

    @Test
    void olderThanAWeekIsADate() {
        assertEquals("14 February 2026", time.describe(NOW.minus(28, ChronoUnit.DAYS), NOW));
    }

    @Test
    void aMissingOrFutureDateSaysNothingStrange() {
        assertEquals("", time.describe(null, NOW));
        assertEquals("just now", time.describe(NOW.plusSeconds(600), NOW));
    }
}
