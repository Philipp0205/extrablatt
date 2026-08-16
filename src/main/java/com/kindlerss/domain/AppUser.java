package com.kindlerss.domain;

import java.time.Instant;

/**
 * A registered account. Feeds and articles are owned through {@code id}.
 * {@code newsletterInboundToken}, when set, is the local part of this account's
 * one shared newsletter inbox address (see {@code app.newsletters.*}); it is
 * generated lazily on first use rather than at registration.
 */
public record AppUser(
        Long id,
        String email,
        String passwordHash,
        String kindleEmail,
        Instant emailVerifiedAt,
        Instant disabledAt,
        Instant createdAt,
        Instant updatedAt,
        String newsletterInboundToken
) {
    public boolean emailVerified() {
        return emailVerifiedAt != null;
    }

    public boolean enabled() {
        return disabledAt == null;
    }

    public AppUser(Long id, String email, String passwordHash, String kindleEmail,
                   Instant emailVerifiedAt, Instant disabledAt, Instant createdAt, Instant updatedAt) {
        this(id, email, passwordHash, kindleEmail, emailVerifiedAt, disabledAt, createdAt, updatedAt, null);
    }
}
