package com.kindlerss.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small in-memory fixed-window rate limiter. Good enough to blunt brute-force and
 * signup abuse on a single instance; a distributed store would be needed to scale
 * horizontally (out of scope while the app runs as one replica).
 *
 * <p>Every entry carries its own expiry, so callers with different windows cannot
 * evict each other's counters early. The map is bounded: once it holds
 * {@link #DEFAULT_MAX_ENTRIES} keys the least recently used one is dropped, so a
 * flood of distinct keys costs a constant amount of memory and work per hit.
 */
@Component
public class RateLimiter {

    static final int DEFAULT_MAX_ENTRIES = 10_000;
    /** How many hits pass between sweeps of expired entries. */
    private static final int SWEEP_EVERY = 256;

    private static final class Window {
        final long expiresAtMillis;
        int count;

        Window(long expiresAtMillis) {
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private final Clock clock;
    private final Map<String, Window> windows;
    private int hitsSinceSweep;

    @Autowired
    public RateLimiter() {
        this(Clock.systemUTC(), DEFAULT_MAX_ENTRIES);
    }

    RateLimiter(Clock clock, int maxEntries) {
        this.clock = clock;
        this.windows = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Window> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /**
     * Records a hit for {@code key} and returns true when it is still within the
     * allowed number of hits for the current window.
     */
    public synchronized boolean tryAcquire(String key, int maxHits, Duration window) {
        long now = clock.millis();
        long windowMillis = Math.max(1_000, window.toMillis());
        if (++hitsSinceSweep >= SWEEP_EVERY) {
            hitsSinceSweep = 0;
            sweepExpired(now);
        }
        Window current = windows.get(key);
        if (current == null || now >= current.expiresAtMillis) {
            current = new Window(now + windowMillis);
            windows.put(key, current);
        }
        current.count++;
        return current.count <= maxHits;
    }

    synchronized int size() {
        return windows.size();
    }

    private void sweepExpired(long now) {
        Iterator<Window> it = windows.values().iterator();
        while (it.hasNext()) {
            if (now >= it.next().expiresAtMillis) {
                it.remove();
            }
        }
    }
}
