package com.kindlerss.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    /** A clock the test can move forward by hand. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void allowsUpToTheLimitAndThenRefusesUntilTheWindowEnds() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(clock, 100);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("k", 3, Duration.ofMinutes(5))).isTrue();
        }
        assertThat(limiter.tryAcquire("k", 3, Duration.ofMinutes(5))).isFalse();

        clock.advance(Duration.ofMinutes(5));
        assertThat(limiter.tryAcquire("k", 3, Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void aShortWindowCallerDoesNotResetSomeoneElsesLongWindow() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(clock, 10_000);

        assertThat(limiter.tryAcquire("long", 1, Duration.ofHours(1))).isTrue();
        clock.advance(Duration.ofMinutes(10));
        // Hits on other keys with a short window used to sweep with their own window,
        // throwing away the one-hour counter after five minutes.
        for (int i = 0; i < 1_000; i++) {
            limiter.tryAcquire("short-" + i, 10, Duration.ofMinutes(5));
        }
        assertThat(limiter.tryAcquire("long", 1, Duration.ofHours(1))).isFalse();
    }

    @Test
    void floodingWithDistinctKeysStaysBounded() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(clock, 50);

        for (int i = 0; i < 10_000; i++) {
            limiter.tryAcquire("flood-" + i, 10, Duration.ofMinutes(5));
        }
        assertThat(limiter.size()).isLessThanOrEqualTo(50);
    }

    @Test
    void expiredEntriesAreSweptAway() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(clock, 10_000);

        for (int i = 0; i < 100; i++) {
            limiter.tryAcquire("old-" + i, 10, Duration.ofMinutes(1));
        }
        clock.advance(Duration.ofMinutes(2));
        for (int i = 0; i < 300; i++) {
            limiter.tryAcquire("fresh", 1_000, Duration.ofMinutes(1));
        }
        assertThat(limiter.size()).isEqualTo(1);
    }
}
