package com.kindlerss.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppPropertiesTest {

    @Test
    void readingSettingsFallBackToTheirDefaults() {
        AppProperties properties = new AppProperties("from@example.com", null, null,
                null, null, null, null, null, null);

        assertEquals(AppProperties.Feeds.DEFAULT_MAX_ENTRIES, properties.feeds().maxEntries());
        assertEquals(AppProperties.Articles.DEFAULT_PAGE_SIZE, properties.articles().pageSize());
        assertEquals(AppProperties.Limits.DEFAULT_MAX_FEEDS, properties.limits().maxFeedsPerUser());
        assertEquals("http://localhost:8080", properties.publicUrl());
        assertEquals("https://paypal.me/philippkurrle", properties.donateUrl());
    }

    @Test
    void readingSettingsStayWithinWorkableBounds() {
        assertEquals(0, new AppProperties.Feeds(-1).maxEntries());
        assertEquals(500, new AppProperties.Feeds(10_000).maxEntries());
        // The repository refuses to hand out more than 100 articles at a time.
        assertEquals(100, new AppProperties.Articles(1_000).pageSize());
        assertEquals(5, new AppProperties.Articles(1).pageSize());
    }
}
