package com.kindlerss.repository;

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

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        DataSource dataSource = postgres.getPostgresDatabase();
        Flyway.configure().dataSource(dataSource).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        feeds = new FeedRepository(jdbc);
        articles = new ArticleRepository(jdbc);
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    void marksAWholePageOfArticlesReadInOneStatement() {
        var feed = feeds.insert("Bulk", "https://bulk.example.com/feed.xml", "https://bulk.example.com");
        long first = insertArticle(feed.id(), "bulk-1");
        long second = insertArticle(feed.id(), "bulk-2");
        long untouched = insertArticle(feed.id(), "bulk-3");

        assertEquals(2, articles.markRead(List.of(first, second), true));
        // Already read: nothing changes, so nothing is reported.
        assertEquals(0, articles.markRead(List.of(first, second), true));
        assertEquals(0, articles.markRead(List.of(), true));

        assertTrue(articles.findById(first).orElseThrow().read());
        assertTrue(articles.findById(second).orElseThrow().read());
        assertFalse(articles.findById(untouched).orElseThrow().read());
    }

    private long insertArticle(long feedId, String guid) {
        return articles.insert(feedId, guid, "Article " + guid, "https://bulk.example.com/" + guid,
                "Author", Instant.parse("2026-08-10T00:00:00Z"), "<p>Summary</p>", "<p>Content</p>");
    }

    @Test
    void insertsReturnOnlyTheGeneratedIdWithPostgres() {
        var feed = feeds.insert("Example", "https://example.com/feed.xml", "https://example.com");
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
        assertEquals("Example", articles.findById(articleId).orElseThrow().feedTitle());
    }
}
