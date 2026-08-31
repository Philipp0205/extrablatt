package com.kindlerss.repository;

import com.kindlerss.domain.BillingInterval;
import com.kindlerss.domain.Plan;
import com.kindlerss.domain.Subscription;
import com.kindlerss.domain.SubscriptionStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence for per-account subscription state. */
@Repository
public class SubscriptionRepository {

    private static final RowMapper<Subscription> MAPPER = (rs, rowNum) -> new Subscription(
            rs.getLong("user_id"),
            Plan.valueOf(rs.getString("plan")),
            SubscriptionStatus.valueOf(rs.getString("status")),
            interval(rs.getString("billing_interval")),
            rs.getString("provider"),
            rs.getString("provider_customer_id"),
            rs.getString("provider_subscription_id"),
            toInstant(rs.getTimestamp("current_period_end")),
            rs.getBoolean("cancel_at_period_end"),
            toInstant(rs.getTimestamp("withdrawal_consent_at"))
    );

    private static final String COLUMNS = """
            user_id, plan, status, billing_interval, provider, provider_customer_id,
            provider_subscription_id, current_period_end, cancel_at_period_end,
            withdrawal_consent_at
            """;

    private final JdbcTemplate jdbc;

    public SubscriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Subscription> findByUserId(long userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM subscriptions WHERE user_id = ?",
                MAPPER, userId).stream().findFirst();
    }

    public Optional<Subscription> findByProviderSubscriptionId(String providerSubscriptionId) {
        if (providerSubscriptionId == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM subscriptions WHERE provider_subscription_id = ?",
                MAPPER, providerSubscriptionId).stream().findFirst();
    }

    public Optional<Subscription> findByProviderCustomerId(String providerCustomerId) {
        if (providerCustomerId == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM subscriptions WHERE provider_customer_id = ?
                 ORDER BY updated_at DESC
                """, MAPPER, providerCustomerId).stream().findFirst();
    }

    /**
     * Accounts whose paid period has run out, or whose renewal is failing. The
     * nightly sweep uses this to expire what the provider never told us about.
     */
    public List<Subscription> findLapsed(Instant asOf) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM subscriptions
                 WHERE status IN ('ACTIVE', 'PAST_DUE', 'CANCELED', 'PENDING')
                   AND current_period_end IS NOT NULL
                   AND current_period_end < ?
                """, MAPPER, Timestamp.from(asOf));
    }

    public void save(Subscription subscription) {
        jdbc.update("""
                INSERT INTO subscriptions (user_id, plan, status, billing_interval, provider,
                                           provider_customer_id, provider_subscription_id,
                                           current_period_end, cancel_at_period_end,
                                           withdrawal_consent_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE
                SET plan = EXCLUDED.plan,
                    status = EXCLUDED.status,
                    billing_interval = EXCLUDED.billing_interval,
                    provider = EXCLUDED.provider,
                    provider_customer_id = COALESCE(EXCLUDED.provider_customer_id,
                                                    subscriptions.provider_customer_id),
                    provider_subscription_id = COALESCE(EXCLUDED.provider_subscription_id,
                                                        subscriptions.provider_subscription_id),
                    current_period_end = EXCLUDED.current_period_end,
                    cancel_at_period_end = EXCLUDED.cancel_at_period_end,
                    withdrawal_consent_at = COALESCE(EXCLUDED.withdrawal_consent_at,
                                                     subscriptions.withdrawal_consent_at),
                    updated_at = NOW()
                """,
                subscription.userId(),
                subscription.plan().name(),
                subscription.status().name(),
                subscription.interval() == null ? null : subscription.interval().name(),
                subscription.provider(),
                subscription.providerCustomerId(),
                subscription.providerSubscriptionId(),
                timestamp(subscription.currentPeriodEnd()),
                subscription.cancelAtPeriodEnd(),
                timestamp(subscription.withdrawalConsentAt()));
    }

    private static BillingInterval interval(String value) {
        return value == null ? null : BillingInterval.valueOf(value);
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
