package com.kindlerss.domain;

import java.time.Instant;

public record Feed(
        Long id,
        String title,
        String url,
        String siteUrl,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        long unreadCount
) {
    public Feed(Long id, String title, String url, String siteUrl, String lastError,
                Instant createdAt, Instant updatedAt) {
        this(id, title, url, siteUrl, lastError, createdAt, updatedAt, 0);
    }
}
