package com.kindlerss.repository;

import com.kindlerss.domain.ApiToken;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/** Persistence for personal API tokens. Only hashes of the secrets are stored. */
@Repository
public class ApiTokenRepository {

    private static final String COLUMNS =
            "id, user_id, name, token_prefix, created_at, last_used_at, revoked_at";

    private static final RowMapper<ApiToken> MAPPER = (rs, rowNum) -> new ApiToken(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("name"),
            rs.getString("token_prefix"),
            rs.getTimestamp("created_at").toInstant(),
            toInstant(rs.getTimestamp("last_used_at")),
            toInstant(rs.getTimestamp("revoked_at"))
    );

    private final JdbcTemplate jdbc;

    public ApiTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ApiToken insert(long userId, String name, String tokenHash, String tokenPrefix) {
        return jdbc.queryForObject("""
                INSERT INTO api_tokens (user_id, name, token_hash, token_prefix)
                VALUES (?, ?, ?, ?)
                RETURNING """ + " " + COLUMNS, MAPPER, userId, name, tokenHash, tokenPrefix);
    }

    /** The account's tokens that still work, newest first. */
    public List<ApiToken> findActive(long userId) {
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM api_tokens WHERE user_id = ? AND revoked_at IS NULL
                ORDER BY created_at DESC, id DESC
                """, MAPPER, userId);
    }

    /** Scoped by account, so nobody can revoke somebody else's token by guessing an id. */
    public boolean revoke(long userId, long id) {
        return jdbc.update("""
                UPDATE api_tokens SET revoked_at = NOW()
                WHERE id = ? AND user_id = ? AND revoked_at IS NULL
                """, id, userId) > 0;
    }

    public Optional<StoredToken> findActiveByHash(String tokenHash) {
        return jdbc.query("""
                SELECT id, user_id, token_hash FROM api_tokens
                WHERE token_hash = ? AND revoked_at IS NULL
                """, (rs, n) -> new StoredToken(rs.getLong("id"), rs.getLong("user_id"),
                rs.getString("token_hash")), tokenHash).stream().findFirst();
    }

    public void markUsed(long id) {
        jdbc.update("UPDATE api_tokens SET last_used_at = NOW() WHERE id = ?", id);
    }

    /** What authentication needs to know about a token; the hash is for comparing only. */
    public record StoredToken(long id, long userId, String tokenHash) {
    }

    private static java.time.Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
