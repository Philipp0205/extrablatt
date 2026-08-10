package com.kindlerss.repository;

import com.kindlerss.domain.Feed;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class FeedRepository {

    private static final RowMapper<Feed> MAPPER = (rs, rowNum) -> new Feed(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("url"),
            rs.getString("site_url"),
            rs.getString("last_error"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            rs.getLong("unread_count")
    );

    private static final RowMapper<Feed> SIMPLE_MAPPER = (rs, rowNum) -> new Feed(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("url"),
            rs.getString("site_url"),
            rs.getString("last_error"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    private final JdbcTemplate jdbc;

    public FeedRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Feed> findAllWithUnreadCounts() {
        return jdbc.query("""
                SELECT f.*, COALESCE(u.unread_count, 0) AS unread_count
                FROM feeds f
                LEFT JOIN (
                    SELECT feed_id, COUNT(*) AS unread_count
                    FROM articles
                    WHERE read = FALSE
                    GROUP BY feed_id
                ) u ON u.feed_id = f.id
                ORDER BY f.title ASC
                """, MAPPER);
    }

    public List<Feed> findAll() {
        return jdbc.query("""
                SELECT * FROM feeds ORDER BY title ASC
                """, SIMPLE_MAPPER);
    }

    public Optional<Feed> findById(long id) {
        var list = jdbc.query("""
                SELECT *, 0 AS unread_count FROM feeds WHERE id = ?
                """, MAPPER, id);
        return list.stream().findFirst();
    }

    public Optional<Feed> findByUrl(String url) {
        var list = jdbc.query("""
                SELECT *, 0 AS unread_count FROM feeds WHERE url = ?
                """, MAPPER, url);
        return list.stream().findFirst();
    }

    public Feed insert(String title, String url, String siteUrl) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO feeds (title, url, site_url)
                    VALUES (?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, title);
            ps.setString(2, url);
            ps.setString(3, siteUrl);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to insert feed");
        }
        return findById(key.longValue()).orElseThrow();
    }

    public void updateTitleAndSite(long id, String title, String siteUrl) {
        jdbc.update("""
                UPDATE feeds SET title = ?, site_url = ?, updated_at = NOW() WHERE id = ?
                """, title, siteUrl, id);
    }

    public void clearError(long id) {
        jdbc.update("""
                UPDATE feeds SET last_error = NULL, updated_at = NOW() WHERE id = ?
                """, id);
    }

    public void setError(long id, String error) {
        String truncated = error == null ? null : error.substring(0, Math.min(error.length(), 2000));
        jdbc.update("""
                UPDATE feeds SET last_error = ?, updated_at = NOW() WHERE id = ?
                """, truncated, id);
    }

    public boolean deleteById(long id) {
        return jdbc.update("DELETE FROM feeds WHERE id = ?", id) > 0;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
