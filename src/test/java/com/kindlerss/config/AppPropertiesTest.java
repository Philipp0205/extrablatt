package com.kindlerss.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppPropertiesTest {

    @Test
    void readingSettingsFallBackToTheirDefaults() {
        AppProperties properties = new AppProperties("from@example.com", null, null,
                null, null, null, null, null, null, null, null, null);

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
                null, null, null, null, null, null, null, null, null);

        assertFalse(properties.billing().enabled());
        assertFalse(properties.billing().checkoutConfigured());
        assertFalse(properties.billing().webhookConfigured());
    }

    /**
     * Retention has to have real periods even when nobody configured any, since the
     * alternative is keeping everything for ever by accident.
     */
    @Test
    void retentionPeriodsExistWithoutBeingConfigured() {
        AppProperties properties = new AppProperties("from@example.com", null, null,
                null, null, null, null, null, null, null, null, null);

        assertEquals(730, properties.retention().sendEventDays());
        assertEquals(90, properties.retention().billingPayloadDays());
        assertEquals(30, properties.retention().usedTokenDays());
        assertEquals(365, properties.retention().articleCacheDays());
    }

    /** Zero switches one sweep off; a negative number is a typo, not a request. */
    @Test
    void aSweepCanBeSwitchedOffButNotSetToNonsense() {
        AppProperties.Retention off = new AppProperties.Retention(0, 0, 0, 0);
        AppProperties.Retention negative = new AppProperties.Retention(-5, -5, -5, -5);

        assertEquals(0, off.sendEventDays());
        assertEquals(0, negative.sendEventDays());
        assertEquals(0, negative.articleCacheDays());
    }

    @Test
    void pricesDefaultToTheAdvertisedOnes() {
        AppProperties.Billing billing = new AppProperties.Billing(true, null, null, null, null,
                null, null, null, null, null, null, null, null);

        assertEquals(250, billing.monthlyPriceCents());
        assertEquals(1_800, billing.yearlyPriceCents());
        // €18.00 a year is what gets advertised as €1.50 a month.
        assertEquals(150, billing.yearlyPricePerMonthCents());
        assertEquals(10, billing.freeMaxSendsPerMonth());
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
