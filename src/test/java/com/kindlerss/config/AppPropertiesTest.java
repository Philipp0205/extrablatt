package com.kindlerss.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppPropertiesTest {

    @Test
    void readingSettingsFallBackToTheirDefaults() {
        AppProperties properties = new AppProperties("from@example.com", null, null,
                null, null, null, null, null, null, null, null);

        assertEquals(AppProperties.Feeds.DEFAULT_MAX_ENTRIES, properties.feeds().maxEntries());
        assertEquals(AppProperties.Articles.DEFAULT_PAGE_SIZE, properties.articles().pageSize());
        assertEquals(AppProperties.Limits.DEFAULT_MAX_FEEDS, properties.limits().maxFeedsPerUser());
        assertEquals("http://localhost:8080", properties.publicUrl());
        assertEquals("https://paypal.me/philippkurrle", properties.donateUrl());
    }

    /**
     * A copy of the app that nobody configured must not charge anybody. Everything
     * about billing hangs off this one flag, so it is worth a test of its own.
     */
    @Test
    void billingIsOffUntilAnOperatorTurnsItOn() {
        AppProperties properties = new AppProperties("from@example.com", null, null,
                null, null, null, null, null, null, null, null);

        assertFalse(properties.billing().enabled());
        assertFalse(properties.billing().checkoutConfigured());
        assertFalse(properties.billing().webhookConfigured());
    }

    @Test
    void pricesDefaultToTheAdvertisedOnes() {
        AppProperties.Billing billing = new AppProperties.Billing(true, null, null, null, null,
                null, null, null, null, null, null, null, null);

        assertEquals(250, billing.monthlyPriceCents());
        assertEquals(1_800, billing.yearlyPriceCents());
        // €18.00 a year is what gets advertised as €1.50 a month.
        assertEquals(150, billing.yearlyPricePerMonthCents());
        assertEquals(3, billing.freeMaxSendsPerDay());
        assertEquals(15, billing.freeMaxFeeds());
    }

    @Test
    void checkoutNeedsBothLinksBeforeItIsUsable() {
        AppProperties.Billing onlyMonthly = new AppProperties.Billing(true, "stripe", "whsec",
                "https://pay.example.com/monthly", null, null, null, null, null, null, null, null, null);
        AppProperties.Billing both = new AppProperties.Billing(true, "stripe", "whsec",
                "https://pay.example.com/monthly", "https://pay.example.com/yearly",
                null, null, null, null, null, null, null, null);

        assertFalse(onlyMonthly.checkoutConfigured());
        assertTrue(both.checkoutConfigured());
        assertTrue(both.webhookConfigured());
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
