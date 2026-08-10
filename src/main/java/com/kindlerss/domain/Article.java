package com.kindlerss.domain;

import java.time.Instant;

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
        String feedTitle
) {
    public Article(Long id, Long feedId, String guid, String title, String url, String author,
                   Instant publishedAt, String summaryHtml, String feedContentHtml,
                   String extractedContentHtml, boolean read, Instant sentAt,
                   Instant createdAt, Instant updatedAt) {
        this(id, feedId, guid, title, url, author, publishedAt, summaryHtml, feedContentHtml,
                extractedContentHtml, read, sentAt, createdAt, updatedAt, null);
    }

    public boolean hasBeenSent() {
        return sentAt != null;
    }
}
