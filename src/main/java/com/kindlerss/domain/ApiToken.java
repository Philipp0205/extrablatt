package com.kindlerss.domain;

import java.time.Instant;

/** A personal API token as listed to its owner: never the secret, never the hash. */
public record ApiToken(
        long id,
        long userId,
        String name,
        String tokenPrefix,
        Instant createdAt,
        Instant lastUsedAt,
        Instant revokedAt
) {
}
