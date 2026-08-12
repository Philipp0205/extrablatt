package com.kindlerss.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppPropertiesTest {

    @Test
    void readingSettingsFallBackToTheirDefaults() {
        AppProperties properties = new AppProperties("from@example.com", null, null,
                null, null, null, null);

        assertEquals(AppProperties.Feeds.DEFAULT_MAX_ENTRIES, properties.feeds().maxEntries());
        assertEquals(AppProperties.Feeds.DEFAULT_AUTO_REFRESH_AFTER, properties.feeds().autoRefreshAfter());
        assertEquals(AppProperties.Http.DEFAULT_MAX_BYTES, properties.http().maxBytes());
        assertEquals(AppProperties.Articles.DEFAULT_PAGE_SIZE, properties.articles().pageSize());
        assertEquals(AppProperties.Limits.DEFAULT_MAX_FEEDS, properties.limits().maxFeedsPerUser());
        assertEquals("http://localhost:8080", properties.publicUrl());
    }

    @Test
    void readingSettingsStayWithinWorkableBounds() {
        assertEquals(0, new AppProperties.Feeds(-1, null).maxEntries());
        assertEquals(500, new AppProperties.Feeds(10_000, null).maxEntries());
        // The repository refuses to hand out more than 100 articles at a time.
        assertEquals(100, new AppProperties.Articles(1_000).pageSize());
        assertEquals(5, new AppProperties.Articles(1).pageSize());
    }

    @Test
    void refreshingOnEveryPageLoadIsNotAnOption() {
        // Every feed of the account is fetched, so page loads cannot trigger it freely.
        assertEquals(Duration.ofMinutes(1),
                new AppProperties.Feeds(100, Duration.ofSeconds(1)).autoRefreshAfter());
        assertEquals(Duration.ZERO, new AppProperties.Feeds(100, Duration.ZERO).autoRefreshAfter());
        assertEquals(Duration.ZERO, new AppProperties.Feeds(100, Duration.ofMinutes(-5)).autoRefreshAfter());
        assertEquals(Duration.ofHours(2),
                new AppProperties.Feeds(100, Duration.ofHours(2)).autoRefreshAfter());
    }
}
