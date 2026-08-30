package com.kindlerss.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Application settings bound from {@code app.*} / env vars. Nested records get
 * safe defaults when omitted so the app boots without a full config file.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String mailFrom,
        String publicUrl,
        String rememberMeKey,
        Http http,
        Feeds feeds,
        Articles articles,
        Limits limits,
        Newsletters newsletters,
        String donateUrl
) {
    public AppProperties {
        if (http == null) {
            // Used by SafeHttpClient for outbound feed/article fetches.
            http = new Http(Duration.ofSeconds(10), Duration.ofSeconds(20), 2_097_152);
        }
        if (feeds == null) {
            feeds = new Feeds(null);
        }
        if (articles == null) {
            articles = new Articles(null);
        }
        if (limits == null) {
            limits = new Limits(null, null);
        }
        if (newsletters == null) {
            newsletters = new Newsletters(null, null);
        }
        if (publicUrl == null || publicUrl.isBlank()) {
            // Base URL used to build links in verification / password-reset e-mails.
            publicUrl = "http://localhost:8080";
        }
        publicUrl = publicUrl.replaceAll("/+$", "");
        if (rememberMeKey == null || rememberMeKey.isBlank()) {
            // Signs the remember-me cookie (TokenBasedRememberMeServices). Override
            // in production so tokens cannot be forged with the well-known default.
            rememberMeKey = "kindle-rss-remember-me-change-me";
        }
        if (donateUrl == null || donateUrl.isBlank()) {
            // Shown in Settings and in the occasional "help keep the servers running"
            // reminder after sending several articles.
            donateUrl = "https://paypal.me/philippkurrle";
        }
    }

    /** Timeouts and response size cap for outbound HTTP (feed refresh, extraction). */
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

    /**
     * Newsletters arrive by e-mail rather than by polling a URL: each newsletter
     * feed gets an address {@code <token>@inboundDomain}, and an inbound mail
     * provider (Postmark, Mailgun routes, …) is configured to POST received
     * messages to {@code /inbound/newsletters?secret=inboundSecret}. Leaving
     * {@code inboundDomain} blank disables adding new newsletters; the shared
     * secret guards the webhook since it cannot itself require a login.
     */
    public record Newsletters(String inboundDomain, String inboundSecret) {
        public Newsletters {
            if (inboundDomain != null) {
                inboundDomain = inboundDomain.trim().toLowerCase();
                if (inboundDomain.isBlank()) {
                    inboundDomain = null;
                }
            }
            if (inboundSecret != null && inboundSecret.isBlank()) {
                inboundSecret = null;
            }
        }

        public boolean enabled() {
            return inboundDomain != null;
        }
    }

    /** Per-account guardrails that keep open registration from being abused. */
    public record Limits(Integer maxFeedsPerUser, Integer maxSendsPerDay) {
        public static final int DEFAULT_MAX_FEEDS = 50;
        public static final int DEFAULT_MAX_SENDS_PER_DAY = 50;

        public Limits {
            if (maxFeedsPerUser == null) {
                maxFeedsPerUser = DEFAULT_MAX_FEEDS;
            }
            maxFeedsPerUser = Math.min(Math.max(maxFeedsPerUser, 1), 1_000);
            if (maxSendsPerDay == null) {
                maxSendsPerDay = DEFAULT_MAX_SENDS_PER_DAY;
            }
            maxSendsPerDay = Math.min(Math.max(maxSendsPerDay, 1), 1_000);
        }
    }
}
