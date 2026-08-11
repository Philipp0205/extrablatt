package com.kindlerss.domain;

import java.time.Instant;

/** A one-time token for e-mail verification or password reset. */
public record EmailToken(
        String token,
        long userId,
        Purpose purpose,
        Instant expiresAt,
        Instant usedAt,
        Instant createdAt
) {
    public enum Purpose {
        VERIFY,
        RESET
    }

    public boolean usable(Instant now) {
        return usedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }
}
