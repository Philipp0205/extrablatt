package com.kindlerss.repository;

import com.kindlerss.domain.EmailToken;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** JDBC persistence for one-time e-mail tokens (verification and password reset). */
@Repository
public class EmailTokenRepository {

    private static final RowMapper<EmailToken> MAPPER = (rs, rowNum) -> new EmailToken(
            rs.getString("token"),
            rs.getLong("user_id"),
            EmailToken.Purpose.valueOf(rs.getString("purpose")),
            toInstant(rs.getTimestamp("expires_at")),
            toInstant(rs.getTimestamp("used_at")),
            toInstant(rs.getTimestamp("created_at"))
    );

    private final JdbcTemplate jdbc;

    public EmailTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String token, long userId, EmailToken.Purpose purpose, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO email_tokens (token, user_id, purpose, expires_at)
                VALUES (?, ?, ?, ?)
                """, token, userId, purpose.name(), Timestamp.from(expiresAt));
    }

    public Optional<EmailToken> find(String token, EmailToken.Purpose purpose) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("SELECT * FROM email_tokens WHERE token = ? AND purpose = ?",
                MAPPER, token, purpose.name()).stream().findFirst();
    }

    public void markUsed(String token) {
        jdbc.update("UPDATE email_tokens SET used_at = NOW() WHERE token = ? AND used_at IS NULL", token);
    }

    /** Removes any outstanding tokens of a purpose so only the newest link works. */
    public void deleteForUser(long userId, EmailToken.Purpose purpose) {
        jdbc.update("DELETE FROM email_tokens WHERE user_id = ? AND purpose = ?", userId, purpose.name());
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
