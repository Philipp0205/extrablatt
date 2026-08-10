package com.kindlerss.repository;

import com.kindlerss.domain.Article;
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
public class ArticleRepository {

    private static final RowMapper<Article> MAPPER = (rs, rowNum) -> new Article(
            rs.getLong("id"),
            rs.getLong("feed_id"),
            rs.getString("guid"),
            rs.getString("title"),
            rs.getString("url"),
            rs.getString("author"),
            toInstant(rs.getTimestamp("published_at")),
            rs.getString("summary_html"),
            rs.getString("feed_content_html"),
            rs.getString("extracted_content_html"),
            rs.getBoolean("read"),
            toInstant(rs.getTimestamp("sent_at")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            columnExists(rs, "feed_title") ? rs.getString("feed_title") : null
    );

    private final JdbcTemplate jdbc;

    public ArticleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Article> findById(long id) {
        var list = jdbc.query("""
                SELECT a.*, f.title AS feed_title
                FROM articles a
                JOIN feeds f ON f.id = a.feed_id
                WHERE a.id = ?
                """, MAPPER, id);
        return list.stream().findFirst();
    }

    public List<Article> findPage(Long feedId, Boolean unreadOnly, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.*, f.title AS feed_title
                FROM articles a
                JOIN feeds f ON f.id = a.feed_id
                WHERE 1=1
                """);
        var args = new java.util.ArrayList<>();
        if (feedId != null) {
            sql.append(" AND a.feed_id = ?");
            args.add(feedId);
        }
        if (Boolean.TRUE.equals(unreadOnly)) {
            sql.append(" AND a.read = FALSE");
        }
        sql.append(" ORDER BY a.published_at DESC NULLS LAST, a.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public long count(Long feedId, Boolean unreadOnly) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM articles a WHERE 1=1");
        var args = new java.util.ArrayList<>();
        if (feedId != null) {
            sql.append(" AND a.feed_id = ?");
            args.add(feedId);
        }
        if (Boolean.TRUE.equals(unreadOnly)) {
            sql.append(" AND a.read = FALSE");
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    public boolean existsByFeedIdAndGuid(long feedId, String guid) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM articles WHERE feed_id = ? AND guid = ?)
                """, Boolean.class, feedId, guid);
        return Boolean.TRUE.equals(exists);
    }

    public long insert(long feedId, String guid, String title, String url, String author,
                       Instant publishedAt, String summaryHtml, String feedContentHtml) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO articles (
                        feed_id, guid, title, url, author, published_at,
                        summary_html, feed_content_html
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (feed_id, guid) DO NOTHING
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, feedId);
            ps.setString(2, guid);
            ps.setString(3, title);
            ps.setString(4, url);
            ps.setString(5, author);
            ps.setTimestamp(6, publishedAt == null ? null : Timestamp.from(publishedAt));
            ps.setString(7, summaryHtml);
            ps.setString(8, feedContentHtml);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1 : key.longValue();
    }

    public void updateExtractedContent(long id, String extractedHtml) {
        jdbc.update("""
                UPDATE articles SET extracted_content_html = ?, updated_at = NOW() WHERE id = ?
                """, extractedHtml, id);
    }

    public void markRead(long id, boolean read) {
        jdbc.update("""
                UPDATE articles SET read = ?, updated_at = NOW() WHERE id = ?
                """, read, id);
    }

    public void markSent(long id, Instant sentAt) {
        jdbc.update("""
                UPDATE articles SET sent_at = ?, updated_at = NOW() WHERE id = ?
                """, Timestamp.from(sentAt), id);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static boolean columnExists(java.sql.ResultSet rs, String label) {
        try {
            rs.findColumn(label);
            return true;
        } catch (java.sql.SQLException e) {
            return false;
        }
    }
}
