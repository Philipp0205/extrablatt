package com.kindlerss.repository;

import com.kindlerss.domain.Article;
import com.kindlerss.domain.Feed;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
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

    /** Maps the list columns of {@link #findPage}; the three body fields are not selected and stay null. */
    private static final RowMapper<Article> LIST_MAPPER = (rs, rowNum) -> new Article(
            rs.getLong("id"),
            rs.getLong("feed_id"),
            rs.getString("guid"),
            rs.getString("title"),
            rs.getString("url"),
            rs.getString("author"),
            toInstant(rs.getTimestamp("published_at")),
            null,
            null,
            null,
            rs.getBoolean("read"),
            toInstant(rs.getTimestamp("sent_at")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            rs.getString("feed_title")
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

    /**
     * One page of the account's articles for list views. The returned articles carry no
     * body text: {@code summaryHtml}, {@code feedContentHtml} and {@code extractedContentHtml}
     * are always null. Callers that need content must use {@link #findById}.
     */
    public List<Article> findPage(long userId, Long feedId, String category, Boolean unreadOnly,
                                  Instant unreadSnapshot, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, a.feed_id, a.guid, a.title, a.url, a.author, a.published_at,
                       a.read, a.sent_at, a.created_at, a.updated_at, f.title AS feed_title
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
        return jdbc.query(sql.toString(), LIST_MAPPER, args.toArray());
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

    public Optional<Article> findByFeedIdAndGuid(long userId, long feedId, String guid) {
        var list = jdbc.query("""
                SELECT a.*, f.title AS feed_title
                FROM articles a
                JOIN feeds f ON f.id = a.feed_id
                WHERE a.feed_id = ? AND a.guid = ? AND f.user_id = ?
                """, MAPPER, feedId, guid, userId);
        return list.stream().findFirst();
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

    /**
     * Of the given article ids, those whose extracted content is missing: NULL or
     * only whitespace. One query with the ids as a single array parameter. It does
     * not check ownership; callers load each article with {@link #findById} first.
     */
    public List<Long> findIdsWithoutExtractedContent(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Long[] idArray = ids.toArray(Long[]::new);
        return jdbc.query(con -> {
            PreparedStatement statement = con.prepareStatement("""
                    SELECT id FROM articles
                    WHERE id = ANY(?)
                    AND (extracted_content_html IS NULL OR extracted_content_html !~ '\\S')
                    """);
            statement.setArray(1, con.createArrayOf("bigint", idArray));
            return statement;
        }, (rs, rowNum) -> rs.getLong("id"));
    }

    public void updateExtractedContent(long id, String extractedHtml) {
        jdbc.update("""
                UPDATE articles SET extracted_content_html = ?, updated_at = NOW() WHERE id = ?
                """, extractedHtml, id);
    }

    /** Refreshes the extracted body of a pasted-URL article, keeping the same row. */
    public void updateImportedContent(long id, String title, String author, String extractedHtml) {
        jdbc.update("""
                UPDATE articles
                SET title = ?, author = ?, extracted_content_html = ?, updated_at = NOW()
                WHERE id = ?
                """, title, author, extractedHtml, id);
    }

    public void markRead(long userId, long id, boolean read) {
        jdbc.update("""
                UPDATE articles
                SET read = ?, read_at = CASE WHEN ? THEN NOW() ELSE NULL END, updated_at = NOW()
                WHERE id = ? AND feed_id IN (SELECT id FROM feeds WHERE user_id = ?)
                """, read, read, id, userId);
    }

    /**
     * Marks every unread article in one of the account's feeds as read.
     * Returns how many articles actually changed state. A feed that is not
     * theirs (or does not exist) changes nothing.
     */
    public int markFeedRead(long userId, long feedId) {
        return jdbc.update("""
                UPDATE articles
                SET read = TRUE, read_at = NOW(), updated_at = NOW()
                WHERE feed_id = ? AND read = FALSE
                AND feed_id IN (SELECT id FROM feeds WHERE user_id = ?)
                """, feedId, userId);
    }

    /**
     * Returns how many of the account's articles actually changed state. The ids go
     * in as one array parameter rather than one placeholder each, because Postgres
     * takes at most 65,535 bind parameters per statement and "mark all read" on a
     * large account can pass more ids than that.
     */
    public int markRead(long userId, Collection<Long> ids, boolean read) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        Long[] idArray = ids.toArray(Long[]::new);
        return jdbc.update(con -> {
            PreparedStatement statement = con.prepareStatement("""
                    UPDATE articles
                    SET read = ?, read_at = CASE WHEN ? THEN NOW() ELSE NULL END, updated_at = NOW()
                    WHERE id = ANY(?) AND read <> ?
                    AND feed_id IN (SELECT id FROM feeds WHERE user_id = ?)
                    """);
            statement.setBoolean(1, read);
            statement.setBoolean(2, read);
            statement.setArray(3, con.createArrayOf("bigint", idArray));
            statement.setBoolean(4, read);
            statement.setLong(5, userId);
            return statement;
        });
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

    /**
     * The outcome of {@link #reserveSend}: the id of the send event now holding a
     * place in the quota, or which limit was already used up. Neither means the
     * account or the article is not theirs.
     */
    public record SendReservation(Long eventId, Limit limitReached) {
        public enum Limit { DAY, MONTH }

        public boolean reserved() {
            return eventId != null;
        }
    }

    /**
     * Takes one send out of the account's quota before the mail goes out, so that
     * two sends started at the same moment cannot both pass a count that only had
     * room for one. The account's row is locked for the length of the transaction,
     * which makes concurrent reservations for one account queue up behind each
     * other; the count is a separate statement because under READ COMMITTED a
     * statement's snapshot is taken before it waits for the lock, and would not see
     * the send that was just reserved. Other accounts are not held up.
     *
     * <p>{@code maxPerMonth} of 0 means no monthly cap. A reservation is a send
     * event like any other; {@link #releaseSend} takes it back if delivery fails.
     */
    @Transactional
    public SendReservation reserveSend(long userId, long articleId, Instant sentAt,
                                       int maxPerDay, Instant dayStart,
                                       int maxPerMonth, Instant monthStart) {
        List<Long> account = jdbc.queryForList(
                "SELECT id FROM users WHERE id = ? FOR NO KEY UPDATE", Long.class, userId);
        if (account.isEmpty()) {
            return new SendReservation(null, null);
        }
        Instant from = maxPerMonth > 0 && monthStart.isBefore(dayStart) ? monthStart : dayStart;
        long[] counts = jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE sent_at >= ?), COUNT(*) FILTER (WHERE sent_at >= ?)
                FROM article_send_events
                WHERE user_id = ? AND sent_at >= ?
                """, (rs, rowNum) -> new long[] {rs.getLong(1), rs.getLong(2)},
                Timestamp.from(dayStart), Timestamp.from(monthStart), userId, Timestamp.from(from));
        if (maxPerMonth > 0 && counts[1] >= maxPerMonth) {
            return new SendReservation(null, SendReservation.Limit.MONTH);
        }
        if (counts[0] >= maxPerDay) {
            return new SendReservation(null, SendReservation.Limit.DAY);
        }
        List<Long> eventId = jdbc.queryForList("""
                INSERT INTO article_send_events (user_id, article_id, sent_at)
                SELECT ?, ?, ?
                WHERE EXISTS (
                    SELECT 1 FROM articles a
                    JOIN feeds f ON f.id = a.feed_id
                    WHERE a.id = ? AND f.user_id = ?
                )
                RETURNING id
                """, Long.class, userId, articleId, Timestamp.from(sentAt), articleId, userId);
        return new SendReservation(eventId.isEmpty() ? null : eventId.getFirst(), null);
    }

    /** Gives a reserved send back to the quota, because the delivery did not happen. */
    public void releaseSend(long userId, long eventId) {
        jdbc.update("DELETE FROM article_send_events WHERE id = ? AND user_id = ?", eventId, userId);
    }

    /** After a reserved send went out: the article shows when it was last sent. */
    public void confirmSend(long userId, long articleId, Instant sentAt) {
        jdbc.update("""
                UPDATE articles
                SET sent_at = ?, updated_at = NOW()
                WHERE id = ? AND feed_id IN (SELECT id FROM feeds WHERE user_id = ?)
                """, Timestamp.from(sentAt), articleId, userId);
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
