package com.kindlerss.domain;

import java.time.Instant;

/**
 * A one-time token for e-mail verification, password reset or sign-in. For
 * {@link Purpose#SIGN_IN} the {@code token} column holds the SHA-256 hex of the
 * token; the plain token exists only in the e-mail.
 */
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
        RESET,
        SIGN_IN
    }

    public boolean usable(Instant now) {
        return usedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }
}
