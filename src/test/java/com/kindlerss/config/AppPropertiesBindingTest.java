package com.kindlerss.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Settings are bound to records through their canonical constructor, which stops
 * happening the moment a record grows a second one — silently, leaving every
 * value at its default. These bind the settings the deployment actually sets.
 */
class AppPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration.class);

    @Test
    void readingSettingsAreBoundFromConfiguration() {
        runner.withPropertyValues(
                        "app.mail-from=from@example.com",
                        "app.feeds.max-entries=42",
                        "app.feeds.auto-refresh-after=3m",
                        "app.articles.page-size=25",
                        "app.http.max-bytes=1234567",
                        "app.limits.max-feeds-per-user=7")
                .run(context -> {
                    AppProperties properties = context.getBean(AppProperties.class);
                    assertEquals(42, properties.feeds().maxEntries());
                    assertEquals(Duration.ofMinutes(3), properties.feeds().autoRefreshAfter());
                    assertEquals(25, properties.articles().pageSize());
                    assertEquals(1234567, properties.http().maxBytes());
                    assertEquals(7, properties.limits().maxFeedsPerUser());
                });
    }

    @Test
    void settingsThatAreNotConfiguredKeepTheirDefaults() {
        runner.withPropertyValues("app.mail-from=from@example.com")
                .run(context -> {
                    AppProperties properties = context.getBean(AppProperties.class);
                    assertEquals(AppProperties.Feeds.DEFAULT_MAX_ENTRIES, properties.feeds().maxEntries());
                    assertEquals(AppProperties.Feeds.DEFAULT_AUTO_REFRESH_AFTER,
                            properties.feeds().autoRefreshAfter());
                    assertEquals(AppProperties.Http.DEFAULT_MAX_BYTES, properties.http().maxBytes());
                });
    }

    @EnableConfigurationProperties(AppProperties.class)
    static class BindingConfiguration {
    }
}
