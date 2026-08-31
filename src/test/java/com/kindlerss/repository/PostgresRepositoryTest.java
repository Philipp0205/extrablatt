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
    void savedArticlesAreKeptApartFromReadStateAndFromOtherAccounts() {
        var feed = feeds.insert(userId, "Saved", "https://saved.example.com/feed.xml",
                "https://saved.example.com", null);
        long articleId = insertArticle(feed.id(), "saved-1");

        assertTrue(articles.setSaved(userId, articleId, true));
        var saved = articles.findById(userId, articleId).orElseThrow();
        assertTrue(saved.saved());
        assertFalse(saved.read(), "saving an article must not mark it read");

        // Reading it afterwards leaves the bookmark alone: an article is usually
        // saved precisely because it has been read.
        articles.markRead(userId, articleId, true);
        assertTrue(articles.findById(userId, articleId).orElseThrow().saved());

        assertEquals(1, articles.findSavedPage(userId, 20, 0).size());
        assertEquals(1, articles.countSaved(userId));
        assertEquals(0, articles.countSaved(otherUserId));
        assertFalse(articles.setSaved(otherUserId, articleId, false), "not their article to unsave");

        assertTrue(articles.setSaved(userId, articleId, false));
        assertFalse(articles.findById(userId, articleId).orElseThrow().saved());
        assertEquals(0, articles.countSaved(userId));
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

        assertTrue(repository.claim("evt_once", "stripe", "invoice.paid", "{}", userId));
        assertFalse(repository.claim("evt_once", "stripe", "invoice.paid", "{}", userId));

        repository.markProcessed("evt_once");
        repository.markFailed("evt_once", "something went wrong");
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
        articles.setSaved(doomed, articleId, true);
        sendLimits.save(doomed, 7, null);
        new SubscriptionRepository(jdbc).save(new com.kindlerss.domain.Subscription(doomed,
                com.kindlerss.domain.Plan.SUPPORTER,
                com.kindlerss.domain.SubscriptionStatus.ACTIVE,
                com.kindlerss.domain.BillingInterval.YEARLY, "stripe", "cus_doomed", "sub_doomed",
                Instant.parse("2027-01-01T00:00:00Z"), false, Instant.now()));
        new BillingEventRepository(jdbc)
                .claim("evt_doomed", "stripe", "invoice.paid", "{\"email\":\"doomed@example.com\"}", doomed);

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
        new BillingEventRepository(jdbc).claim("evt_ancient", "stripe", "invoice.paid",
                "{\"email\":\"ancient@example.com\"}", ancient);
        jdbc.update("UPDATE billing_events SET received_at = ? WHERE provider_event_id = 'evt_ancient'",
                java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));

        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        assertEquals(1, repository.deleteSendEventsBefore(cutoff));
        assertEquals(1, repository.redactBillingPayloadsBefore(cutoff));
        assertEquals(1, repository.deleteSpentTokensBefore(cutoff));
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

    /** A saved article keeps its text however old it gets — it was saved to be reread. */
    @Test
    void theSweepLeavesSavedAndUnreadArticlesAlone() {
        JdbcTemplate jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
        var repository = new RetentionRepository(jdbc);
        long keeper = users.insert("keeper@example.com", "hash").id();
        var feed = feeds.insert(keeper, "Keeper", "https://keeper.example.com/feed.xml", null, null);
        long saved = articles.insert(feed.id(), "keep-1", "Saved", "https://keeper.example.com/1",
                null, null, "<p>s</p>", "<p>c</p>");
        long unread = articles.insert(feed.id(), "keep-2", "Unread", "https://keeper.example.com/2",
                null, null, "<p>s</p>", "<p>c</p>");
        for (long id : new long[]{saved, unread}) {
            articles.updateExtractedContent(id, "<p>extracted</p>");
            jdbc.update("UPDATE articles SET created_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")), id);
        }
        articles.markRead(keeper, saved, true);
        articles.setSaved(keeper, saved, true);

        assertEquals(0, repository.clearStaleArticleCacheBefore(Instant.parse("2026-01-01T00:00:00Z")));

        assertEquals("<p>extracted</p>",
                articles.findById(keeper, saved).orElseThrow().extractedContentHtml());
        assertEquals("<p>extracted</p>",
                articles.findById(keeper, unread).orElseThrow().extractedContentHtml());
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
