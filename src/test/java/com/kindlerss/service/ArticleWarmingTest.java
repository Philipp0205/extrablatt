package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.Article;
import com.kindlerss.repository.ArticleRepository;
import com.kindlerss.repository.FeedRepository;
import com.kindlerss.repository.UserRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Warming the extracted text of listed articles in the background: against a real
 * database and a real {@link SafeHttpClient} talking to a slow local publisher.
 */
class ArticleWarmingTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final long DELAY_MS = 400;
    private static final String PAGE_HTML = """
            <!DOCTYPE html>
            <html lang="en"><head><title>Why paper still wins</title></head><body>
              <nav><a href="/">Home</a></nav>
              <article>
                <h1>Why paper still wins</h1>
                <p>Electronic ink is pleasant, but a printed page still has a quiet
                   authority that a glowing rectangle cannot match. The weight of the
                   paper, the smell of the ink, and the way a folded corner keeps your
                   place are all part of the reading, not distractions from it.</p>
                <p>That is why a document that arrives on a Kindle as a real book,
                   rather than a web page with menus and pop-ups, feels like something
                   you can finish. The argument is not that screens are bad. It is that
                   an article deserves the same calm as a chapter.</p>
                <p>Extractors that look for the longest run of paragraphs will keep
                   this block and drop the navigation around it, which is exactly what
                   we want when a page is fetched to be read or sent as an EPUB.</p>
              </article>
            </body></html>
            """;

    private static EmbeddedPostgres postgres;
    private static ArticleRepository articles;
    private static FeedRepository feeds;
    private static long userId;
    private static long feedId;
    private static final AtomicInteger guids = new AtomicInteger();

    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService publisherThreads;
    private SafeHttpClient httpClient;
    private TrackingExecutor warmExecutor;
    private RecordingTransactions transactions;
    private ArticleService service;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        DataSource dataSource = postgres.getPostgresDatabase();
        Flyway.configure().dataSource(dataSource).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        articles = new ArticleRepository(jdbc);
        feeds = new FeedRepository(jdbc);
        userId = new UserRepository(jdbc).insert("warm@example.com", "hash").id();
        feedId = feeds.insert(userId, "Warm", "https://warm.example.com/feed.xml",
                "https://warm.example.com", null).id();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        publisherThreads = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        server.setExecutor(publisherThreads);
        server.createContext("/ok", exchange -> {
            hit(exchange);
            sleep(DELAY_MS);
            respond(exchange, 200, "text/html; charset=utf-8", PAGE_HTML);
        });
        server.createContext("/missing", exchange -> {
            hit(exchange);
            sleep(DELAY_MS);
            respond(exchange, 404, "text/plain", "not found");
        });
        server.start();

        AppProperties props = new AppProperties(
                "from@example.com", null, "remember",
                new AppProperties.Http(Duration.ofSeconds(2), Duration.ofSeconds(5), 2_097_152,
                        Duration.ofSeconds(10)),
                null, null, null, null, null, null);
        httpClient = new SafeHttpClient(props, host -> new InetAddress[] {LOOPBACK},
                address -> !address.isLoopbackAddress() && SafeHttpClient.isBlockedAddress(address));
        warmExecutor = new TrackingExecutor();
        transactions = new RecordingTransactions();
        service = new ArticleService(articles, feeds, httpClient, new HtmlSanitizer(),
                new DiscussionParser(httpClient), transactions, warmExecutor);
    }

    @AfterEach
    void tearDown() {
        warmExecutor.shutdown();
        server.stop(0);
        publisherThreads.shutdownNow();
        httpClient.close();
    }

    @Test
    void aWarmedArticleOpensWithoutTouchingThePublisherAgain() throws Exception {
        long cold = insertArticle("/ok");
        long warm = insertArticle("/ok");

        // Without warming, opening pays for the fetch.
        long start = System.nanoTime();
        service.getReaderHtml(articles.findById(userId, cold).orElseThrow(), false);
        long coldMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertTrue(coldMillis >= DELAY_MS, "cold open took " + coldMillis + " ms");
        assertEquals(1, hitCount("/ok"));

        // Warming returns at once; the fetch is still going on in the background.
        long warmStart = System.nanoTime();
        service.warmExtractedContent(userId, List.of(warm));
        long callMillis = Duration.ofNanos(System.nanoTime() - warmStart).toMillis();
        assertTrue(callMillis < DELAY_MS, "warmExtractedContent blocked for " + callMillis + " ms");
        assertFalse(warmExecutor.allDone(), "the fetch should still be running when the call returns");
        warmExecutor.awaitAll();

        Article stored = articles.findById(userId, warm).orElseThrow();
        assertTrue(stored.extractedContentHtml() != null && stored.extractedContentHtml()
                .contains("Electronic ink is pleasant"));
        assertEquals(2, hitCount("/ok"));
        assertEquals(0, transactions.started());

        long openStart = System.nanoTime();
        String html = service.getReaderHtml(stored, false);
        long openMillis = Duration.ofNanos(System.nanoTime() - openStart).toMillis();
        assertTrue(html.contains("Electronic ink is pleasant"));
        assertTrue(openMillis < 250, "warmed open took " + openMillis + " ms");
        assertEquals(2, hitCount("/ok"), "opening a warmed article must not fetch again");
    }

    @Test
    void anArticleThatCannotBeExtractedIsFetchedOnceByWarmingWithinFifteenMinutes() throws Exception {
        long id = insertArticle("/missing");

        service.warmExtractedContent(userId, List.of(id));
        warmExecutor.awaitAll();
        assertEquals(1, hitCount("/missing"));
        assertNull(articles.findById(userId, id).orElseThrow().extractedContentHtml());

        service.warmExtractedContent(userId, List.of(id));
        warmExecutor.awaitAll();
        assertEquals(1, hitCount("/missing"), "a failed extraction is not retried by warming");

        // Opening the article is not held back by that memory: it still tries.
        service.getReaderHtml(articles.findById(userId, id).orElseThrow(), false);
        assertEquals(2, hitCount("/missing"));
    }

    @Test
    void onlyTheFirstFiveIdsAreConsidered() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            ids.add(insertArticle("/ok"));
        }

        service.warmExtractedContent(userId, ids);
        warmExecutor.awaitAll();

        assertEquals(5, hitCount("/ok"));
        for (int i = 0; i < 7; i++) {
            String extracted = articles.findById(userId, ids.get(i)).orElseThrow().extractedContentHtml();
            assertEquals(i < 5, extracted != null && !extracted.isBlank(), "article " + i);
        }
    }

    @Test
    void anAlreadyExtractedArticleIsNotFetched() throws Exception {
        long id = insertArticle("/ok");
        articles.updateExtractedContent(id, "<p>Stored</p>");

        service.warmExtractedContent(userId, List.of(id));
        warmExecutor.awaitAll();

        assertEquals(0, hitCount("/ok"));
        assertEquals(0, warmExecutor.submitted());
    }

    @Test
    void anArticleOfAnotherAccountIsNeverFetched() throws Exception {
        long id = insertArticle("/ok");
        long other = 987_654L;

        service.warmExtractedContent(other, List.of(id));
        warmExecutor.awaitAll();

        assertEquals(0, hitCount("/ok"));
        assertNull(articles.findById(userId, id).orElseThrow().extractedContentHtml());
    }

    private long insertArticle(String path) {
        String guid = "warm-" + guids.incrementAndGet();
        long id = articles.insert(feedId, guid, "Article " + guid,
                "http://pub.test:" + server.getAddress().getPort() + path + "?a=" + guid,
                "Author", Instant.parse("2026-08-10T00:00:00Z"), "<p>Summary</p>", "<p>Feed text</p>");
        assertTrue(id > 0);
        return id;
    }

    private int hitCount(String path) {
        AtomicInteger count = hits.get(path);
        return count == null ? 0 : count.get();
    }

    private void hit(HttpExchange exchange) {
        hits.computeIfAbsent(exchange.getRequestURI().getPath(), p -> new AtomicInteger()).incrementAndGet();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        } catch (IOException ignored) {
            // client went away
        }
    }

    /** Runs warming tasks on real threads and lets a test wait for all of them. */
    private static final class TrackingExecutor implements Executor {
        private final ExecutorService threads = Executors.newFixedThreadPool(2);
        private final List<Future<?>> futures = new CopyOnWriteArrayList<>();

        @Override
        public void execute(Runnable command) {
            futures.add(threads.submit(command));
        }

        int submitted() {
            return futures.size();
        }

        boolean allDone() {
            return futures.stream().allMatch(Future::isDone);
        }

        void awaitAll() throws Exception {
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        }

        void shutdown() {
            threads.shutdownNow();
        }
    }
}
