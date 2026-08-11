package com.kindlerss.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Read-only aggregate telemetry derived from application data. */
@Repository
public class TelemetryRepository {

    private final JdbcTemplate jdbc;

    public TelemetryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Summary summary() {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT COUNT(*) FROM users WHERE disabled_at IS NULL) AS users,
                  (SELECT COUNT(*) FROM feeds) AS feeds,
                  (SELECT COUNT(*) FROM articles) AS articles,
                  (SELECT COUNT(*) FROM article_send_events) AS sends_total,
                  (SELECT COUNT(*) FROM article_send_events
                     WHERE sent_at >= NOW() - INTERVAL '24 hours') AS sends_24h,
                  (SELECT COUNT(*) FROM article_send_events
                     WHERE sent_at >= NOW() - INTERVAL '7 days') AS sends_7d
                """, (rs, rowNum) -> new Summary(
                rs.getLong("users"),
                rs.getLong("feeds"),
                rs.getLong("articles"),
                rs.getLong("sends_total"),
                rs.getLong("sends_24h"),
                rs.getLong("sends_7d")
        ));
    }

    public List<UserUsage> userUsage() {
        return jdbc.query("""
                SELECT u.id, u.email, u.email_verified_at, u.created_at,
                       COUNT(DISTINCT f.id) AS feeds,
                       COUNT(DISTINCT a.id) AS articles,
                       (SELECT COUNT(*) FROM article_send_events e
                          WHERE e.user_id = u.id) AS sends_total,
                       (SELECT COUNT(*) FROM article_send_events e
                          WHERE e.user_id = u.id
                            AND e.sent_at >= NOW() - INTERVAL '24 hours') AS sends_24h,
                       l.max_sends_per_day, l.blocked_until
                FROM users u
                LEFT JOIN feeds f ON f.user_id = u.id
                LEFT JOIN articles a ON a.feed_id = f.id
                LEFT JOIN user_send_limits l ON l.user_id = u.id
                WHERE u.disabled_at IS NULL
                GROUP BY u.id, l.max_sends_per_day, l.blocked_until
                ORDER BY sends_24h DESC, sends_total DESC, u.created_at DESC
                """, (rs, rowNum) -> new UserUsage(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getTimestamp("email_verified_at") != null,
                toInstant(rs.getTimestamp("created_at")),
                rs.getLong("feeds"),
                rs.getLong("articles"),
                rs.getLong("sends_total"),
                rs.getLong("sends_24h"),
                (Integer) rs.getObject("max_sends_per_day"),
                toInstant(rs.getTimestamp("blocked_until"))
        ));
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record Summary(
            long users,
            long feeds,
            long articles,
            long sendsTotal,
            long sends24h,
            long sends7d
    ) {}

    public record UserUsage(
            long userId,
            String email,
            boolean verified,
            Instant createdAt,
            long feeds,
            long articles,
            long sendsTotal,
            long sends24h,
            Integer maxSendsPerDay,
            Instant blockedUntil
    ) {
        public boolean blocked() {
            return blockedUntil != null && blockedUntil.isAfter(Instant.now());
        }
    }
}
