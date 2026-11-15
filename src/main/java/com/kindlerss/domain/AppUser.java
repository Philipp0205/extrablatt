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
        Instant updatedAt,
        boolean markReadOnNextPage,
        String lastSeenChangelogId,
        boolean markReadOnScroll
) {
    public AppUser(Long id, String email, String passwordHash, String kindleEmail,
                   Instant emailVerifiedAt, Instant disabledAt, Instant createdAt, Instant updatedAt,
                   boolean markReadOnNextPage, String lastSeenChangelogId) {
        this(id, email, passwordHash, kindleEmail, emailVerifiedAt, disabledAt, createdAt, updatedAt,
                markReadOnNextPage, lastSeenChangelogId, false);
    }

    public boolean emailVerified() {
        return emailVerifiedAt != null;
    }

    public boolean enabled() {
        return disabledAt == null;
    }

    public AppUser(Long id, String email, String passwordHash, String kindleEmail,
                   Instant emailVerifiedAt, Instant disabledAt, Instant createdAt, Instant updatedAt) {
        this(id, email, passwordHash, kindleEmail, emailVerifiedAt, disabledAt, createdAt, updatedAt, true, null);
    }
}
