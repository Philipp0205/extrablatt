package com.kindlerss.repository;

import com.kindlerss.domain.Article;
import com.kindlerss.domain.Feed;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** JDBC persistence for articles, scoped to the owning account through feeds. */
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

    public Optional<Article> findById(long userId, long id) {
        var list = jdbc.query("""
                SELECT a.*, f.title AS feed_title
                FROM articles a
                JOIN feeds f ON f.id = a.feed_id
                WHERE a.id = ? AND f.user_id = ?
                """, MAPPER, id, userId);
        return list.stream().findFirst();
    }

    public List<Article> findPage(long userId, Long feedId, Boolean unreadOnly, int limit, int offset) {
        return findPage(userId, feedId, null, unreadOnly, null, limit, offset);
    }

    public List<Article> findPage(long userId, Long feedId, String category, Boolean unreadOnly,
                                  Instant unreadSnapshot, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.*, f.title AS feed_title
                FROM articles a
                JOIN feeds f ON f.id = a.feed_id
                WHERE f.user_id = ?
                """);
        var args = new java.util.ArrayList<>();
        args.add(userId);
        if (feedId != null) {
            sql.append(" AND a.feed_id = ?");
            args.add(feedId);
        }
        appendCategoryFilter(sql, args, category);
        if (Boolean.TRUE.equals(unreadOnly)) {
            sql.append(" AND (a.read = FALSE");
            if (unreadSnapshot != null) {
                sql.append(" OR a.read_at >= ?");
                args.add(Timestamp.from(unreadSnapshot));
            }
            sql.append(")");
        }
        sql.append(" ORDER BY a.published_at DESC NULLS LAST, a.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public long count(long userId, Long feedId, Boolean unreadOnly) {
        return count(userId, feedId, null, unreadOnly, null);
    }

    public long count(long userId, Long feedId, String category, Boolean unreadOnly, Instant unreadSnapshot) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM articles a
                JOIN feeds f ON f.id = a.feed_id
                WHERE f.user_id = ?
                """);
        var args = new java.util.ArrayList<>();
        args.add(userId);
        if (feedId != null) {
            sql.append(" AND a.feed_id = ?");
            args.add(feedId);
        }
        appendCategoryFilter(sql, args, category);
        if (Boolean.TRUE.equals(unreadOnly)) {
            sql.append(" AND (a.read = FALSE");
            if (unreadSnapshot != null) {
                sql.append(" OR a.read_at >= ?");
                args.add(Timestamp.from(unreadSnapshot));
            }
            sql.append(")");
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
                    """, new String[]{"id"});
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

    public void markRead(long userId, long id, boolean read) {
        jdbc.update("""
                UPDATE articles
                SET read = ?, read_at = CASE WHEN ? THEN NOW() ELSE NULL END, updated_at = NOW()
                WHERE id = ? AND feed_id IN (SELECT id FROM feeds WHERE user_id = ?)
                """, read, read, id, userId);
    }

    /** Returns how many of the account's articles actually changed state. */
    public int markRead(long userId, Collection<Long> ids, boolean read) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        var args = new java.util.ArrayList<Object>(ids.size() + 4);
        args.add(read);
        args.add(read);
        args.addAll(ids);
        args.add(read);
        args.add(userId);
        return jdbc.update("""
                UPDATE articles
                SET read = ?, read_at = CASE WHEN ? THEN NOW() ELSE NULL END, updated_at = NOW()
                WHERE id IN (%s) AND read <> ?
                AND feed_id IN (SELECT id FROM feeds WHERE user_id = ?)
                """.formatted(placeholders), args.toArray());
    }

    /** Records one successful delivery and updates the article's latest-send timestamp. */
    public void recordSend(long userId, long articleId, Instant sentAt) {
        jdbc.update("""
                WITH event AS (
                    INSERT INTO article_send_events (user_id, article_id, sent_at)
                    SELECT ?, ?, ?
                    WHERE EXISTS (
                        SELECT 1 FROM articles a
                        JOIN feeds f ON f.id = a.feed_id
                        WHERE a.id = ? AND f.user_id = ?
                    )
                    RETURNING sent_at
                )
                UPDATE articles
                SET sent_at = (SELECT sent_at FROM event), updated_at = NOW()
                WHERE id = ? AND EXISTS (SELECT 1 FROM event)
                """, userId, articleId, Timestamp.from(sentAt), articleId, userId, articleId);
    }

    /** How many successful deliveries an account made since a moment in time. */
    public long countSentSince(long userId, Instant since) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM article_send_events
                WHERE user_id = ? AND sent_at >= ?
                """, Long.class, userId, Timestamp.from(since));
        return count == null ? 0 : count;
    }

    /** How many successful deliveries an account has made in total, lifetime. */
    public long countSentTotal(long userId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM article_send_events WHERE user_id = ?
                """, Long.class, userId);
        return count == null ? 0 : count;
    }

    /** Feeds without a category of their own are browsed under one shared name. */
    private static void appendCategoryFilter(StringBuilder sql, List<Object> args, String category) {
        if (Feed.UNCATEGORIZED.equals(category)) {
            sql.append(" AND (f.category IS NULL OR f.category = '')");
        } else if (category != null && !category.isBlank()) {
            sql.append(" AND f.category = ?");
            args.add(category);
        }
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
