package com.kindlerss.repository;

import com.kindlerss.domain.SyncAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** JDBC persistence for provider sync: the connection, remote ids and local change markers. */
@Repository
public class SyncRepository {

    /** An RSS feed as sync sees it. */
    public record SyncFeed(long id, String url, String title, String category, Long remoteId,
                           boolean categoryDirty) {}

    /** The columns of an article that sync needs to match and compare. */
    public record SyncArticle(long id, long feedId, long feedRemoteId, String guid, String url,
                              boolean read, boolean dirty, Long remoteId) {}

    public record DirtyArticle(long id, long remoteId, boolean read) {}

    private static final RowMapper<SyncAccount> ACCOUNT_MAPPER = (rs, rowNum) -> new SyncAccount(
            rs.getLong("user_id"),
            rs.getString("provider"),
            rs.getString("base_url"),
            rs.getString("username"),
            rs.getString("secret_encrypted"),
            rs.getString("cursor"),
            toInstant(rs.getTimestamp("last_synced_at")),
            rs.getString("last_error"));

    private final JdbcTemplate jdbc;

    public SyncRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- connection -----------------------------------------------------

    public Optional<SyncAccount> findAccount(long userId) {
        return jdbc.query("SELECT * FROM sync_accounts WHERE user_id = ?", ACCOUNT_MAPPER, userId)
                .stream().findFirst();
    }

    public List<SyncAccount> findAllAccounts() {
        return jdbc.query("SELECT * FROM sync_accounts ORDER BY user_id", ACCOUNT_MAPPER);
    }

    /** Stores a (new) connection and resets the cursor so the next run is a first sync. */
    public void saveAccount(long userId, String provider, String baseUrl, String username,
                            String secretEncrypted) {
        jdbc.update("""
                INSERT INTO sync_accounts (user_id, provider, base_url, username, secret_encrypted)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    provider = EXCLUDED.provider, base_url = EXCLUDED.base_url,
                    username = EXCLUDED.username, secret_encrypted = EXCLUDED.secret_encrypted,
                    cursor = NULL, last_error = NULL, updated_at = NOW()
                """, userId, provider, baseUrl, username, secretEncrypted);
    }

    public void deleteAccount(long userId) {
        jdbc.update("DELETE FROM sync_accounts WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM sync_feed_tombstones WHERE user_id = ?", userId);
        jdbc.update("""
                UPDATE articles SET remote_id = NULL, read_changed_at = NULL
                WHERE feed_id IN (SELECT id FROM feeds WHERE user_id = ?)
                """, userId);
        jdbc.update("UPDATE feeds SET remote_id = NULL, category_dirty = FALSE WHERE user_id = ?", userId);
    }

    public void markSynced(long userId, String cursor) {
        jdbc.update("""
                UPDATE sync_accounts
                SET cursor = ?, last_synced_at = NOW(), last_error = NULL, updated_at = NOW()
                WHERE user_id = ?
                """, cursor, userId);
    }

    public void setError(long userId, String error) {
        String truncated = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
        jdbc.update("UPDATE sync_accounts SET last_error = ?, updated_at = NOW() WHERE user_id = ?",
                truncated, userId);
    }

    // --- feeds ----------------------------------------------------------

    public List<SyncFeed> findRssFeeds(long userId) {
        return jdbc.query("""
                SELECT id, url, title, category, remote_id, category_dirty
                FROM feeds WHERE user_id = ? AND source = 'RSS' ORDER BY id
                """, (rs, n) -> new SyncFeed(rs.getLong("id"), rs.getString("url"), rs.getString("title"),
                rs.getString("category"), (Long) rs.getObject("remote_id"), rs.getBoolean("category_dirty")),
                userId);
    }

    public void setFeedRemote(long feedId, long remoteId) {
        jdbc.update("UPDATE feeds SET remote_id = ? WHERE id = ?", remoteId, feedId);
    }

    /** Applies the provider's category without marking it as a local change. */
    public void setFeedCategory(long feedId, String category) {
        jdbc.update("UPDATE feeds SET category = ?, category_dirty = FALSE, updated_at = NOW() WHERE id = ?",
                category, feedId);
    }

