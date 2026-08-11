package com.kindlerss.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-memory fixed-window rate limiter. Good enough to blunt brute-force and
 * signup abuse on a single instance; a distributed store would be needed to scale
 * horizontally (out of scope while the app runs as one replica).
 */
@Component
public class RateLimiter {

    private record Window(long windowStartEpochSec, int count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * Records a hit for {@code key} and returns true when it is still within the
     * allowed number of hits for the current window.
     */
    public boolean tryAcquire(String key, int maxHits, Duration window) {
        long now = Instant.now().getEpochSecond();
        long windowSeconds = Math.max(1, window.toSeconds());
        Window updated = windows.compute(key, (ignored, existing) -> {
            if (existing == null || now - existing.windowStartEpochSec() >= windowSeconds) {
                return new Window(now, 1);
            }
            return new Window(existing.windowStartEpochSec(), existing.count() + 1);
        });
        // Opportunistic cleanup so the map does not grow without bound.
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> now - e.getValue().windowStartEpochSec() >= windowSeconds);
        }
        return updated.count() <= maxHits;
    }
}
