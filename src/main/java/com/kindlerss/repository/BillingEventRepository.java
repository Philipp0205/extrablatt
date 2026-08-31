package com.kindlerss.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Record of provider callbacks. The provider's own event id is the primary key,
 * so a retried or duplicated delivery is recognised instead of being applied a
 * second time.
 */
@Repository
public class BillingEventRepository {

    private final JdbcTemplate jdbc;

    public BillingEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records an event, returning false when it has been seen before. The insert
     * itself is the lock: two concurrent deliveries of the same event cannot both
     * win the primary key.
     *
     * <p>{@code userId} may be null when the provider sent nothing that identifies an
     * account. When it is present it is what lets the payload be erased with the
     * account, since the event id itself has to outlive it.
     */
    public boolean claim(String providerEventId, String provider, String type, String payload,
                         Long userId) {
        int inserted = jdbc.update("""
                INSERT INTO billing_events (provider_event_id, provider, type, payload, user_id)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (provider_event_id) DO NOTHING
                """, providerEventId, provider, type, payload, userId);
        return inserted == 1;
    }

    public void markProcessed(String providerEventId) {
        jdbc.update("UPDATE billing_events SET processed_at = NOW(), error = NULL WHERE provider_event_id = ?",
                providerEventId);
    }

    /**
     * Records why an event could not be applied. The row stays claimed so a retry
     * does not double-apply, but the failure is visible rather than silent.
     */
    public void markFailed(String providerEventId, String error) {
        jdbc.update("UPDATE billing_events SET error = ? WHERE provider_event_id = ?",
                error == null ? "unknown" : error.substring(0, Math.min(error.length(), 500)),
                providerEventId);
    }
}
