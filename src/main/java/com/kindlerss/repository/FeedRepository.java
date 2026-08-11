package com.kindlerss.repository;

import com.kindlerss.domain.Feed;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JDBC persistence for subscribed feeds, scoped to the owning account. */
@Repository
public class FeedRepository {

    private static final RowMapper<Feed> MAPPER = (rs, rowNum) -> new Feed(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("url"),
            rs.getString("site_url"),
            rs.getString("category"),
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
            rs.getString("category"),
            rs.getString("last_error"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    private final JdbcTemplate jdbc;

    public FeedRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Feed> findAllWithUnreadCounts(long userId) {
        return jdbc.query("""
                SELECT f.*, COALESCE(u.unread_count, 0) AS unread_count
                FROM feeds f
                LEFT JOIN (
                    SELECT feed_id, COUNT(*) AS unread_count
                    FROM articles
                    WHERE read = FALSE
                    GROUP BY feed_id
                ) u ON u.feed_id = f.id
                WHERE f.user_id = ?
                ORDER BY COALESCE(NULLIF(f.category, ''), 'Uncategorized') ASC, f.title ASC
                """, MAPPER, userId);
    }

    public List<Feed> findAll(long userId) {
        return jdbc.query("""
                SELECT * FROM feeds
                WHERE user_id = ?
                ORDER BY COALESCE(NULLIF(category, ''), 'Uncategorized') ASC, title ASC
                """, SIMPLE_MAPPER, userId);
    }

    /** Every feed across all accounts, for the scheduled refresh. */
    public List<Feed> findAllAcrossUsers() {
        return jdbc.query("""
                SELECT * FROM feeds
                ORDER BY id ASC
                """, SIMPLE_MAPPER);
    }

    public long countByUser(long userId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM feeds WHERE user_id = ?", Long.class, userId);
        return count == null ? 0 : count;
    }

    public Optional<Feed> findById(long userId, long id) {
        var list = jdbc.query("""
                SELECT *, 0 AS unread_count FROM feeds WHERE id = ? AND user_id = ?
                """, MAPPER, id, userId);
        return list.stream().findFirst();
    }

    public Optional<Feed> findByUrl(long userId, String url) {
        var list = jdbc.query("""
                SELECT *, 0 AS unread_count FROM feeds WHERE user_id = ? AND url = ?
                """, MAPPER, userId, url);
        return list.stream().findFirst();
    }

    public Feed insert(long userId, String title, String url, String siteUrl, String category) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO feeds (user_id, title, url, site_url, category)
                    VALUES (?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setLong(1, userId);
            ps.setString(2, title);
            ps.setString(3, url);
            ps.setString(4, siteUrl);
            ps.setString(5, normalizeCategory(category));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to insert feed");
        }
        return findById(userId, key.longValue()).orElseThrow();
    }

    public void updateTitleAndSite(long id, String title, String siteUrl) {
        jdbc.update("""
                UPDATE feeds SET title = ?, site_url = ?, updated_at = NOW() WHERE id = ?
                """, title, siteUrl, id);
    }

    public boolean updateCategory(long userId, long id, String category) {
        return jdbc.update("""
                UPDATE feeds SET category = ?, updated_at = NOW() WHERE id = ? AND user_id = ?
                """, normalizeCategory(category), id, userId) > 0;
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

    public boolean deleteById(long userId, long id) {
        return jdbc.update("DELETE FROM feeds WHERE id = ? AND user_id = ?", id, userId) > 0;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        return category.trim().substring(0, Math.min(category.trim().length(), 100));
    }
}
