package com.kindlerss.domain;

import java.time.Instant;

/**
 * A single feed entry. {@code guid} is the publisher's stable id (Atom {@code id} /
 * RSS {@code guid}, else link, else title+date) used for deduplication per feed.
 * {@code savedAt} marks a bookmarked article, which is deliberately independent of
 * {@code read}: an article is usually saved precisely because it has been read.
 */
public record Article(
        Long id,
        Long feedId,
        String guid,
        String title,
        String url,
        String author,
        Instant publishedAt,
        String summaryHtml,
        String feedContentHtml,
        String extractedContentHtml,
        boolean read,
        Instant sentAt,
        Instant createdAt,
        Instant updatedAt,
        String feedTitle,
        Instant savedAt
) {
    public Article(Long id, Long feedId, String guid, String title, String url, String author,
                   Instant publishedAt, String summaryHtml, String feedContentHtml,
                   String extractedContentHtml, boolean read, Instant sentAt,
                   Instant createdAt, Instant updatedAt) {
        this(id, feedId, guid, title, url, author, publishedAt, summaryHtml, feedContentHtml,
                extractedContentHtml, read, sentAt, createdAt, updatedAt, null, null);
    }

    public Article(Long id, Long feedId, String guid, String title, String url, String author,
                   Instant publishedAt, String summaryHtml, String feedContentHtml,
                   String extractedContentHtml, boolean read, Instant sentAt,
                   Instant createdAt, Instant updatedAt, String feedTitle) {
        this(id, feedId, guid, title, url, author, publishedAt, summaryHtml, feedContentHtml,
                extractedContentHtml, read, sentAt, createdAt, updatedAt, feedTitle, null);
    }

    public boolean hasBeenSent() {
        return sentAt != null;
    }

    public boolean saved() {
        return savedAt != null;
    }
}
