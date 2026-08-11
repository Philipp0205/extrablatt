package com.kindlerss.repository;

import com.kindlerss.domain.UserSendLimit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** Persistence for administrator-managed per-user send controls. */
@Repository
public class UserSendLimitRepository {

    private final JdbcTemplate jdbc;

    public UserSendLimitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserSendLimit> findByUserId(long userId) {
        return jdbc.query("""
                SELECT user_id, max_sends_per_day, blocked_until
                FROM user_send_limits WHERE user_id = ?
                """, (rs, rowNum) -> new UserSendLimit(
                rs.getLong("user_id"),
                (Integer) rs.getObject("max_sends_per_day"),
                toInstant(rs.getTimestamp("blocked_until"))
        ), userId).stream().findFirst();
    }

    public void save(long userId, Integer maxSendsPerDay, Instant blockedUntil) {
        jdbc.update("""
                INSERT INTO user_send_limits (user_id, max_sends_per_day, blocked_until)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE
                SET max_sends_per_day = EXCLUDED.max_sends_per_day,
                    blocked_until = EXCLUDED.blocked_until,
                    updated_at = NOW()
                """, userId, maxSendsPerDay,
                blockedUntil == null ? null : Timestamp.from(blockedUntil));
    }

    public void delete(long userId) {
        jdbc.update("DELETE FROM user_send_limits WHERE user_id = ?", userId);
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
