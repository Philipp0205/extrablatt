package com.kindlerss.domain;

import java.time.Instant;

/**
 * Subscribed source, optionally with an unread count for list views. Most feeds
 * are {@link FeedSource#RSS}, polled at {@code url}. A {@link FeedSource#NEWSLETTER}
 * feed is instead auto-created the first time an account's shared newsletter
 * inbox (see {@code AppUser.newsletterInboundToken}) receives an issue from a new
 * sender; {@code url} holds a synthetic {@code newsletter:<sender-address>} value
 * (never fetched) so the column can stay non-null and unique per account, and also
 * doubles as the key used to find that sender's feed again next time.
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
        FeedSource source
) {
    /** The category a feed belongs to while it has none of its own. */
    public static final String UNCATEGORIZED = "Uncategorized";

    /** Prefix of the synthetic {@code url} a newsletter feed is stored under. */
    private static final String NEWSLETTER_URL_PREFIX = "newsletter:";

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

    /** The newsletter's sender address, or null for an RSS feed. */
    public String newsletterSender() {
        if (!isNewsletter() || url == null || !url.startsWith(NEWSLETTER_URL_PREFIX)) {
            return null;
        }
        return url.substring(NEWSLETTER_URL_PREFIX.length());
    }

    public Feed(Long id, String title, String url, String siteUrl, String lastError,
                Instant createdAt, Instant updatedAt) {
        this(id, title, url, siteUrl, null, lastError, createdAt, updatedAt, 0, FeedSource.RSS);
    }

    public Feed(Long id, String title, String url, String siteUrl, String category, String lastError,
                Instant createdAt, Instant updatedAt) {
        this(id, title, url, siteUrl, category, lastError, createdAt, updatedAt, 0, FeedSource.RSS);
    }
}
