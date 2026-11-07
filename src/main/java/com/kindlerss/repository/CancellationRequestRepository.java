package com.kindlerss.repository;

import com.kindlerss.domain.CancellationRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/** Persistence for cancellation declarations received under § 312k BGB. */
@Repository
public class CancellationRequestRepository {

    private static final RowMapper<CancellationRequest> MAPPER = (rs, rowNum) -> new CancellationRequest(
            rs.getLong("id"),
            (Long) rs.getObject("user_id"),
            rs.getString("email"),
            rs.getString("name"),
            rs.getString("contract_ref"),
            CancellationRequest.Kind.valueOf(rs.getString("kind")),
            rs.getDate("requested_end") == null ? null : rs.getDate("requested_end").toLocalDate(),
            rs.getString("reason"),
            rs.getTimestamp("effective_at") == null ? null : rs.getTimestamp("effective_at").toInstant(),
            rs.getTimestamp("received_at").toInstant()
    );

    private final JdbcTemplate jdbc;

    public CancellationRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CancellationRequest insert(Long userId, String email, String name, String contractRef,
                                      CancellationRequest.Kind kind, LocalDate requestedEnd,
                                      String reason, Instant effectiveAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO cancellation_requests
                        (user_id, email, name, contract_ref, kind, requested_end, reason, effective_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            if (userId == null) {
                statement.setNull(1, java.sql.Types.BIGINT);
            } else {
                statement.setLong(1, userId);
            }
            statement.setString(2, email);
            statement.setString(3, name);
            statement.setString(4, contractRef);
            statement.setString(5, kind.name());
            statement.setDate(6, requestedEnd == null ? null : Date.valueOf(requestedEnd));
            statement.setString(7, reason);
            statement.setTimestamp(8, effectiveAt == null ? null : Timestamp.from(effectiveAt));
            return statement;
        }, keys);
        long id = Objects.requireNonNull(keys.getKeys()).get("id") instanceof Number number
                ? number.longValue()
                : Objects.requireNonNull(keys.getKey()).longValue();
        return findById(id);
    }

    public CancellationRequest findById(long id) {
        return jdbc.queryForObject("""
                SELECT id, user_id, email, name, contract_ref, kind, requested_end, reason,
                       effective_at, received_at
                FROM cancellation_requests WHERE id = ?
                """, MAPPER, id);
    }

    public void markConfirmed(long id) {
        jdbc.update("UPDATE cancellation_requests SET confirmed_at = NOW() WHERE id = ?", id);
    }

    /**
     * Records when the contract ends, and whether the declaration changed the
     * subscription here or was left to the operator.
     */
    public void recordEffect(long id, Instant effectiveAt, boolean applied) {
        jdbc.update("""
                UPDATE cancellation_requests
                SET effective_at = ?, applied_at = CASE WHEN ? THEN NOW() ELSE applied_at END
                WHERE id = ?
                """, effectiveAt == null ? null : Timestamp.from(effectiveAt), applied, id);
    }

    /** How many confirmation links an account was sent since then, used or not. */
    public int countTokensSince(long userId, Instant since) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cancellation_tokens WHERE user_id = ? AND created_at > ?",
                Integer.class, userId, Timestamp.from(since));
        return count == null ? 0 : count;
    }

    /** Stores the hash of a confirmation link's token. */
    public void insertToken(String tokenHash, long requestId, long userId, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO cancellation_tokens (token_hash, request_id, user_id, expires_at)
                VALUES (?, ?, ?, ?)
                """, tokenHash, requestId, userId, Timestamp.from(expiresAt));
    }

    /** A token that is unused and unexpired, without spending it. */
    public Optional<PendingToken> findUsableToken(String tokenHash) {
        return jdbc.query("""
                SELECT request_id, user_id FROM cancellation_tokens
                WHERE token_hash = ? AND used_at IS NULL AND expires_at > NOW()
                """, (rs, n) -> new PendingToken(rs.getLong("request_id"), rs.getLong("user_id")), tokenHash)
                .stream().findFirst();
    }

    /**
     * Spends a token. A single statement, so two clicks racing each other cannot
     * both get a row back.
     */
    public Optional<PendingToken> consumeToken(String tokenHash) {
        return jdbc.query("""
                UPDATE cancellation_tokens SET used_at = NOW()
                WHERE token_hash = ? AND used_at IS NULL AND expires_at > NOW()
                RETURNING request_id, user_id
                """, (rs, n) -> new PendingToken(rs.getLong("request_id"), rs.getLong("user_id")), tokenHash)
                .stream().findFirst();
    }

    /** What a confirmation link points at. */
    public record PendingToken(long requestId, long userId) {
    }
}
