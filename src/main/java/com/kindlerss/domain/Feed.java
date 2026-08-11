package com.kindlerss.domain;

import java.time.Instant;

public record Feed(
        Long id,
        String title,
        String url,
        String siteUrl,
        String category,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        long unreadCount
) {
    /** The category a feed belongs to while it has none of its own. */
    public static final String UNCATEGORIZED = "Uncategorized";

    /** The category to browse this feed under, never blank. */
    public String categoryName() {
        return category == null || category.isBlank() ? UNCATEGORIZED : category.trim();
    }

    public Feed(Long id, String title, String url, String siteUrl, String lastError,
                Instant createdAt, Instant updatedAt) {
        this(id, title, url, siteUrl, null, lastError, createdAt, updatedAt, 0);
    }

    public Feed(Long id, String title, String url, String siteUrl, String category, String lastError,
                Instant createdAt, Instant updatedAt) {
        this(id, title, url, siteUrl, category, lastError, createdAt, updatedAt, 0);
    }
}
