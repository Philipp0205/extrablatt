package com.kindlerss.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
        Billing billing,
        Retention retention,
        String trustedProxies,
        Boolean trustCloudflare
) {
    @ConstructorBinding
    public AppProperties {
        if (http == null) {
            // Used by SafeHttpClient for outbound feed/article fetches.
            http = new Http(Duration.ofSeconds(10), Duration.ofSeconds(20), 2_097_152, Duration.ofSeconds(30));
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
        if (billing == null) {
            billing = new Billing(false, null, null, null, null, null, null, null,
                    null, null, null, null, null, null);
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
        if (trustedProxies != null && trustedProxies.isBlank()) {
            // Blank means ClientIpResolver.PRIVATE_RANGES.
            trustedProxies = null;
        }
        if (trustCloudflare == null) {
            trustCloudflare = true;
        }
    }

    public AppProperties(String mailFrom, String publicUrl, String rememberMeKey, Http http,
                         Feeds feeds, Articles articles, Limits limits, Newsletters newsletters,
                         Billing billing, Retention retention) {
        this(mailFrom, publicUrl, rememberMeKey, http, feeds, articles, limits, newsletters,
                billing, retention, null, null);
    }

    /**
     * Timeouts and response size cap for outbound HTTP (feed refresh, extraction).
     * {@code readTimeout} bounds each wait for data; {@code totalTimeout} bounds a
     * whole fetch, redirects included, so a server that trickles a byte at a time
     * cannot hold a request open for ever.
     */
    public record Http(Duration connectTimeout, Duration readTimeout, int maxBytes, Duration totalTimeout) {
        // Two constructors: tell Boot which one binds, or it picks the short one.
        @ConstructorBinding
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
            if (totalTimeout == null || totalTimeout.isZero() || totalTimeout.isNegative()) {
                totalTimeout = Duration.ofSeconds(30);
            }
        }

        public Http(Duration connectTimeout, Duration readTimeout, int maxBytes) {
            this(connectTimeout, readTimeout, maxBytes, null);
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
     * messages to {@code /inbound/newsletters} with {@code inboundSecret} in the
     * {@code X-Newsletter-Secret} header or as the URL's Basic auth password.
     * Leaving {@code inboundDomain} blank disables adding new newsletters; the
     * shared secret guards the webhook since it cannot itself require a login.
     * {@code maxBodyBytes} caps one message (HTML, text and headers together);
     * default 10 MiB.
     */
    public record Newsletters(String inboundDomain, String inboundSecret, int maxBodyBytes) {
        public static final int DEFAULT_MAX_BODY_BYTES = 10 * 1024 * 1024;

        public Newsletters(String inboundDomain, String inboundSecret) {
            this(inboundDomain, inboundSecret, 0);
        }

        @ConstructorBinding
        public Newsletters {
            if (maxBodyBytes <= 0) {
                maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
            }
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
     * Paid subscriptions. Unconfigured — the default — the app has no billing at
     * all: nothing is charged, no prices are shown, no cancellation page exists,
     * and every account keeps the full {@code app.limits.*} allowances. That is
     * what a self-hosted deployment needs, since it is not the one collecting the
     * money, and it keeps the whole feature out of the way until an operator
     * deliberately turns it on.
     *
     * <p>Prices are gross, in euro cents, because a consumer price in the EU has to
     * be the total the customer pays. New accounts get a week of the paid
     * allowances; after that, reading stays free and Kindle delivery needs a
     * subscription. {@code app.limits.*} are the paid plan's allowances.
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
            Integer graceDays,
            Integer trialDays,
            Integer webhookMaxAttempts,
            Duration webhookRetryBackoff
    ) {
        public static final int DEFAULT_MONTHLY_PRICE_CENTS = 399;
        public static final int DEFAULT_YEARLY_PRICE_CENTS = 3_588;
        public static final int DEFAULT_FREE_MAX_SENDS_PER_MONTH = 0;
        public static final int DEFAULT_FREE_MAX_FEEDS = 0;
        public static final int DEFAULT_GRACE_DAYS = 7;
        public static final int DEFAULT_TRIAL_DAYS = 7;
        /**
         * How often a webhook event that could not be applied is tried in total,
         * counting the first delivery. With the default backoff the last attempt
         * lands about a day after the first, which outlasts any ordinary out-of-order
         * delivery and most short outages.
         */
        public static final int DEFAULT_WEBHOOK_MAX_ATTEMPTS = 12;
        /** Wait before the first retry; it doubles on each attempt, up to six hours. */
        public static final Duration DEFAULT_WEBHOOK_RETRY_BACKOFF = Duration.ofMinutes(1);

        /**
         * Query parameter carrying the account id into a hosted checkout, so the
         * provider hands it back and the payment can find its account. Stripe payment
         * links use {@code client_reference_id}; a Paddle checkout wants
         * {@code custom_data[user_id]}.
         */
        public static final String DEFAULT_REFERENCE_PARAM = "client_reference_id";

        /** Without the webhook retry settings, which then take their defaults. */
        public Billing(boolean enabled, String provider, String webhookSecret,
                       String monthlyCheckoutUrl, String yearlyCheckoutUrl, String portalUrl,
                       String operatorEmail, String referenceParam, Integer monthlyPriceCents,
                       Integer yearlyPriceCents, Integer freeMaxSendsPerMonth, Integer freeMaxFeeds,
                       Integer graceDays, Integer trialDays) {
            this(enabled, provider, webhookSecret, monthlyCheckoutUrl, yearlyCheckoutUrl, portalUrl,
                    operatorEmail, referenceParam, monthlyPriceCents, yearlyPriceCents,
                    freeMaxSendsPerMonth, freeMaxFeeds, graceDays, trialDays, null, null);
        }

        @ConstructorBinding
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
            freeMaxSendsPerMonth = Math.min(Math.max(freeMaxSendsPerMonth, 0), 10_000);
            if (freeMaxFeeds == null) {
                freeMaxFeeds = DEFAULT_FREE_MAX_FEEDS;
            }
            freeMaxFeeds = Math.min(Math.max(freeMaxFeeds, 0), 1_000);
            if (graceDays == null || graceDays < 0) {
                graceDays = DEFAULT_GRACE_DAYS;
            }
            if (trialDays == null || trialDays < 0) {
                trialDays = DEFAULT_TRIAL_DAYS;
            }
            trialDays = Math.min(trialDays, 90);
            if (webhookMaxAttempts == null || webhookMaxAttempts < 1) {
                webhookMaxAttempts = DEFAULT_WEBHOOK_MAX_ATTEMPTS;
            }
            webhookMaxAttempts = Math.min(webhookMaxAttempts, 100);
            if (webhookRetryBackoff == null || webhookRetryBackoff.isNegative()
                    || webhookRetryBackoff.isZero()) {
                webhookRetryBackoff = DEFAULT_WEBHOOK_RETRY_BACKOFF;
            }
        }

        /**
         * Whether a payment can actually be taken right now. Billing can be enabled
         * before a provider is connected — the plans and prices are then visible and
         * the trial still runs, but ordering says so instead of failing obscurely.
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
