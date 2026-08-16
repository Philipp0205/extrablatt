package com.kindlerss.repository;

import com.kindlerss.domain.Feed;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresRepositoryTest {

    private static EmbeddedPostgres postgres;
    private static FeedRepository feeds;
    private static ArticleRepository articles;
    private static UserRepository users;
    private static UserSendLimitRepository sendLimits;
    private static TelemetryRepository telemetry;
    private static long userId;
    private static long otherUserId;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        DataSource dataSource = postgres.getPostgresDatabase();
        Flyway.configure().dataSource(dataSource).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        feeds = new FeedRepository(jdbc);
        articles = new ArticleRepository(jdbc);
        users = new UserRepository(jdbc);
        sendLimits = new UserSendLimitRepository(jdbc);
        telemetry = new TelemetryRepository(jdbc);
        userId = users.insert("owner@example.com", "hash").id();
        otherUserId = users.insert("other@example.com", "hash").id();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    void marksAWholePageOfArticlesReadInOneStatement() {
        var feed = feeds.insert(userId, "Bulk", "https://bulk.example.com/feed.xml",
                "https://bulk.example.com", null);
        long first = insertArticle(feed.id(), "bulk-1");
        long second = insertArticle(feed.id(), "bulk-2");
        long untouched = insertArticle(feed.id(), "bulk-3");

        assertEquals(2, articles.markRead(userId, List.of(first, second), true));
        // Already read: nothing changes, so nothing is reported.
        assertEquals(0, articles.markRead(userId, List.of(first, second), true));
        assertEquals(0, articles.markRead(userId, List.of(), true));

        assertTrue(articles.findById(userId, first).orElseThrow().read());
        assertTrue(articles.findById(userId, second).orElseThrow().read());
        assertFalse(articles.findById(userId, untouched).orElseThrow().read());
    }

    @Test
    void insertsReturnOnlyTheGeneratedIdWithPostgres() {
        var feed = feeds.insert(userId, "Example", "https://example.com/feed.xml",
                "https://example.com", null);
        long articleId = articles.insert(
                feed.id(),
                "guid-1",
                "Article",
                "https://example.com/article",
                "Author",
                Instant.parse("2026-08-10T00:00:00Z"),
                "<p>Summary</p>",
                "<p>Content</p>"
        );

        assertTrue(feed.id() > 0);
        assertTrue(articleId > 0);
        assertEquals("Example", articles.findById(userId, articleId).orElseThrow().feedTitle());
    }

    @Test
    void feedsAndArticlesAreIsolatedPerUser() {
        var mine = feeds.insert(userId, "Mine", "https://iso.example.com/feed.xml",
                "https://iso.example.com", null);
        long articleId = insertArticle(mine.id(), "iso-1");

        // The same URL can be followed independently by another account.
        var theirs = feeds.insert(otherUserId, "Theirs", "https://iso.example.com/feed.xml",
                "https://iso.example.com", null);
        assertTrue(theirs.id() != mine.id());

        // The other account cannot see or mutate my feed or article.
        assertTrue(feeds.findById(otherUserId, mine.id()).isEmpty());
        assertTrue(articles.findById(otherUserId, articleId).isEmpty());
        assertEquals(0, articles.markRead(otherUserId, List.of(articleId), true));
        assertFalse(articles.findById(userId, articleId).orElseThrow().read());

        // My own list only counts my feeds.
        List<Feed> myFeeds = feeds.findAll(userId);
        assertTrue(myFeeds.stream().allMatch(f -> f.id().equals(mine.id())
                || !"Theirs".equals(f.title())));
        assertFalse(feeds.deleteById(otherUserId, mine.id()));
        assertTrue(feeds.deleteById(userId, mine.id()));
    }

    @Test
    void telemetryCountsSendsAndPersistsUserLimits() {
        var feed = feeds.insert(userId, "Metrics", "https://metrics.example.com/feed.xml",
                "https://metrics.example.com", null);
        long articleId = insertArticle(feed.id(), "metrics-1");
        articles.recordSend(userId, articleId, Instant.now());
        Instant blockedUntil = Instant.now().plusSeconds(3600);
        sendLimits.save(userId, 3, blockedUntil);

        var summary = telemetry.summary();
        assertTrue(summary.sendsTotal() >= 1);
        assertTrue(summary.sends24h() >= 1);

        var usage = telemetry.userUsage().stream()
                .filter(row -> row.userId() == userId)
                .findFirst().orElseThrow();
        assertTrue(usage.sendsTotal() >= 1);
        assertEquals(3, usage.maxSendsPerDay());
        assertTrue(usage.blocked());
        assertEquals(3, sendLimits.findByUserId(userId).orElseThrow().maxSendsPerDay());
    }

    @Test
    void newsletterFeedsAreFoundByInboundTokenAcrossAccountsAndCanRotateIt() {
        var newsletter = feeds.insertNewsletter(userId, "Stratechery", "Tech", "token-1");
        assertTrue(newsletter.isNewsletter());
        assertEquals("token-1", newsletter.inboundToken());

        var found = feeds.findByInboundToken("token-1");
        assertTrue(found.isPresent());
        assertEquals(newsletter.id(), found.get().id());
        assertTrue(feeds.findByInboundToken("no-such-token").isEmpty());

        // Rotating the address changes the token but not the feed's identity.
        assertTrue(feeds.updateInboundToken(userId, newsletter.id(), "token-2"));
        assertTrue(feeds.findByInboundToken("token-1").isEmpty());
        assertEquals(newsletter.id(), feeds.findByInboundToken("token-2").orElseThrow().id());

        // Another account cannot rotate a newsletter it does not own.
        assertFalse(feeds.updateInboundToken(otherUserId, newsletter.id(), "token-3"));
    }

    @Test
    void anIssueSentToANewslettersInboundAddressBecomesAnArticle() {
        var newsletter = feeds.insertNewsletter(userId, "Weekly Digest", null, "digest-token");
        long articleId = articles.insert(newsletter.id(), "message-id-1", "Issue #1", null, "Sender",
                Instant.parse("2026-08-10T00:00:00Z"), null, "<p>Hello</p>");

        assertTrue(articleId > 0);
        var stored = articles.findById(userId, articleId).orElseThrow();
        assertEquals("Weekly Digest", stored.feedTitle());
        assertEquals("Issue #1", stored.title());
    }

    private long insertArticle(long feedId, String guid) {
        return articles.insert(feedId, guid, "Article " + guid, "https://bulk.example.com/" + guid,
                "Author", Instant.parse("2026-08-10T00:00:00Z"), "<p>Summary</p>", "<p>Content</p>");
    }
}
