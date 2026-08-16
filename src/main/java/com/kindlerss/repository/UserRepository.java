package com.kindlerss.repository;

import com.kindlerss.domain.AppUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** JDBC persistence for user accounts. */
@Repository
public class UserRepository {

    private static final RowMapper<AppUser> MAPPER = (rs, rowNum) -> new AppUser(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("kindle_email"),
            toInstant(rs.getTimestamp("email_verified_at")),
            toInstant(rs.getTimestamp("disabled_at")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findById(long id) {
        return jdbc.query("SELECT * FROM users WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<AppUser> findByEmail(String email) {
        return jdbc.query("SELECT * FROM users WHERE email = ?", MAPPER, normalizeEmail(email))
                .stream().findFirst();
    }

    /**
     * Inserts a new account. Throws {@link DuplicateKeyException} when the e-mail
     * is already taken, so callers can respond without leaking which addresses exist.
     */
    public AppUser insert(String email, String passwordHash) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO users (email, password_hash) VALUES (?, ?)
                    """, new String[]{"id"});
            ps.setString(1, normalizeEmail(email));
            ps.setString(2, passwordHash);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to insert user");
        }
        return findById(key.longValue()).orElseThrow();
    }

    /**
     * Confirms an address. Returns true only the first time — later calls (a
     * reused link, or the confirmation a password reset also implies) find the
     * column already set and change nothing, which callers use to send a welcome
     * message exactly once.
     */
    public boolean markEmailVerified(long id) {
        return jdbc.update("""
                UPDATE users SET email_verified_at = NOW(), updated_at = NOW()
                WHERE id = ? AND email_verified_at IS NULL
                """, id) > 0;
    }

    public void updatePasswordHash(long id, String passwordHash) {
        jdbc.update("""
                UPDATE users SET password_hash = ?, updated_at = NOW() WHERE id = ?
                """, passwordHash, id);
    }

    public void updateKindleEmail(long id, String kindleEmail) {
        jdbc.update("""
                UPDATE users SET kindle_email = ?, updated_at = NOW() WHERE id = ?
                """, kindleEmail, id);
    }

    public boolean deleteById(long id) {
        return jdbc.update("DELETE FROM users WHERE id = ?", id) > 0;
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
