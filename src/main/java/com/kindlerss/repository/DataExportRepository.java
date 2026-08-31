package com.kindlerss.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads everything held about one account, for the copy a person is entitled to
 * under Art. 15 GDPR.
 *
 * <p>Its own repository rather than a method on each existing one, because the thing
 * being expressed is a single guarantee — that the export is complete — and that is
 * only checkable if every query behind it is in one file. When a migration adds a
 * table holding personal data, this is the file that has to change with it.
 *
 * <p>Every query is scoped by the account. There is no code path here that can read
 * another account's rows.
 */
@Repository
public class DataExportRepository {

    private final JdbcTemplate jdbc;

    public DataExportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> account(long userId) {
        return single("""
                SELECT email, kindle_email, email_verified_at, disabled_at,
                       newsletter_inbound_token, created_at, updated_at
                FROM users WHERE id = ?
                """, userId);
    }

    public Map<String, Object> subscription(long userId) {
        return single("""
                SELECT plan, status, billing_interval, provider, provider_customer_id,
                       provider_subscription_id, current_period_end, cancel_at_period_end,
                       withdrawal_consent_at, created_at, updated_at
                FROM subscriptions WHERE user_id = ?
                """, userId);
    }

    /** The administrator's override, if one was ever set on this account. */
    public Map<String, Object> sendLimit(long userId) {
        return single("""
                SELECT max_sends_per_day, blocked_until, updated_at
                FROM user_send_limits WHERE user_id = ?
                """, userId);
    }

    public List<Map<String, Object>> feeds(long userId) {
        return list("""
                SELECT title, url, site_url, category, source, created_at
                FROM feeds WHERE user_id = ? ORDER BY created_at
                """, userId);
    }

    /**
     * Article metadata and reading state, without the stored article text. The text is
     * the publisher's writing rather than personal data about the reader, it is still
     * at the URL in each row, and including it would turn a copy of someone's account
     * into a hundred megabytes they cannot open.
     */
    public List<Map<String, Object>> articles(long userId) {
        return list("""
                SELECT f.title AS feed, a.title, a.url, a.author, a.published_at,
                       a.read, a.read_at, a.saved_at, a.sent_at, a.created_at
                FROM articles a
                JOIN feeds f ON f.id = a.feed_id
                WHERE f.user_id = ?
                ORDER BY a.created_at
                """, userId);
    }

    public List<Map<String, Object>> sendHistory(long userId) {
        return list("""
                SELECT e.sent_at, a.title, a.url
                FROM article_send_events e
                JOIN articles a ON a.id = e.article_id
                WHERE e.user_id = ?
                ORDER BY e.sent_at
                """, userId);
    }

    /**
     * Matched on the address as well as the account id: a cancellation may have been
     * declared without signing in, and it is still that person's data.
     */
    public List<Map<String, Object>> cancellations(long userId, String email) {
        return list("""
                SELECT received_at, kind, contract_ref, requested_end, reason,
                       effective_at, confirmed_at
                FROM cancellation_requests
                WHERE user_id = ? OR lower(email) = lower(?)
                ORDER BY received_at
                """, userId, email);
    }

    /** Which payment events touched this account, without the raw provider payload. */
    public List<Map<String, Object>> billingEvents(long userId) {
        return list("""
                SELECT received_at, provider, type, processed_at
                FROM billing_events WHERE user_id = ? ORDER BY received_at
                """, userId);
    }

    private Map<String, Object> single(String sql, Object... args) {
        List<Map<String, Object>> rows = list(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Ordered maps, so the exported JSON reads in the order the columns were written
     * rather than in whatever order a hash map felt like.
     */
    private List<Map<String, Object>> list(String sql, Object... args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbc.query(sql, resultSet -> {
            var metaData = resultSet.getMetaData();
            Map<String, Object> row = new LinkedHashMap<>();
            for (int column = 1; column <= metaData.getColumnCount(); column++) {
                Object value = resultSet.getObject(column);
                if (value instanceof java.sql.Timestamp timestamp) {
                    value = timestamp.toInstant().toString();
                } else if (value instanceof java.sql.Date date) {
                    value = date.toLocalDate().toString();
                }
                row.put(metaData.getColumnLabel(column), value);
            }
            rows.add(row);
        }, args);
        return rows;
    }
}
