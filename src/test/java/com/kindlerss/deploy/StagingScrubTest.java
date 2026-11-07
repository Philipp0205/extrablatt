package com.kindlerss.deploy;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs deploy/staging-scrub.sql the way the staging sync does: after the
 * production dump, in the same transaction, with an optional keep list.
 */
class StagingScrubTest {

    /**
     * Every text column in the schema, and why it is fine on staging after the
     * scrub. A migration that adds a text column fails this test until the column
     * is listed here (and, if it can hold personal data, scrubbed).
     */
    private static final Map<String, String> TEXT_COLUMNS = Map.ofEntries(
            Map.entry("users.email", "scrubbed"),
            Map.entry("users.password_hash", "scrubbed"),
            Map.entry("users.kindle_email", "scrubbed"),
            Map.entry("users.newsletter_inbound_token", "scrubbed"),
            Map.entry("users.last_seen_changelog_id", "release id"),
            Map.entry("email_tokens.token", "rows deleted"),
            Map.entry("email_tokens.purpose", "rows deleted"),
            Map.entry("feeds.title", "newsletter titles scrubbed, RSS titles are public"),
            Map.entry("feeds.url", "newsletter senders and URL credentials scrubbed"),
            Map.entry("feeds.site_url", "newsletter ones scrubbed, RSS ones are public"),
            Map.entry("feeds.last_error", "scrubbed"),
            Map.entry("feeds.category", "user label, no contact data"),
            Map.entry("feeds.source", "enum"),
            Map.entry("articles.guid", "newsletter ones scrubbed"),
            Map.entry("articles.title", "newsletter ones scrubbed"),
            Map.entry("articles.url", "newsletter ones scrubbed"),
            Map.entry("articles.author", "newsletter ones scrubbed"),
            Map.entry("articles.summary_html", "newsletter ones scrubbed"),
            Map.entry("articles.feed_content_html", "newsletter ones scrubbed"),
            Map.entry("articles.extracted_content_html", "newsletter ones scrubbed"),
            Map.entry("subscriptions.plan", "enum"),
            Map.entry("subscriptions.status", "enum"),
            Map.entry("subscriptions.billing_interval", "enum"),
            Map.entry("subscriptions.provider", "provider name"),
            Map.entry("subscriptions.provider_customer_id", "scrubbed"),
            Map.entry("subscriptions.provider_subscription_id", "scrubbed"),
            Map.entry("billing_events.provider_event_id", "provider event id, kept for idempotency"),
            Map.entry("billing_events.provider", "provider name"),
            Map.entry("billing_events.type", "event type"),
            Map.entry("billing_events.payload", "scrubbed"),
            Map.entry("billing_events.error", "scrubbed"),
            Map.entry("billing_events.subscription_ref", "scrubbed"),
            Map.entry("cancellation_requests.email", "scrubbed"),
            Map.entry("cancellation_requests.name", "scrubbed"),
            Map.entry("cancellation_requests.contract_ref", "scrubbed"),
            Map.entry("cancellation_requests.kind", "enum"),
            Map.entry("cancellation_requests.reason", "scrubbed"),
            Map.entry("cancellation_tokens.token_hash", "rows deleted"));

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeAll
    static void start() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        postgres.close();
    }

    @BeforeEach
    void seed() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                TRUNCATE users, feeds, articles, email_tokens, subscriptions, billing_events,
                         cancellation_requests, user_send_limits, article_send_events
                         RESTART IDENTITY CASCADE
                """);
        jdbc.update("""
                INSERT INTO users (email, password_hash, kindle_email, newsletter_inbound_token, email_verified_at)
                VALUES ('Reader@Example.com', '$2a$10$realhash', 'reader@kindle.com', 'inbox123', NOW()),
                       ('tester@example.org', '$2a$10$testerhash', 'tester@kindle.com', 'inbox456', NOW()),
                       ('nokindle@example.net', '$2a$10$otherhash', NULL, NULL, NULL)
                """);
        jdbc.update("""
                INSERT INTO email_tokens (token, user_id, purpose, expires_at)
                VALUES ('reset-token', 1, 'RESET', NOW() + INTERVAL '1 hour')
                """);
        jdbc.update("""
                INSERT INTO feeds (user_id, title, url, site_url, last_error, source)
                VALUES (1, 'Jane Doe Weekly', 'newsletter:jane@doe.example', 'https://doe.example', NULL, 'NEWSLETTER'),
                       (1, 'Public blog', 'https://blog.example/feed.xml', 'https://blog.example', 'HTTP 500 for reader@example.com', 'RSS'),
                       (1, 'Private feed', 'https://user:secret@paid.example/rss?token=abc', NULL, NULL, 'RSS'),
                       (1, 'Pasted URLs', 'clippings:', NULL, NULL, 'CLIPPING')
                """);
        jdbc.update("""
                INSERT INTO articles (feed_id, guid, title, url, author, summary_html, feed_content_html)
                VALUES (1, '<msg-id@doe.example>', 'Hi Reader, your issue', 'https://doe.example/u?id=reader',
                        'Jane Doe', '<p>Dear Reader</p>', '<p>unsubscribe reader@example.com</p>'),
                       (2, 'post-1', 'Public post', 'https://blog.example/1', 'Blogger', '<p>Public</p>', NULL)
                """);
        jdbc.update("""
                INSERT INTO subscriptions (user_id, plan, status, provider, provider_customer_id, provider_subscription_id)
                VALUES (1, 'SUPPORTER', 'ACTIVE', 'paddle', 'ctm_real', 'sub_real'),
                       (3, 'FREE', 'FREE', NULL, NULL, NULL)
                """);
        jdbc.update("""
                INSERT INTO billing_events (provider_event_id, provider, type, payload, error, user_id, subscription_ref)
                VALUES ('evt_1', 'paddle', 'subscription.created', '{"email":"reader@example.com"}', 'failed for reader@example.com', 1, 'sub_real')
                """);
        jdbc.update("""
                INSERT INTO cancellation_requests (user_id, email, name, contract_ref, kind, reason)
                VALUES (NULL, 'anon@example.com', 'Anna Nym', 'sub_real', 'ORDINARY', 'moving away'),
                       (2, 'tester@example.org', 'Tess Ter', 'sub_t', 'ORDINARY', 'testing')
                """);
        jdbc.update("""
                INSERT INTO cancellation_tokens (token_hash, request_id, user_id, expires_at)
                VALUES ('hash-of-link', 2, 2, NOW() + INTERVAL '1 day')
                """);
    }

    @Test
    void everyTextColumnIsClassified() {
        List<String> columns = jdbc.queryForList("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name <> 'flyway_schema_history'
                  AND data_type IN ('text', 'character varying', 'character', 'json', 'jsonb', 'bytea')
                """, String.class);
        Set<String> unclassified = new TreeSet<>(columns);
        unclassified.removeAll(TEXT_COLUMNS.keySet());
        assertTrue(unclassified.isEmpty(),
                "Add these columns to deploy/staging-scrub.sql (or mark them safe) and to TEXT_COLUMNS: "
                        + unclassified);
        Set<String> stale = new TreeSet<>(TEXT_COLUMNS.keySet());
        stale.removeAll(columns);
        assertTrue(stale.isEmpty(), "No longer in the schema: " + stale);
    }

    @Test
    void accountsLoseTheirAddressesCredentialsAndTokens() throws Exception {
        scrub(List.of());

        List<Map<String, Object>> users = jdbc.queryForList("SELECT * FROM users ORDER BY id");
        assertEquals("user-1@staging.invalid", users.get(0).get("email"));
        assertEquals("kindle-1@staging.invalid", users.get(0).get("kindle_email"));
        assertNull(users.get(0).get("newsletter_inbound_token"));
        assertNull(users.get(2).get("kindle_email"));
        for (Map<String, Object> user : users) {
            assertFalse(user.get("password_hash").toString().startsWith("$2"), user.toString());
        }
        assertEquals(0, count("SELECT COUNT(*) FROM email_tokens"));
    }

    @Test
    void newsletterFeedsAndIssuesNoLongerNameTheSenderOrReader() throws Exception {
        scrub(List.of());

        Map<String, Object> newsletter = jdbc.queryForMap("SELECT * FROM feeds WHERE id = 1");
        assertEquals("newsletter:sender-1@staging.invalid", newsletter.get("url"));
        assertEquals("Newsletter 1", newsletter.get("title"));
        assertNull(newsletter.get("site_url"));

        Map<String, Object> issue = jdbc.queryForMap("SELECT * FROM articles WHERE feed_id = 1");
        String everything = issue.values().toString();
        assertFalse(everything.contains("Reader"), everything);
        assertFalse(everything.contains("reader@"), everything);
        assertFalse(everything.contains("doe.example"), everything);
        assertNull(issue.get("author"));

        Map<String, Object> publicPost = jdbc.queryForMap("SELECT * FROM articles WHERE feed_id = 2");
        assertEquals("Public post", publicPost.get("title"));
        assertEquals("<p>Public</p>", publicPost.get("summary_html"));
    }

    @Test
    void feedUrlsLoseCredentialsAndErrorsAreCleared() throws Exception {
        scrub(List.of());

        assertEquals("https://blog.example/feed.xml", feedUrl(2));
        assertEquals("https://paid.example/rss?staging-redacted=3", feedUrl(3));
        assertEquals("clippings:", feedUrl(4));
        assertEquals(0, count("SELECT COUNT(*) FROM feeds WHERE last_error IS NOT NULL"));
    }

    @Test
    void billingAndCancellationRecordsAreScrubbed() throws Exception {
        scrub(List.of());

        Map<String, Object> sub = jdbc.queryForMap("SELECT * FROM subscriptions WHERE user_id = 1");
        assertEquals("staging-cus-1", sub.get("provider_customer_id"));
        assertEquals("staging-sub-1", sub.get("provider_subscription_id"));
        assertNull(jdbc.queryForMap("SELECT * FROM subscriptions WHERE user_id = 3").get("provider_customer_id"));

        Map<String, Object> event = jdbc.queryForMap("SELECT * FROM billing_events");
        assertEquals("{}", event.get("payload"));
        assertFalse(event.get("error").toString().contains("@"));
        assertFalse(event.get("subscription_ref").toString().contains("sub_real"));
        assertEquals(0, count("SELECT COUNT(*) FROM cancellation_tokens"));

        Map<String, Object> cancel = jdbc.queryForMap("SELECT * FROM cancellation_requests WHERE id = 1");
        assertEquals("cancel-1@staging.invalid", cancel.get("email"));
        assertFalse(cancel.values().toString().contains("Anna"));
        assertNull(cancel.get("contract_ref"));
    }

    @Test
    void keptAccountsStayUsableAndMatchCaseInsensitively() throws Exception {
        scrub(List.of("TESTER@example.org"));

        Map<String, Object> tester = jdbc.queryForMap("SELECT * FROM users WHERE id = 2");
        assertEquals("tester@example.org", tester.get("email"));
        assertEquals("$2a$10$testerhash", tester.get("password_hash"));
        assertEquals("tester@kindle.com", tester.get("kindle_email"));
        assertEquals("inbox456", tester.get("newsletter_inbound_token"));
        assertEquals("tester@example.org",
                jdbc.queryForObject("SELECT email FROM cancellation_requests WHERE id = 2", String.class));
        assertEquals("user-1@staging.invalid",
                jdbc.queryForObject("SELECT email FROM users WHERE id = 1", String.class));
    }

    @Test
    void aFailedCheckRollsTheWholeTransactionBack() throws Exception {
        // A trigger that puts a real address back stands in for a scrub that
        // misses something; the final check has to stop the commit.
        jdbc.execute("""
                CREATE FUNCTION staging_scrub_test_undo() RETURNS trigger AS $$
                BEGIN NEW.email := 'leak@example.com'; RETURN NEW; END $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER staging_scrub_test_undo BEFORE UPDATE ON users
                FOR EACH ROW WHEN (NEW.id = 3) EXECUTE FUNCTION staging_scrub_test_undo()
                """);
        try {
            SQLException failure = assertThrows(SQLException.class, () -> scrub(List.of()));
            assertTrue(failure.getMessage().contains("real address"), failure.getMessage());
            assertEquals("Reader@Example.com",
                    jdbc.queryForObject("SELECT email FROM users WHERE id = 1", String.class));
            assertEquals(1, count("SELECT COUNT(*) FROM email_tokens"));
        } finally {
            jdbc.execute("DROP TRIGGER staging_scrub_test_undo ON users");
            jdbc.execute("DROP FUNCTION staging_scrub_test_undo()");
        }
    }

    @Test
    void runsAfterADumpThatClearedTheSearchPath() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT pg_catalog.set_config('search_path', '', false)");
                statement.execute(Files.readString(Path.of("deploy/staging-scrub.sql")));
            }
            connection.commit();
        }
        assertEquals("user-1@staging.invalid",
                jdbc.queryForObject("SELECT email FROM users WHERE id = 1", String.class));
    }

    /** Same order as the sync: keep list first, then the scrub, in one transaction. */
    private static void scrub(List<String> keep) throws Exception {
        StringBuilder header = new StringBuilder(
                "CREATE TEMP TABLE staging_keep_emails (email TEXT PRIMARY KEY) ON COMMIT DROP;\n");
        for (String email : keep) {
            header.append("INSERT INTO pg_temp.staging_keep_emails VALUES ('")
                    .append(email.replace("'", "''")).append("');\n");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(header.toString());
                statement.execute(Files.readString(Path.of("deploy/staging-scrub.sql")));
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private String feedUrl(long id) {
        return jdbc.queryForObject("SELECT url FROM feeds WHERE id = ?", String.class, id);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
