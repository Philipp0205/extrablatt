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
        Accessibility accessibility,
        String donateUrl,
        Billing billing,
        Retention retention
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
        if (accessibility == null) {
            accessibility = new Accessibility(null);
        }
        if (billing == null) {
            billing = new Billing(false, null, null, null, null, null, null, null,
                    null, null, null, null, null);
        }
        if (retention == null) {
            retention = new Retention(null, null, null, null);
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

    /**
     * The host the accessibility-first edition answers on (e.g.
     * {@code accessibility.extrablatt.app}). It is the same application, the same
     * database and the same accounts as the standard edition — only the view layer
     * differs. Left blank, the edition is still reachable through
     * {@code ?display=accessible} on any host, which is all a local run needs.
     */
    public record Accessibility(String domain) {
        public Accessibility {
            if (domain != null) {
                domain = domain.trim().toLowerCase();
                if (domain.isBlank()) {
                    domain = null;
                }
            }
        }

        public boolean hasDomain() {
            return domain != null;
        }

        /** Absolute base URL of the accessible edition, or null when it has no host of its own. */
        public String baseUrl() {
            return domain == null ? null : "https://" + domain;
        }
    }

    /**
     * Paid subscriptions. Unconfigured — the default — the app has no billing at
     * all: nothing is charged, no prices are shown, no cancellation page exists,
     * and every account keeps the full {@code app.limits.*} allowances. That is
     * what a self-hosted deployment needs, since it is not the one collecting the
     * money, and it keeps the whole feature out of the way until an operator
     * deliberately turns it on.
     *
     * <p>Prices are gross, in euro cents, because a consumer price in the EU has to
     * be the total the customer pays. The free tier's allowances are separate from
     * {@code app.limits.*}, which become the paid tier's allowances.
     */
    public record Billing(
            boolean enabled,
            String provider,
            String webhookSecret,
            String monthlyCheckoutUrl,
            String yearlyCheckoutUrl,
            String portalUrl,
            String operatorEmail,
            String referenceParam,
            Integer monthlyPriceCents,
            Integer yearlyPriceCents,
            Integer freeMaxSendsPerMonth,
            Integer freeMaxFeeds,
            Integer graceDays
    ) {
        public static final int DEFAULT_MONTHLY_PRICE_CENTS = 250;
        public static final int DEFAULT_YEARLY_PRICE_CENTS = 1_800;
        public static final int DEFAULT_FREE_MAX_SENDS_PER_MONTH = 5;
        public static final int DEFAULT_FREE_MAX_FEEDS = 15;
        public static final int DEFAULT_GRACE_DAYS = 7;

        /**
         * Query parameter carrying the account id into a hosted checkout, so the
         * provider hands it back and the payment can find its account. Stripe payment
         * links use {@code client_reference_id}; a Paddle checkout wants
         * {@code custom_data[user_id]}.
         */
        public static final String DEFAULT_REFERENCE_PARAM = "client_reference_id";

        public Billing {
            provider = blankToNull(provider);
            if (provider != null) {
                provider = provider.trim().toLowerCase();
            }
            webhookSecret = blankToNull(webhookSecret);
            monthlyCheckoutUrl = blankToNull(monthlyCheckoutUrl);
            yearlyCheckoutUrl = blankToNull(yearlyCheckoutUrl);
            portalUrl = blankToNull(portalUrl);
            operatorEmail = blankToNull(operatorEmail);
            referenceParam = blankToNull(referenceParam);
            if (referenceParam == null) {
                referenceParam = DEFAULT_REFERENCE_PARAM;
            }
            if (monthlyPriceCents == null || monthlyPriceCents <= 0) {
                monthlyPriceCents = DEFAULT_MONTHLY_PRICE_CENTS;
            }
            if (yearlyPriceCents == null || yearlyPriceCents <= 0) {
                yearlyPriceCents = DEFAULT_YEARLY_PRICE_CENTS;
            }
            if (freeMaxSendsPerMonth == null) {
                freeMaxSendsPerMonth = DEFAULT_FREE_MAX_SENDS_PER_MONTH;
            }
            freeMaxSendsPerMonth = Math.min(Math.max(freeMaxSendsPerMonth, 1), 10_000);
            if (freeMaxFeeds == null) {
                freeMaxFeeds = DEFAULT_FREE_MAX_FEEDS;
            }
            freeMaxFeeds = Math.min(Math.max(freeMaxFeeds, 1), 1_000);
            if (graceDays == null || graceDays < 0) {
                graceDays = DEFAULT_GRACE_DAYS;
            }
        }

        /**
         * Whether a payment can actually be taken right now. Billing can be enabled
         * before a provider is connected — the plans and prices are then visible and
         * the free tier applies, but ordering says so instead of failing obscurely.
         */
        public boolean checkoutConfigured() {
            return enabled && monthlyCheckoutUrl != null && yearlyCheckoutUrl != null;
        }

        public boolean webhookConfigured() {
            return provider != null && webhookSecret != null;
        }

        public Duration grace() {
            return Duration.ofDays(graceDays);
        }

        /** The yearly price expressed per month, which is how it is advertised. */
        public int yearlyPricePerMonthCents() {
            return Math.round(yearlyPriceCents / 12f);
        }

        private static String blankToNull(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }

    /**
     * How long data is kept. Art. 5(1)(e) GDPR asks for personal data to be held no
     * longer than necessary, and "necessary" is a decision an operator has to make
     * rather than something a default can make for them — so each figure is separate,
     * and zero switches that particular sweep off.
     *
     * <p>The values are deliberately generous. Nothing here deletes anything a reader
     * would miss: an article's cached text is re-extracted from its URL on demand, and
     * send history beyond the window only ever fed a lifetime counter.
     */
    public record Retention(
            Integer sendEventDays,
            Integer billingPayloadDays,
            Integer usedTokenDays,
            Integer articleCacheDays
    ) {
        public static final int DEFAULT_SEND_EVENT_DAYS = 730;
        public static final int DEFAULT_BILLING_PAYLOAD_DAYS = 90;
        public static final int DEFAULT_USED_TOKEN_DAYS = 30;
        public static final int DEFAULT_ARTICLE_CACHE_DAYS = 365;

        public Retention {
            sendEventDays = atLeastZero(sendEventDays, DEFAULT_SEND_EVENT_DAYS);
            billingPayloadDays = atLeastZero(billingPayloadDays, DEFAULT_BILLING_PAYLOAD_DAYS);
            usedTokenDays = atLeastZero(usedTokenDays, DEFAULT_USED_TOKEN_DAYS);
            articleCacheDays = atLeastZero(articleCacheDays, DEFAULT_ARTICLE_CACHE_DAYS);
        }

        private static int atLeastZero(Integer value, int fallback) {
            if (value == null) {
                return fallback;
            }
            return Math.max(value, 0);
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
