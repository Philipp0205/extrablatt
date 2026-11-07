package com.kindlerss.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Record of provider callbacks. The provider's own event id is the primary key,
 * so a retried or duplicated delivery is recognised instead of being applied a
 * second time.
 *
 * <p>An event is in one of four states: processed ({@code processed_at} set),
 * in progress (claimed, no error yet), failed ({@code error} set, a retry may be
 * due at {@code next_attempt_at}), or out of attempts (failed with no retry
 * scheduled). Only a failed event, or a claim that went stale because the process
 * died part-way, can be claimed again.
 */
@Repository
public class BillingEventRepository {

    /** How long a claim may run before another delivery or the sweep may take it over. */
    static final String STALE_CLAIM = "INTERVAL '10 minutes'";

    /** The longest wait between two retries, however many attempts have gone before. */
    static final Duration MAX_BACKOFF = Duration.ofHours(6);

    /** A failed event the retry sweep may pick up. */
    public record DueRetry(String providerEventId, String provider, String payload) {
    }

    private final JdbcTemplate jdbc;

    public BillingEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims an event for applying, returning false when it must not be applied by
     * this caller: already processed, being applied by someone else right now, or
     * out of attempts. The statement itself is the lock: two concurrent deliveries
     * of the same event cannot both win the primary key or the conditional update.
     *
     * <p>A delivery of an event that failed before claims it again, which is what
     * lets the provider's own retries (and a manual resend from its dashboard)
     * succeed once whatever was missing has arrived.
     *
     * <p>{@code userId} may be null when the provider sent nothing that identifies an
     * account. When it is present it is what lets the payload be erased with the
     * account, since the event id itself has to outlive it.
     */
    public boolean claim(String providerEventId, String provider, String type, String payload,
                         Long userId, String subscriptionRef, Instant occurredAt, int maxAttempts) {
        List<Integer> claimed = jdbc.query("""
                INSERT INTO billing_events (provider_event_id, provider, type, payload, user_id,
                                            subscription_ref, occurred_at, attempts, claimed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, NOW())
                ON CONFLICT (provider_event_id) DO UPDATE
                SET attempts = billing_events.attempts + 1,
                    error = NULL,
                    claimed_at = NOW(),
                    next_attempt_at = NULL,
                    subscription_ref = COALESCE(billing_events.subscription_ref, EXCLUDED.subscription_ref),
                    occurred_at = COALESCE(billing_events.occurred_at, EXCLUDED.occurred_at)
                WHERE billing_events.processed_at IS NULL
                  AND billing_events.attempts < ?
                  AND (billing_events.error IS NOT NULL
                       OR billing_events.claimed_at IS NULL
                       OR billing_events.claimed_at < NOW() - %s)
                RETURNING attempts
                """.formatted(STALE_CLAIM), (rs, rowNum) -> rs.getInt(1),
                providerEventId, provider, type, payload, userId, subscriptionRef,
                timestamp(occurredAt), maxAttempts);
        return !claimed.isEmpty();
    }

    /**
     * Failed events whose retry is due, oldest first, plus claims that went stale.
     * Events whose payload was already redacted cannot be re-read and are left to
     * the provider's own redelivery.
     */
    public List<DueRetry> findDueRetries(int maxAttempts, int limit) {
        return jdbc.query("""
                SELECT provider_event_id, provider, payload FROM billing_events
                WHERE %s
                ORDER BY received_at
                LIMIT ?
                """.formatted(dueCondition()),
                (rs, rowNum) -> new DueRetry(rs.getString("provider_event_id"),
                        rs.getString("provider"), rs.getString("payload")),
                maxAttempts, limit);
    }

    /**
     * Claims a due event for the retry sweep. False when a provider redelivery or
     * another sweep got there first.
     */
    public boolean reclaim(String providerEventId, int maxAttempts) {
        return jdbc.update("""
                UPDATE billing_events
                SET attempts = attempts + 1, error = NULL, claimed_at = NOW(), next_attempt_at = NULL
                WHERE provider_event_id = ? AND %s
                """.formatted(dueCondition()), providerEventId, maxAttempts) == 1;
    }

    private static String dueCondition() {
        return """
                processed_at IS NULL
                  AND attempts < ?
                  AND payload <> ''
                  AND ((error IS NOT NULL AND next_attempt_at <= NOW())
                       OR (error IS NULL AND claimed_at < NOW() - %s))
                """.formatted(STALE_CLAIM);
    }

    /**
     * Whether a newer event about the same subscription has already been applied.
     * Subscription events carry the whole state, so applying an older one after a
     * newer one would roll the subscription back. A checkout event does not carry
     * the state and therefore never supersedes anything.
     */
    public boolean supersededByNewer(String providerEventId, String subscriptionRef, Instant occurredAt) {
        if (subscriptionRef == null || occurredAt == null) {
            return false;
        }
        Boolean newer = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM billing_events
                    WHERE subscription_ref = ?
                      AND provider_event_id <> ?
                      AND processed_at IS NOT NULL
                      AND occurred_at > ?
                      AND type <> 'checkout.session.completed'
                )
                """, Boolean.class, subscriptionRef, providerEventId, timestamp(occurredAt));
        return Boolean.TRUE.equals(newer);
    }

    public void markProcessed(String providerEventId) {
        jdbc.update("""
                UPDATE billing_events
                SET processed_at = NOW(), error = NULL, claimed_at = NULL, next_attempt_at = NULL
                WHERE provider_event_id = ?
                """, providerEventId);
    }

    /**
     * Records why an event could not be applied and schedules the next attempt,
     * backing off exponentially from {@code backoff} up to {@link #MAX_BACKOFF}.
     * Returns whether another attempt is scheduled; false means the event is out of
     * attempts and stays failed, with its reason on the row, for an operator.
     */
    public boolean markFailed(String providerEventId, String error, int maxAttempts, Duration backoff) {
        List<Boolean> retry = jdbc.query("""
                UPDATE billing_events
                SET error = ?,
                    claimed_at = NULL,
                    next_attempt_at = CASE
                        WHEN attempts < ?
                        THEN NOW() + LEAST(? * POWER(2, attempts - 1), ?) * INTERVAL '1 second'
                    END
                WHERE provider_event_id = ?
                RETURNING next_attempt_at IS NOT NULL
                """, (rs, rowNum) -> rs.getBoolean(1),
                error == null ? "unknown" : error.substring(0, Math.min(error.length(), 500)),
                maxAttempts, backoff.toSeconds(), MAX_BACKOFF.toSeconds(), providerEventId);
        return !retry.isEmpty() && Boolean.TRUE.equals(retry.getFirst());
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
