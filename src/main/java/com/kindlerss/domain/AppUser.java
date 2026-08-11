package com.kindlerss.domain;

import java.time.Instant;

/** A registered account. Feeds and articles are owned through {@code id}. */
public record AppUser(
        Long id,
        String email,
        String passwordHash,
        String kindleEmail,
        Instant emailVerifiedAt,
        Instant disabledAt,
        Instant createdAt,
        Instant updatedAt
) {
    public boolean emailVerified() {
        return emailVerifiedAt != null;
    }

    public boolean enabled() {
        return disabledAt == null;
    }
}
