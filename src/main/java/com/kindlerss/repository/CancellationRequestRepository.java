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
}
