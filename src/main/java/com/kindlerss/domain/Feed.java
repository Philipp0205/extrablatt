package com.kindlerss.domain;

import java.time.Instant;

/**
 * Subscribed source, optionally with an unread count for list views. Most feeds
 * are {@link FeedSource#RSS}, polled at {@code url}. A {@link FeedSource#NEWSLETTER}
 * feed instead has an {@code inboundToken} identifying the per-feed e-mail address
 * newsletter issues are sent to; {@code url} holds a synthetic, never-fetched
 * placeholder for that kind so the column can stay non-null and unique per account.
 */
public record Feed(
        Long id,
        String title,
        String url,
        String siteUrl,
        String category,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        long unreadCount,
        FeedSource source,
        String inboundToken
) {
    /** The category a feed belongs to while it has none of its own. */
    public static final String UNCATEGORIZED = "Uncategorized";

    public Feed {
        if (source == null) {
            source = FeedSource.RSS;
        }
    }

    /** The category to browse this feed under, never blank. */
    public String categoryName() {
        return category == null || category.isBlank() ? UNCATEGORIZED : category.trim();
    }

    public boolean isNewsletter() {
        return source == FeedSource.NEWSLETTER;
    }

    public Feed(Long id, String title, String url, String siteUrl, String lastError,
                Instant createdAt, Instant updatedAt) {
        this(id, title, url, siteUrl, null, lastError, createdAt, updatedAt, 0, FeedSource.RSS, null);
    }

    public Feed(Long id, String title, String url, String siteUrl, String category, String lastError,
                Instant createdAt, Instant updatedAt) {
        this(id, title, url, siteUrl, category, lastError, createdAt, updatedAt, 0, FeedSource.RSS, null);
    }
}