    public void clearCategoryDirty(long feedId) {
        jdbc.update("UPDATE feeds SET category_dirty = FALSE WHERE id = ?", feedId);
    }

    /** Removes a feed because the provider no longer has it; leaves no tombstone. */
    public void deleteFeed(long feedId) {
        jdbc.update("DELETE FROM feeds WHERE id = ?", feedId);
    }

    public List<Long> findTombstones(long userId) {
        return jdbc.queryForList("SELECT remote_id FROM sync_feed_tombstones WHERE user_id = ?",
                Long.class, userId);
    }

    public void clearTombstones(long userId) {
        jdbc.update("DELETE FROM sync_feed_tombstones WHERE user_id = ?", userId);
    }

    // --- articles -------------------------------------------------------

    /** Light view of every article in the account's linked feeds. */
    public List<SyncArticle> findLinkedArticles(long userId) {
        return jdbc.query("""
                SELECT a.id, a.feed_id, f.remote_id AS feed_remote_id, a.guid, a.url, a.read,
                       a.read_changed_at IS NOT NULL AS dirty, a.remote_id
                FROM articles a JOIN feeds f ON f.id = a.feed_id
                WHERE f.user_id = ? AND f.remote_id IS NOT NULL
                """, (rs, n) -> new SyncArticle(rs.getLong("id"), rs.getLong("feed_id"),
                rs.getLong("feed_remote_id"), rs.getString("guid"), rs.getString("url"),
                rs.getBoolean("read"), rs.getBoolean("dirty"), (Long) rs.getObject("remote_id")), userId);
    }

    /** First sync: whatever is already read locally is a change the provider has not seen. */
    public void markReadArticlesDirty(long userId) {
        jdbc.update("""
                UPDATE articles SET read_changed_at = NOW()
                WHERE read = TRUE AND read_changed_at IS NULL
                AND feed_id IN (SELECT id FROM feeds WHERE user_id = ? AND remote_id IS NOT NULL)
                """, userId);
    }

    public void linkArticle(long articleId, long remoteId) {
        jdbc.update("UPDATE articles SET remote_id = ? WHERE id = ?", remoteId, articleId);
    }

    /** Takes the provider's read state, discarding any pending local change. */
    public void applyRemoteState(long articleId, long remoteId, boolean read) {
        jdbc.update("""
                UPDATE articles
                SET remote_id = ?,
                    read_at = CASE WHEN ? THEN COALESCE(read_at, NOW()) ELSE NULL END,
                    read = ?, read_changed_at = NULL
                WHERE id = ?
                """, remoteId, read, read, articleId);
    }

    public List<DirtyArticle> findDirtyArticles(long userId) {
        return jdbc.query("""
                SELECT a.id, a.remote_id, a.read
                FROM articles a JOIN feeds f ON f.id = a.feed_id
                WHERE f.user_id = ? AND a.read_changed_at IS NOT NULL AND a.remote_id IS NOT NULL
                """, (rs, n) -> new DirtyArticle(rs.getLong("id"), rs.getLong("remote_id"),
                rs.getBoolean("read")), userId);
    }

    /**
     * Clears the pending marker of articles whose state was just pushed, unless the
     * reader flipped them again in the meantime.
     */
    public void clearDirty(Collection<Long> articleIds, boolean pushedRead) {
        if (articleIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(articleIds.size(), "?"));
        var args = new java.util.ArrayList<Object>(articleIds);
        args.add(pushedRead);
        jdbc.update("UPDATE articles SET read_changed_at = NULL WHERE id IN (" + placeholders
                + ") AND read = ?", args.toArray());
    }

    /**
     * Remote ids of linked feeds that hold recently stored articles the provider has
     * not been matched to yet, so their read state can be looked up directly.
     */
    public List<Long> findFeedsWithRecentUnmatchedArticles(long userId, Instant since) {
        return jdbc.queryForList("""
                SELECT DISTINCT f.remote_id
                FROM feeds f JOIN articles a ON a.feed_id = f.id
                WHERE f.user_id = ? AND f.remote_id IS NOT NULL
                AND a.remote_id IS NULL AND a.created_at >= ?
                """, Long.class, userId, Timestamp.from(since));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
