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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresRepositoryTest {

    private static EmbeddedPostgres postgres;
    private static FeedRepository feeds;
    private static ArticleRepository articles;
    private static UserRepository users;
    private static UserSendLimitRepository sendLimits;
    private static TelemetryRepository telemetry;
    private static JdbcTemplate jdbc;
    private static long userId;
    private static long otherUserId;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        DataSource dataSource = postgres.getPostgresDatabase();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
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
    void accessibilityEditionTablesAndColumnsAreGone() {
        Integer preferenceTables = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'display_preferences'
                """, Integer.class);
        Integer savedAtColumns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'articles' AND column_name = 'saved_at'
                """, Integer.class);
        assertEquals(0, preferenceTables);
        assertEquals(0, savedAtColumns);
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

    /**
     * More ids than Postgres accepts bind parameters in one statement (65,535). Ids
     * that do not exist or are not the account's change nothing.
     */
    @Test
    void marksMoreArticlesReadThanAStatementHasPlaceholders() {
        var feed = feeds.insert(userId, "Huge", "https://huge.example.com/feed.xml",
                "https://huge.example.com", null);
        var theirs = feeds.insert(otherUserId, "Huge theirs", "https://huge-theirs.example.com/feed.xml",
                "https://huge-theirs.example.com", null);
        long mine = insertArticle(feed.id(), "huge-1");
        long stolen = insertArticle(theirs.id(), "huge-theirs-1");
        List<Long> ids = new java.util.ArrayList<>();
        for (long i = 0; i < 70_000; i++) {
            ids.add(-1 - i);
        }
        ids.add(mine);
        ids.add(stolen);

        assertEquals(1, articles.markRead(userId, ids, true));
        assertTrue(articles.findById(userId, mine).orElseThrow().read());
        assertFalse(articles.findById(otherUserId, stolen).orElseThrow().read());
        assertEquals(1, articles.markRead(userId, ids, false));
    }

    @Test
    void marksEveryUnreadArticleInAFeedAndLeavesOtherFeedsAlone() {
        var feed = feeds.insert(userId, "Mark all", "https://mark-all.example.com/feed.xml",
                "https://mark-all.example.com", null);
        var neighbor = feeds.insert(userId, "Mark neighbor", "https://mark-neighbor.example.com/feed.xml",
                "https://mark-neighbor.example.com", null);
        var theirs = feeds.insert(otherUserId, "Mark theirs", "https://mark-theirs.example.com/feed.xml",
                "https://mark-theirs.example.com", null);
        long first = insertArticle(feed.id(), "feed-1");
        long second = insertArticle(feed.id(), "feed-2");
        long already = insertArticle(feed.id(), "feed-3");
        long otherFeed = insertArticle(neighbor.id(), "neighbor-1");
        long stolen = insertArticle(theirs.id(), "theirs-1");
        articles.markRead(userId, already, true);

        assertEquals(2, articles.markFeedRead(userId, feed.id()));
        assertEquals(0, articles.markFeedRead(userId, feed.id()));
        assertEquals(0, articles.markFeedRead(otherUserId, feed.id()));

        assertTrue(articles.findById(userId, first).orElseThrow().read());
        assertTrue(articles.findById(userId, second).orElseThrow().read());
        assertTrue(articles.findById(userId, already).orElseThrow().read());
        assertFalse(articles.findById(userId, otherFeed).orElseThrow().read());
        assertFalse(articles.findById(otherUserId, stolen).orElseThrow().read());
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

    /**
     * Two sends racing for the last place in a quota. The first holds its
     * reservation open; the second must wait for it rather than count around it,
     * and then find the quota used up. Without the lock the second counts zero
     * sends, since the first is not committed yet, and goes ahead as well.
     */
    @Test
    void aSendRacingForTheLastPlaceWaitsAndIsRefused() throws Exception {
        // The repository's own DataSource: each getPostgresDatabase() is a new one, and
        // a transaction bound to another would leave every statement auto-committed.
        DataSource dataSource = jdbc.getDataSource();
        var transactional = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        long racer = users.insert("racer@example.com", "hash").id();
        var feed = feeds.insert(racer, "Race", "https://race.example.com/feed.xml",
                "https://race.example.com", null);
        long articleId = insertArticle(feed.id(), "race-1");
        Instant now = Instant.now();
        Instant dayAgo = now.minus(1, ChronoUnit.DAYS);
        var firstReserved = new java.util.concurrent.CountDownLatch(1);
        var letFirstCommit = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> transactional.execute(status -> {
                var reservation = articles.reserveSend(racer, articleId, now, 1, dayAgo, 0, now);
                firstReserved.countDown();
                try {
                    letFirstCommit.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return reservation;
            }));
            assertTrue(firstReserved.await(30, java.util.concurrent.TimeUnit.SECONDS));

            var second = pool.submit(() -> transactional.execute(status ->
                    articles.reserveSend(racer, articleId, now, 1, dayAgo, 0, now)));
            Thread.sleep(500);
            assertFalse(second.isDone(), "the second reservation must wait for the first");

            letFirstCommit.countDown();
            assertTrue(first.get(30, java.util.concurrent.TimeUnit.SECONDS).reserved());
            assertEquals(ArticleRepository.SendReservation.Limit.DAY,
                    second.get(30, java.util.concurrent.TimeUnit.SECONDS).limitReached());
            assertEquals(1, articles.countSentSince(racer, dayAgo));
        } finally {
            letFirstCommit.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aReservedSendCanBeGivenBackOrConfirmed() {
        long reader = users.insert("reserver@example.com", "hash").id();
        var feed = feeds.insert(reader, "Reserve", "https://reserve.example.com/feed.xml",
                "https://reserve.example.com", null);
        long articleId = insertArticle(feed.id(), "reserve-1");
        Instant now = Instant.now();
        Instant dayAgo = now.minus(1, ChronoUnit.DAYS);

        var first = articles.reserveSend(reader, articleId, now, 1, dayAgo, 0, now);
        assertTrue(first.reserved());
        assertEquals(ArticleRepository.SendReservation.Limit.DAY,
                articles.reserveSend(reader, articleId, now, 1, dayAgo, 0, now).limitReached());

        articles.releaseSend(reader, first.eventId());
        assertEquals(0, articles.countSentSince(reader, dayAgo));

        var second = articles.reserveSend(reader, articleId, now, 1, dayAgo, 0, now);
        assertTrue(second.reserved());
        articles.confirmSend(reader, articleId, now);
        assertTrue(articles.findById(reader, articleId).orElseThrow().sentAt() != null);

        // A monthly cap counts from the start of the month, not the last day.
        assertEquals(ArticleRepository.SendReservation.Limit.MONTH,
                articles.reserveSend(reader, articleId, now, 10, now.plusSeconds(1), 1, dayAgo).limitReached());
        // Someone else's article cannot be reserved against.
        assertFalse(articles.reserveSend(otherUserId, articleId, now, 10, dayAgo, 0, now).reserved());
    }

    @Test
    void telemetryCountsSendsAndPersistsUserLimits() {
        var feed = feeds.insert(userId, "Metrics", "https://metrics.example.com/feed.xml",
                "https://metrics.example.com", null);
        long articleId = insertArticle(feed.id(), "metrics-1");
        articles.recordSend(userId, articleId, Instant.now());
        long olderArticleId = insertArticle(feed.id(), "metrics-old");
        articles.recordSend(userId, olderArticleId, Instant.now().minus(40, ChronoUnit.DAYS));
        Instant blockedUntil = Instant.now().plusSeconds(3600);
        sendLimits.save(userId, 3, blockedUntil);
        Instant lastLogin = Instant.parse("2026-08-15T10:00:00Z");
        users.updateLastLoginAt(userId, lastLogin);

        var summary = telemetry.summary();
        assertTrue(summary.sendsTotal() >= 2);
        assertTrue(summary.sends24h() >= 1);
        assertTrue(summary.sends30d() >= 1);

        var usage = telemetry.userUsage().stream()
                .filter(row -> row.userId() == userId)
                .findFirst().orElseThrow();
        assertTrue(usage.sendsTotal() >= 2);
        assertTrue(usage.sends24h() >= 1);
        assertTrue(usage.sends30d() >= 1);
        assertTrue(usage.sendsTotal() > usage.sends30d(),
                "a send older than 30 days counts in the total but not the month");
        assertEquals(lastLogin, usage.lastLoginAt());
        assertEquals(3, usage.maxSendsPerDay());
        assertTrue(usage.blocked());
        assertEquals(3, sendLimits.findByUserId(userId).orElseThrow().maxSendsPerDay());
    }

    @Test
    void aNewsletterFeedIsFoundOrCreatedOncePerSenderPerAccount() {
        var first = feeds.findOrCreateNewsletterFeed(userId, "newsletter:editor@example.com",
                "Stratechery", "Newsletters");
        assertTrue(first.isNewsletter());
        assertEquals("editor@example.com", first.newsletterSender());

        // A second issue from the same sender reuses the feed instead of duplicating it.
        var again = feeds.findOrCreateNewsletterFeed(userId, "newsletter:editor@example.com",
                "Stratechery", "Newsletters");
        assertEquals(first.id(), again.id());

        // The same sender can independently become a feed for a different account.
        var theirs = feeds.findOrCreateNewsletterFeed(otherUserId, "newsletter:editor@example.com",
                "Stratechery", "Newsletters");
        assertTrue(theirs.id() != first.id());
    }

    @Test
    void aClippingFeedIsFoundOrCreatedOncePerAccount() {
        var first = feeds.findOrCreateClippingFeed(userId);
        assertTrue(first.isClipping());
        assertEquals("clippings:", first.url());
        assertEquals("Pasted URLs", first.title());

        var again = feeds.findOrCreateClippingFeed(userId);
        assertEquals(first.id(), again.id());

        var theirs = feeds.findOrCreateClippingFeed(otherUserId);
        assertTrue(theirs.id() != first.id());
    }

    @Test
    void anIssueSentToTheNewslettersInboxBecomesAnArticleOfItsAutoCreatedFeed() {
        var newsletter = feeds.findOrCreateNewsletterFeed(userId, "newsletter:weekly@example.com",
                "Weekly Digest", "Newsletters");
        long articleId = articles.insert(newsletter.id(), "message-id-1", "Issue #1", null, "Sender",
                Instant.parse("2026-08-10T00:00:00Z"), null, "<p>Hello</p>");

        assertTrue(articleId > 0);
        var stored = articles.findById(userId, articleId).orElseThrow();
        assertEquals("Weekly Digest", stored.feedTitle());
        assertEquals("Issue #1", stored.title());
    }

    @Test
    void anAccountsNewsletterInboxTokenIsGeneratedOnceAndFoundByIt() {
        var third = users.insert("third@example.com", "hash");
        assertTrue(users.setNewsletterInboundTokenIfAbsent(third.id(), "inbox-token"));
        // A concurrent/second attempt to set it must not clobber the winning token.
        assertFalse(users.setNewsletterInboundTokenIfAbsent(third.id(), "other-token"));

        var found = users.findByNewsletterInboundToken("inbox-token");
        assertTrue(found.isPresent());
        assertEquals(third.id(), found.get().id());
        assertTrue(users.findByNewsletterInboundToken("no-such-token").isEmpty());

        users.updateNewsletterInboundToken(third.id(), "rotated-token");
        assertTrue(users.findByNewsletterInboundToken("inbox-token").isEmpty());
        assertEquals(third.id(), users.findByNewsletterInboundToken("rotated-token").orElseThrow().id());
    }

    @Test
    void markReadOnNextPageIsStoredOnTheAccountAndLookedUpByFeed() {
        var feed = feeds.insert(userId, "Pref", "https://pref.example.com/feed.xml",
                "https://pref.example.com", null);

        assertTrue(users.findById(userId).orElseThrow().markReadOnNextPage());
        assertEquals(Optional.of(true), users.findMarkReadOnNextPageByFeedId(feed.id()));
        assertEquals(Optional.of(userId), users.findIdByFeedId(feed.id()));

        users.updateMarkReadOnNextPage(userId, false);
        assertFalse(users.findById(userId).orElseThrow().markReadOnNextPage());
        assertEquals(Optional.of(false), users.findMarkReadOnNextPageByFeedId(feed.id()));
        assertTrue(users.findById(otherUserId).orElseThrow().markReadOnNextPage());
    }

    @Test
    void lastSeenChangelogIdIsStoredOnTheAccount() {
        assertNull(users.findById(userId).orElseThrow().lastSeenChangelogId());
        users.updateLastSeenChangelogId(userId, "2026-08-31");
        assertEquals("2026-08-31", users.findById(userId).orElseThrow().lastSeenChangelogId());
        assertNull(users.findById(otherUserId).orElseThrow().lastSeenChangelogId());
    }

    @Test
    void renamingACategoryMovesTheAccountsFeedsAndOnlyThoseThatMatchTheNameExactly() {
        var upper = feeds.insert(userId, "Upper", "https://upper.example.com/feed.xml",
                "https://upper.example.com", "Technology");
        var second = feeds.insert(userId, "Second", "https://second.example.com/feed.xml",
                "https://second.example.com", "Technology");
        var lower = feeds.insert(userId, "Lower", "https://lower.example.com/feed.xml",
                "https://lower.example.com", "technology");
        var theirs = feeds.insert(otherUserId, "Theirs", "https://theirs.example.com/feed.xml",
                "https://theirs.example.com", "Technology");

        assertEquals(2, feeds.renameCategory(userId, "Technology", "Tech"));
        assertEquals("Tech", feeds.findById(userId, upper.id()).orElseThrow().category());
        assertEquals("Tech", feeds.findById(userId, second.id()).orElseThrow().category());
        assertEquals("technology", feeds.findById(userId, lower.id()).orElseThrow().category(),
                "a differently capitalized category is a category of its own");
        assertEquals("Technology", feeds.findById(otherUserId, theirs.id()).orElseThrow().category(),
                "another account's feeds are not touched");

        // Capitalization is part of the name, so correcting it is a rename too.
        assertEquals(1, feeds.renameCategory(userId, "technology", "Technology"));
        assertEquals("Technology", feeds.findById(userId, lower.id()).orElseThrow().category());

        assertEquals(0, feeds.renameCategory(userId, "No Such Category", "Tech"));

        feeds.deleteById(userId, upper.id());
        feeds.deleteById(userId, second.id());
        feeds.deleteById(userId, lower.id());
        feeds.deleteById(otherUserId, theirs.id());
    }

    @Test
    void aSubscriptionIsStoredAndFoundByEitherProviderIdentifier() {
        var repository = new SubscriptionRepository(new JdbcTemplate(postgres.getPostgresDatabase()));
        var end = Instant.parse("2027-03-01T00:00:00Z");
        var subscription = new com.kindlerss.domain.Subscription(userId,
                com.kindlerss.domain.Plan.SUPPORTER,
                com.kindlerss.domain.SubscriptionStatus.ACTIVE,
                com.kindlerss.domain.BillingInterval.YEARLY,
                "stripe", "cus_1", "sub_1", end, false, Instant.parse("2026-03-01T00:00:00Z"));

        assertTrue(repository.findByUserId(userId).isEmpty());
        repository.save(subscription);

        assertEquals(subscription, repository.findByUserId(userId).orElseThrow());
        assertEquals(userId, repository.findByProviderSubscriptionId("sub_1").orElseThrow().userId());
        assertEquals(userId, repository.findByProviderCustomerId("cus_1").orElseThrow().userId());
        assertTrue(repository.findByUserId(otherUserId).isEmpty());
    }

    /**
     * A later event often carries only what changed. Saving one must not wipe the
     * provider identifiers or the withdrawal consent that arrived with the order.
     */
    @Test
    void savingAnUpdateKeepsIdentifiersAndConsentItDoesNotMention() {
        var repository = new SubscriptionRepository(new JdbcTemplate(postgres.getPostgresDatabase()));
        long id = users.insert("keeps@example.com", "hash").id();
        var consentAt = Instant.parse("2026-03-01T00:00:00Z");
        repository.save(new com.kindlerss.domain.Subscription(id,
                com.kindlerss.domain.Plan.SUPPORTER,
                com.kindlerss.domain.SubscriptionStatus.ACTIVE,
                com.kindlerss.domain.BillingInterval.YEARLY,
                "stripe", "cus_2", "sub_2", Instant.parse("2027-03-01T00:00:00Z"), false, consentAt));

        repository.save(new com.kindlerss.domain.Subscription(id,
                com.kindlerss.domain.Plan.SUPPORTER,
                com.kindlerss.domain.SubscriptionStatus.PAST_DUE,
                null, "stripe", null, null, Instant.parse("2027-03-01T00:00:00Z"), true, null));

        var stored = repository.findByUserId(id).orElseThrow();
        assertEquals(com.kindlerss.domain.SubscriptionStatus.PAST_DUE, stored.status());
        assertEquals("cus_2", stored.providerCustomerId());
        assertEquals("sub_2", stored.providerSubscriptionId());
        assertEquals(consentAt, stored.withdrawalConsentAt());
        assertTrue(stored.cancelAtPeriodEnd());
    }

    @Test
    void lapsedSubscriptionsAreTheOnesWhosePaidPeriodHasPassed() {
        var repository = new SubscriptionRepository(new JdbcTemplate(postgres.getPostgresDatabase()));
        long lapsed = users.insert("lapsed@example.com", "hash").id();
        long current = users.insert("current@example.com", "hash").id();
        repository.save(new com.kindlerss.domain.Subscription(lapsed,
                com.kindlerss.domain.Plan.SUPPORTER,
                com.kindlerss.domain.SubscriptionStatus.ACTIVE,
                com.kindlerss.domain.BillingInterval.MONTHLY, "stripe", "cus_3", "sub_3",
                Instant.parse("2020-01-01T00:00:00Z"), false, null));
        repository.save(new com.kindlerss.domain.Subscription(current,
                com.kindlerss.domain.Plan.SUPPORTER,
                com.kindlerss.domain.SubscriptionStatus.ACTIVE,
                com.kindlerss.domain.BillingInterval.MONTHLY, "stripe", "cus_4", "sub_4",
                Instant.parse("2099-01-01T00:00:00Z"), false, null));

        var found = repository.findLapsed(Instant.parse("2026-01-01T00:00:00Z")).stream()
                .map(com.kindlerss.domain.Subscription::userId).toList();

        assertTrue(found.contains(lapsed));
        assertFalse(found.contains(current));
    }

    /** The provider's event id is the lock, so a replay cannot be claimed twice. */
    @Test
    void aBillingEventCanOnlyBeClaimedOnce() {
        var repository = new BillingEventRepository(new JdbcTemplate(postgres.getPostgresDatabase()));

        assertTrue(claim(repository, "evt_once", "sub_once", null, 5));
        // Still in progress, and later processed: neither may be claimed again.
        assertFalse(claim(repository, "evt_once", "sub_once", null, 5));
        repository.markProcessed("evt_once");
        assertFalse(claim(repository, "evt_once", "sub_once", null, 5));
    }

    /**
     * A failed event is claimed again by the provider's redelivery, and scheduled for
     * the sweep with a backoff, until it is out of attempts.
     */
    @Test
    void aFailedBillingEventIsRetriedUntilItIsOutOfAttempts() {
        JdbcTemplate jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        var repository = new BillingEventRepository(jdbc);

        assertTrue(claim(repository, "evt_retry", "sub_retry", null, 3));
        assertTrue(repository.markFailed("evt_retry", "No account matches", 3, java.time.Duration.ofMinutes(1)));
        // Not due yet: the backoff has not run out.
        assertFalse(dueIds(repository, 3).contains("evt_retry"));
        assertFalse(repository.reclaim("evt_retry", 3));

        jdbc.update("UPDATE billing_events SET next_attempt_at = NOW() - INTERVAL '1 second'"
                + " WHERE provider_event_id = 'evt_retry'");
        assertTrue(dueIds(repository, 3).contains("evt_retry"));
        assertTrue(repository.reclaim("evt_retry", 3));
        // Claimed by the sweep: a concurrent redelivery must not apply it as well.
        assertFalse(claim(repository, "evt_retry", "sub_retry", null, 3));
        assertTrue(repository.markFailed("evt_retry", "still nothing", 3, java.time.Duration.ofMinutes(1)));

        // A redelivery claims it straight away; that is the third and last attempt.
        assertTrue(claim(repository, "evt_retry", "sub_retry", null, 3));
        assertFalse(repository.markFailed("evt_retry", "gave up", 3, java.time.Duration.ofMinutes(1)));
        assertFalse(claim(repository, "evt_retry", "sub_retry", null, 3));
        assertFalse(dueIds(repository, 3).contains("evt_retry"));
        assertEquals(1, count(jdbc, "SELECT count(*) FROM billing_events WHERE provider_event_id = 'evt_retry'"
                + " AND attempts = 3 AND error = 'gave up' AND next_attempt_at IS NULL"));
    }

    /** The backoff doubles with every attempt, and never beyond six hours. */
    @Test
    void theRetryBackoffDoublesAndIsCapped() {
        JdbcTemplate jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        var repository = new BillingEventRepository(jdbc);
        claim(repository, "evt_backoff", "sub_backoff", null, 50);

        jdbc.update("UPDATE billing_events SET attempts = 3 WHERE provider_event_id = 'evt_backoff'");
        repository.markFailed("evt_backoff", "x", 50, java.time.Duration.ofMinutes(1));
        assertEquals(4 * 60, waitSeconds(jdbc, "evt_backoff"), 5);

        jdbc.update("UPDATE billing_events SET attempts = 20 WHERE provider_event_id = 'evt_backoff'");
        repository.markFailed("evt_backoff", "x", 50, java.time.Duration.ofMinutes(1));
        assertEquals(6 * 3600, waitSeconds(jdbc, "evt_backoff"), 5);
    }

    /** A process that died mid-apply leaves a claim that is taken over once stale. */
    @Test
    void aStaleBillingClaimIsTakenOver() {
        JdbcTemplate jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        var repository = new BillingEventRepository(jdbc);
        claim(repository, "evt_stale", "sub_stale", null, 5);

        jdbc.update("UPDATE billing_events SET claimed_at = NOW() - INTERVAL '11 minutes'"
                + " WHERE provider_event_id = 'evt_stale'");

        assertTrue(dueIds(repository, 5).contains("evt_stale"));
        assertTrue(repository.reclaim("evt_stale", 5));
    }

    /** Redacted payloads cannot be re-read, so the sweep leaves them to the provider. */
    @Test
    void aRedactedBillingEventIsNotRetriedFromItsPayload() {
        JdbcTemplate jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        var repository = new BillingEventRepository(jdbc);
        claim(repository, "evt_redacted", "sub_redacted", null, 5);
        repository.markFailed("evt_redacted", "x", 5, java.time.Duration.ofMinutes(1));
        jdbc.update("UPDATE billing_events SET payload = '', next_attempt_at = NOW() - INTERVAL '1 second'"
                + " WHERE provider_event_id = 'evt_redacted'");

        assertFalse(dueIds(repository, 5).contains("evt_redacted"));
    }

    @Test
    void anOlderBillingEventIsSupersededByANewerAppliedOne() {
        var repository = new BillingEventRepository(new JdbcTemplate(postgres.getPostgresDatabase()));
        Instant older = Instant.parse("2026-11-01T10:00:00Z");
        Instant newer = Instant.parse("2026-11-01T11:00:00Z");
        claim(repository, "evt_sup_new", "sub_sup", newer, 5);

        // Not applied yet, so it supersedes nothing.
        assertFalse(repository.supersededByNewer("evt_sup_old", "sub_sup", older));
        repository.markProcessed("evt_sup_new");

        assertTrue(repository.supersededByNewer("evt_sup_old", "sub_sup", older));
        assertFalse(repository.supersededByNewer("evt_sup_newest", "sub_sup", newer.plusSeconds(1)));
        assertFalse(repository.supersededByNewer("evt_sup_old", "sub_other", older));
        assertFalse(repository.supersededByNewer("evt_sup_old", "sub_sup", null));
    }

    /** A checkout carries no subscription state, so it cannot make a later update stale. */
    @Test
    void aCheckoutNeverSupersedesASubscriptionEvent() {
        var repository = new BillingEventRepository(new JdbcTemplate(postgres.getPostgresDatabase()));
        Instant created = Instant.parse("2026-11-01T10:00:00Z");
        repository.claim("evt_checkout", "stripe", "checkout.session.completed", "{}", userId,
                "sub_checkout", created.plusSeconds(5), 5);
        repository.markProcessed("evt_checkout");

        assertFalse(repository.supersededByNewer("evt_created", "sub_checkout", created));
    }

    private static boolean claim(BillingEventRepository repository, String eventId, String subscriptionRef,
                                 Instant occurredAt, int maxAttempts) {
        return repository.claim(eventId, "stripe", "customer.subscription.updated", "{}", userId,
                subscriptionRef, occurredAt, maxAttempts);
    }

    private static List<String> dueIds(BillingEventRepository repository, int maxAttempts) {
        return repository.findDueRetries(maxAttempts, 1_000).stream()
                .map(BillingEventRepository.DueRetry::providerEventId).toList();
    }

    private static double waitSeconds(JdbcTemplate jdbc, String eventId) {
        return jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM next_attempt_at - NOW()) FROM billing_events"
                + " WHERE provider_event_id = ?", Double.class, eventId);
    }

    @Test
    void aCancellationDeclarationIsStoredWithWhenItArrived() {
        var repository = new CancellationRequestRepository(
                new JdbcTemplate(postgres.getPostgresDatabase()));

        var stored = repository.insert(userId, "owner@example.com", "A Reader", "ref-1",
                com.kindlerss.domain.CancellationRequest.Kind.IMMEDIATE,
                java.time.LocalDate.parse("2027-01-31"), "too many newsletters",
                Instant.parse("2027-01-31T00:00:00Z"));

        assertEquals("owner@example.com", stored.email());
        assertEquals(com.kindlerss.domain.CancellationRequest.Kind.IMMEDIATE, stored.kind());
        assertEquals("too many newsletters", stored.reason());
        assertTrue(stored.receivedAt() != null, "the time it arrived is what gets confirmed");
        repository.markConfirmed(stored.id());
    }

    /** Somebody cancelling without an account still has their declaration recorded. */
    @Test
    void aCancellationWithNoMatchingAccountIsStillRecorded() {
        var repository = new CancellationRequestRepository(
                new JdbcTemplate(postgres.getPostgresDatabase()));

        var stored = repository.insert(null, "stranger@example.com", null, null,
                com.kindlerss.domain.CancellationRequest.Kind.ORDINARY, null, null, null);

        assertEquals(null, stored.userId());
        assertEquals("stranger@example.com", stored.email());
    }

    /**
     * A cancellation link is spent once and only once, looking at it does not spend
     * it, and an expired one does nothing.
     */
    @Test
    void aCancellationLinkWorksOnceAndOnlyBeforeItExpires() {
        JdbcTemplate jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        long owner = users.insert("link-owner@example.com", "hash").id();
        var repository = new CancellationRequestRepository(jdbc);
        var stored = repository.insert(owner, "link-owner@example.com", null, null,
                com.kindlerss.domain.CancellationRequest.Kind.ORDINARY, null, null, null);
        repository.insertToken("hash-live", stored.id(), owner, Instant.now().plusSeconds(3600));
        repository.insertToken("hash-old", stored.id(), owner, Instant.now().minusSeconds(1));

        assertTrue(repository.findUsableToken("hash-live").isPresent());
        assertTrue(repository.findUsableToken("hash-live").isPresent(), "peeking must not spend it");
        var spent = repository.consumeToken("hash-live").orElseThrow();
        assertEquals(stored.id(), spent.requestId());
        assertEquals(owner, spent.userId());
        assertTrue(repository.consumeToken("hash-live").isEmpty());
        assertTrue(repository.findUsableToken("hash-live").isEmpty());

        assertTrue(repository.findUsableToken("hash-old").isEmpty());
        assertTrue(repository.consumeToken("hash-old").isEmpty());
        assertTrue(repository.consumeToken("unknown").isEmpty());
        assertEquals(2, repository.countTokensSince(owner, Instant.now().minusSeconds(60)));

        repository.recordEffect(stored.id(), Instant.parse("2027-01-31T00:00:00Z"), true);
        assertEquals(Instant.parse("2027-01-31T00:00:00Z"), repository.findById(stored.id()).effectiveAt());
        assertEquals(1, count(jdbc,
                "SELECT count(*) FROM cancellation_requests WHERE id = ? AND applied_at IS NOT NULL",
                stored.id()));

        assertTrue(users.deleteById(owner));
        assertEquals(0, count(jdbc, "SELECT count(*) FROM cancellation_tokens WHERE user_id = ?", owner));
    }

    /**
     * The guarantee behind "Delete account": everything linked to the account goes, in
     * one statement, through the cascades. Written as one test over every table that
     * holds personal data, because a migration that adds a table without a cascade
     * would otherwise be invisible until somebody asked to be forgotten.
     */
    @Test
    void deletingAnAccountRemovesEverythingLinkedToIt() {
        JdbcTemplate jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        long doomed = users.insert("doomed@example.com", "hash").id();

        var feed = feeds.insert(doomed, "Doomed", "https://doomed.example.com/feed.xml",
                "https://doomed.example.com", "News");
        long articleId = articles.insert(feed.id(), "doomed-1", "Article", "https://doomed.example.com/1",
                "Author", Instant.parse("2026-08-10T00:00:00Z"), "<p>s</p>", "<p>c</p>");
        articles.recordSend(doomed, articleId, Instant.now());
        sendLimits.save(doomed, 7, null);
        new SubscriptionRepository(jdbc).save(new com.kindlerss.domain.Subscription(doomed,
                com.kindlerss.domain.Plan.SUPPORTER,
                com.kindlerss.domain.SubscriptionStatus.ACTIVE,
                com.kindlerss.domain.BillingInterval.YEARLY, "stripe", "cus_doomed", "sub_doomed",
                Instant.parse("2027-01-01T00:00:00Z"), false, Instant.now()));
        new BillingEventRepository(jdbc)
                .claim("evt_doomed", "stripe", "invoice.paid", "{\"email\":\"doomed@example.com\"}", doomed,
                        null, null, 5);

        assertTrue(users.deleteById(doomed));

        assertEquals(0, count(jdbc, "SELECT count(*) FROM feeds WHERE user_id = ?", doomed));
        assertEquals(0, count(jdbc, "SELECT count(*) FROM articles WHERE id = ?", articleId));
        assertEquals(0, count(jdbc, "SELECT count(*) FROM article_send_events WHERE user_id = ?", doomed));
        assertEquals(0, count(jdbc, "SELECT count(*) FROM user_send_limits WHERE user_id = ?", doomed));
        assertEquals(0, count(jdbc, "SELECT count(*) FROM subscriptions WHERE user_id = ?", doomed));
        assertEquals(0, count(jdbc, "SELECT count(*) FROM email_tokens WHERE user_id = ?", doomed));

        // The payment event id survives on purpose — it is what stops a replayed
        // webhook being applied twice — but it must no longer point at anybody.
        assertEquals(1, count(jdbc,
                "SELECT count(*) FROM billing_events WHERE provider_event_id = 'evt_doomed'"));
        assertEquals(0, count(jdbc,
                "SELECT count(*) FROM billing_events WHERE user_id = ?", doomed));
    }

    /** A cancellation record is the one thing kept, and it must lose its account link. */
    @Test
    void aCancellationRecordOutlivesTheAccountButNotTheLinkToIt() {
        JdbcTemplate jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        long leaving = users.insert("leaving@example.com", "hash").id();
        var repository = new CancellationRequestRepository(jdbc);
        var stored = repository.insert(leaving, "leaving@example.com", null, null,
                com.kindlerss.domain.CancellationRequest.Kind.ORDINARY, null, null, Instant.now());

        assertTrue(users.deleteById(leaving));

        assertEquals(1, count(jdbc, "SELECT count(*) FROM cancellation_requests WHERE id = ?",
                stored.id()));
        assertEquals(1, count(jdbc,
                "SELECT count(*) FROM cancellation_requests WHERE id = ? AND user_id IS NULL",
                stored.id()));
    }

    /** Every sweep in the retention policy, against rows old enough to be swept. */
    @Test
    void theRetentionSweepClearsWhatItSaysItClears() {
        JdbcTemplate jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        var repository = new RetentionRepository(jdbc);
        long ancient = users.insert("ancient@example.com", "hash").id();
        var feed = feeds.insert(ancient, "Ancient", "https://ancient.example.com/feed.xml",
                "https://ancient.example.com", null);
        long articleId = articles.insert(feed.id(), "ancient-1", "Old", "https://ancient.example.com/1",
                null, Instant.parse("2020-01-01T00:00:00Z"), "<p>s</p>", "<p>c</p>");
        articles.updateExtractedContent(articleId, "<p>extracted</p>");
        articles.markRead(ancient, articleId, true);
        articles.recordSend(ancient, articleId, Instant.parse("2020-01-02T00:00:00Z"));
        jdbc.update("UPDATE articles SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")), articleId);
        jdbc.update("""
                INSERT INTO email_tokens (token, user_id, purpose, expires_at, used_at)
                VALUES ('spent-token', ?, 'RESET', ?, ?)
                """, ancient,
                java.sql.Timestamp.from(Instant.parse("2020-01-03T00:00:00Z")),
                java.sql.Timestamp.from(Instant.parse("2020-01-02T00:00:00Z")));
        var ancientRequest = new CancellationRequestRepository(jdbc).insert(ancient,
                "ancient@example.com", null, null,
                com.kindlerss.domain.CancellationRequest.Kind.ORDINARY, null, null, null);
        new CancellationRequestRepository(jdbc).insertToken("ancient-hash", ancientRequest.id(),
                ancient, Instant.parse("2020-01-03T00:00:00Z"));
        new BillingEventRepository(jdbc).claim("evt_ancient", "stripe", "invoice.paid",
                "{\"email\":\"ancient@example.com\"}", ancient, null, null, 5);
        jdbc.update("UPDATE billing_events SET received_at = ? WHERE provider_event_id = 'evt_ancient'",
                java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));

        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        assertEquals(1, repository.deleteSendEventsBefore(cutoff));
        assertEquals(1, repository.redactBillingPayloadsBefore(cutoff));
        assertEquals(2, repository.deleteSpentTokensBefore(cutoff));
        assertEquals(1, repository.clearStaleArticleCacheBefore(cutoff));

        // Redacting is not deleting: the id has to stay, so a replay is still a no-op.
        assertEquals(1, count(jdbc,
                "SELECT count(*) FROM billing_events WHERE provider_event_id = 'evt_ancient' AND payload = ''"));
        // The article itself stays; only its cached text goes, and that is re-extracted.
        assertEquals(1, count(jdbc, "SELECT count(*) FROM articles WHERE id = ?", articleId));
        assertTrue(articles.findById(ancient, articleId).orElseThrow()
                .extractedContentHtml() == null);
        // Running it again finds nothing left to do.
        assertEquals(0, repository.deleteSendEventsBefore(cutoff));
        assertEquals(0, repository.redactBillingPayloadsBefore(cutoff));
    }

    /**
     * An unread article keeps its text however old it gets: clearing it would make the
     * first read of a backlog article wait on a refetch that may no longer resolve.
     */
    @Test
    void theSweepLeavesUnreadArticlesAlone() {
        JdbcTemplate jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        var repository = new RetentionRepository(jdbc);
        long keeper = users.insert("keeper@example.com", "hash").id();
        var feed = feeds.insert(keeper, "Keeper", "https://keeper.example.com/feed.xml", null, null);
        long unread = articles.insert(feed.id(), "keep-2", "Unread", "https://keeper.example.com/2",
                null, null, "<p>s</p>", "<p>c</p>");
        articles.updateExtractedContent(unread, "<p>extracted</p>");
        jdbc.update("UPDATE articles SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")), unread);

        assertEquals(0, repository.clearStaleArticleCacheBefore(Instant.parse("2026-01-01T00:00:00Z")));

        assertEquals("<p>extracted</p>",
                articles.findById(keeper, unread).orElseThrow().extractedContentHtml());
    }

    /** The list query leaves the body columns out; only findById reads them. */
    @Test
    void theArticleListCarriesNoBodiesWhileFindByIdStillDoes() {
        long reader = users.insert("list-reader@example.com", "hash").id();
        var feed = feeds.insert(reader, "List feed", "https://list.example.com/feed.xml",
                "https://list.example.com", null);
        long older = articles.insert(feed.id(), "list-old", "Older", "https://list.example.com/old",
                "Author", Instant.parse("2026-08-01T00:00:00Z"), "<p>old summary</p>", "<p>old feed content</p>");
        long newer = articles.insert(feed.id(), "list-new", "Newer", "https://list.example.com/new",
                "Author", Instant.parse("2026-08-02T00:00:00Z"), "<p>new summary</p>", "<p>new feed content</p>");
        articles.updateExtractedContent(newer, "<p>new extracted</p>");
        articles.markRead(reader, newer, true);
        Instant sentAt = Instant.parse("2026-08-03T12:00:00Z");
        articles.confirmSend(reader, newer, sentAt);

        var page = articles.findPage(reader, null, null, null, null, 50, 0);

        assertEquals(List.of(newer, older), page.stream().map(com.kindlerss.domain.Article::id).toList());
        var listed = page.get(0);
        assertEquals("Newer", listed.title());
        assertEquals("List feed", listed.feedTitle());
        assertEquals(feed.id(), listed.feedId());
        assertTrue(listed.read());
        assertEquals(sentAt, listed.sentAt());
        assertFalse(page.get(1).read());
        assertNull(page.get(1).sentAt());
        for (var article : page) {
            assertNull(article.summaryHtml());
            assertNull(article.feedContentHtml());
            assertNull(article.extractedContentHtml());
        }

        var full = articles.findById(reader, newer).orElseThrow();
        assertEquals("<p>new summary</p>", full.summaryHtml());
        assertEquals("<p>new feed content</p>", full.feedContentHtml());
        assertEquals("<p>new extracted</p>", full.extractedContentHtml());
    }

    private static int count(JdbcTemplate jdbc, String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private long insertArticle(long feedId, String guid) {
        return articles.insert(feedId, guid, "Article " + guid, "https://bulk.example.com/" + guid,
                "Author", Instant.parse("2026-08-10T00:00:00Z"), "<p>Summary</p>", "<p>Content</p>");
    }
}
