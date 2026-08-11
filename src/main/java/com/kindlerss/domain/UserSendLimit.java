package com.kindlerss.domain;

import java.time.Instant;

/** Optional per-account override for Kindle delivery throttling. */
public record UserSendLimit(
        long userId,
        Integer maxSendsPerDay,
        Instant blockedUntil
) {
    public boolean blocked(Instant now) {
        return blockedUntil != null && blockedUntil.isAfter(now);
    }
}
