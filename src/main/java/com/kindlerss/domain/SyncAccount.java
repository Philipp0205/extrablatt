package com.kindlerss.domain;

import java.time.Instant;

/** One account's connection to an external RSS provider. The password stays encrypted. */
public record SyncAccount(
        long userId,
        String provider,
        String baseUrl,
        String username,
        String secretEncrypted,
        String cursor,
        Instant lastSyncedAt,
        String lastError
) {
    public static final String NEXTCLOUD = "nextcloud";
}
