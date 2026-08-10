package com.kindlerss.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String password,
        String kindleEmail,
        String mailFrom,
        String rememberMeKey,
        Http http,
        Feeds feeds,
        Articles articles
) {
    public AppProperties {
        if (http == null) {
            http = new Http(Duration.ofSeconds(10), Duration.ofSeconds(20), 2_097_152);
        }
        if (feeds == null) {
            feeds = new Feeds(null);
        }
        if (articles == null) {
            articles = new Articles(null);
        }
        if (rememberMeKey == null || rememberMeKey.isBlank()) {
            rememberMeKey = "kindle-rss-remember-me-change-me";
        }
    }

    public record Http(Duration connectTimeout, Duration readTimeout, int maxBytes) {
        public Http {
            if (connectTimeout == null) {
                connectTimeout = Duration.ofSeconds(10);
            }
            if (readTimeout == null) {
                readTimeout = Duration.ofSeconds(20);
            }
            if (maxBytes <= 0) {
                maxBytes = 2_097_152;
            }
        }
    }

    /**
     * How many entries a refresh asks a feed for. 0 fetches feed URLs exactly as
     * they were entered.
     */
    public record Feeds(Integer maxEntries) {
        public static final int DEFAULT_MAX_ENTRIES = 100;

        public Feeds {
            if (maxEntries == null) {
                maxEntries = DEFAULT_MAX_ENTRIES;
            }
            maxEntries = Math.min(Math.max(maxEntries, 0), 500);
        }
    }

    /** How many articles one page of the article list holds. */
    public record Articles(Integer pageSize) {
        public static final int DEFAULT_PAGE_SIZE = 50;

        public Articles {
            if (pageSize == null) {
                pageSize = DEFAULT_PAGE_SIZE;
            }
            pageSize = Math.min(Math.max(pageSize, 5), 100);
        }
    }
}
