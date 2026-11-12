package com.kindlerss.web;

import com.kindlerss.domain.Feed;
import com.kindlerss.repository.ArticleRepository;
import com.kindlerss.repository.FeedRepository;
import com.kindlerss.repository.UserRepository;
import com.kindlerss.service.ApiTokenService;
import com.kindlerss.service.KindleMailService;
import com.kindlerss.service.SafeHttpClient;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The REST API against the whole application and a real database. Feed fetching
 * and mail are the only things replaced, so no test touches the network.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiControllerIntegrationTest {

    private static final String FEED_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <title>Example Feed</title>
              <link>https://example.com/</link>
              <item>
                <title>First post</title>
                <link>https://example.com/1</link>
                <guid isPermaLink="false">https://example.com/1</guid>
              </item>
            </channel></rss>
            """;

    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        registry.add("spring.datasource.url",
                () -> "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("app.mail-from", () -> "noreply@example.com");
        registry.add("app.remember-me-key", () -> "api-it-remember-key");
        registry.add("app.articles.page-size", () -> "5");
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired FeedRepository feeds;
    @Autowired ArticleRepository articles;
    @Autowired ApiTokenService tokenService;
    @Autowired com.kindlerss.service.DataExportService dataExportService;

    @MockitoBean SafeHttpClient httpClient;
    @MockitoBean KindleMailService kindleMailService;

    private long userA;
    private long userB;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void seedTwoAccounts() {
        userA = verifiedUser("a-" + UUID.randomUUID() + "@example.com");
        userB = verifiedUser("b-" + UUID.randomUUID() + "@example.com");
        tokenA = tokenService.create(userA, "A").secret();
        tokenB = tokenService.create(userB, "B").secret();
    }

    private long verifiedUser(String email) {
        long id = users.insert(email, "hash").id();
        users.markEmailVerified(id);
        return id;
    }

    private long article(Feed feed, String guid, int ageMinutes) {
        return articles.insert(feed.id(), guid, "Title " + guid, "https://example.com/" + guid, "Ann",
                Instant.now().minus(ageMinutes, ChronoUnit.MINUTES), "<p>Summary " + guid + "</p>", null);
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String token,
                                                      String body) {
        return bearer(request, token).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // --- authentication ---

    @Test
    void noTokenIsA401WithJsonAndNoRedirect() throws Exception {
        mockMvc.perform(get("/api/v1/feeds").servletPath("/api/v1/feeds"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", containsString("application/json")))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void aWrongTokenIsA401() throws Exception {
        mockMvc.perform(bearer(get("/api/v1/feeds"), "eb_notatoken"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").isNotEmpty());
        mockMvc.perform(get("/api/v1/feeds").header("Authorization", "Basic abc"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRevokedTokenStopsWorking() throws Exception {
        mockMvc.perform(bearer(get("/api/v1/feeds"), tokenA)).andExpect(status().isOk());
        long id = jdbc.queryForObject("SELECT id FROM api_tokens WHERE user_id = ?", Long.class, userA);

        assertTrue(tokenService.revoke(userA, id));

        mockMvc.perform(bearer(get("/api/v1/feeds"), tokenA)).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenCannotBeRevokedByAnotherAccount() throws Exception {
        long id = jdbc.queryForObject("SELECT id FROM api_tokens WHERE user_id = ?", Long.class, userA);

        assertFalse(tokenService.revoke(userB, id));

        mockMvc.perform(bearer(get("/api/v1/feeds"), tokenA)).andExpect(status().isOk());
    }

    @Test
    void aTokenOfADisabledOrUnconfirmedAccountIsRefused() throws Exception {
        jdbc.update("UPDATE users SET disabled_at = NOW() WHERE id = ?", userA);
        mockMvc.perform(bearer(get("/api/v1/feeds"), tokenA)).andExpect(status().isUnauthorized());

        jdbc.update("UPDATE users SET email_verified_at = NULL WHERE id = ?", userB);
        mockMvc.perform(bearer(get("/api/v1/feeds"), tokenB)).andExpect(status().isUnauthorized());
    }

    @Test
    void successfulUseRecordsLastUsedAndStoresOnlyAHash() throws Exception {
        assertEquals(null, jdbc.queryForObject(
                "SELECT last_used_at FROM api_tokens WHERE user_id = ?", Object.class, userA));

        mockMvc.perform(bearer(get("/api/v1/feeds"), tokenA)).andExpect(status().isOk());

        assertNotNull(jdbc.queryForObject(
                "SELECT last_used_at FROM api_tokens WHERE user_id = ?", Object.class, userA));
        String stored = jdbc.queryForObject("SELECT token_hash FROM api_tokens WHERE user_id = ?",
                String.class, userA);
        assertEquals(ApiTokenService.hashToken(tokenA), stored);
        assertFalse(stored.contains(tokenA));
    }

    @Test
    void theApiNeverStartsASessionAndIgnoresOneForOtherPaths() throws Exception {
        var result = mockMvc.perform(bearer(get("/api/v1/feeds"), tokenA))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();
        assertEquals(null, result.getRequest().getSession(false));
        // A token is not a credential for the website.
        mockMvc.perform(bearer(get("/settings"), tokenA))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void requestsAreRateLimitedPerToken() throws Exception {
        String token = tokenService.create(userA, "busy").secret();
        for (int i = 0; i < 60; i++) {
            mockMvc.perform(bearer(get("/api/v1/categories"), token)).andExpect(status().isOk());
        }
        mockMvc.perform(bearer(get("/api/v1/categories"), token))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").isNotEmpty());
        mockMvc.perform(bearer(get("/api/v1/categories"), tokenB)).andExpect(status().isOk());
    }

    // --- reading ---

    @Test
    void categoriesAndFeedsAreTheOwnersOnly() throws Exception {
        feeds.insert(userA, "Tech blog", "https://a.example/feed", "https://a.example", "Tech");
        feeds.insert(userA, "Plain", "https://a2.example/feed", "https://a2.example", null);
        feeds.insert(userB, "Secret", "https://b.example/feed", "https://b.example", "Private");

        mockMvc.perform(bearer(get("/api/v1/categories"), tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories", hasSize(2)))
                .andExpect(jsonPath("$.categories[0]").value("Tech"))
                .andExpect(jsonPath("$.categories[1]").value("Uncategorized"));
        mockMvc.perform(bearer(get("/api/v1/feeds"), tokenA))
                .andExpect(jsonPath("$.feeds", hasSize(2)))
                .andExpect(content().string(not(containsString("Secret"))));
    }

    @Test
    void itemsFilterByUnreadAndPaginate() throws Exception {
        Feed feed = feeds.insert(userA, "Blog", "https://p.example/feed", "https://p.example", "News");
        long newest = article(feed, "n1", 1);
        for (int i = 2; i <= 6; i++) {
            article(feed, "n" + i, i);
        }
        long oldest = article(feed, "n7", 7);
        articles.markRead(userA, oldest, true);

        mockMvc.perform(bearer(get("/api/v1/items"), tokenA))
                .andExpect(jsonPath("$.total").value(7))
                .andExpect(jsonPath("$.pageSize").value(5))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[0].id").value(newest));
        mockMvc.perform(bearer(get("/api/v1/items").param("page", "2"), tokenA))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[1].id").value(oldest));
        mockMvc.perform(bearer(get("/api/v1/items").param("unread", "true"), tokenA))
                .andExpect(jsonPath("$.total").value(6))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(bearer(get("/api/v1/items").param("unread", "true").param("page", "2"), tokenA))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.hasMore").value(false));
        mockMvc.perform(bearer(get("/api/v1/items").param("category", "News").param("pageSize", "2"), tokenA))
                .andExpect(jsonPath("$.pageSize").value(2))
                .andExpect(jsonPath("$.totalPages").value(4));
        mockMvc.perform(bearer(get("/api/v1/items").param("pageSize", "500"), tokenA))
                .andExpect(jsonPath("$.pageSize").value(5));
        mockMvc.perform(bearer(get("/api/v1/items").param("category", "Nothing"), tokenA))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void anotherAccountsFeedAndArticlesAreNotFound() throws Exception {
        Feed feedB = feeds.insert(userB, "B blog", "https://bb.example/feed", "https://bb.example", null);
        long articleB = article(feedB, "b1", 1);

        mockMvc.perform(bearer(get("/api/v1/items").param("feed", String.valueOf(feedB.id())), tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
        mockMvc.perform(bearer(get("/api/v1/items"), tokenA))
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(bearer(get("/api/v1/articles/" + articleB), tokenA)).andExpect(status().isNotFound());
        mockMvc.perform(json(post("/api/v1/articles/" + articleB + "/read"), tokenA, "{\"read\":true}"))
                .andExpect(status().isNotFound());
        // The send is always for the token's own account; the service answers not-found for B's row.
        doThrow(new com.kindlerss.service.ArticleService.NotFoundException("Article not found"))
                .when(kindleMailService).sendToKindle(userA, articleB, false);
        mockMvc.perform(json(post("/api/v1/articles/" + articleB + "/send"), tokenA, "{}"))
                .andExpect(status().isNotFound());
        verify(kindleMailService).sendToKindle(userA, articleB, false);
        mockMvc.perform(bearer(delete("/api/v1/feeds/" + feedB.id()), tokenA)).andExpect(status().isNotFound());
        mockMvc.perform(json(post("/api/v1/items/mark-read"), tokenA, "{\"feed\":" + feedB.id() + "}"))
                .andExpect(status().isNotFound());

        // Untouched, and still there for its owner.
        mockMvc.perform(bearer(get("/api/v1/articles/" + articleB), tokenB)).andExpect(status().isOk());
        assertEquals(1, articles.count(userB, feedB.id(), Boolean.TRUE));
    }

    @Test
    void anArticleComesWithItsReadableContent() throws Exception {
        Feed feed = feeds.insert(userA, "Blog", "https://c.example/feed", "https://c.example", null);
        long id = article(feed, "c1", 1);

        mockMvc.perform(bearer(get("/api/v1/articles/" + id), tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.article.id").value(id))
                .andExpect(jsonPath("$.article.title").value("Title c1"))
                .andExpect(jsonPath("$.contentHtml").value(containsString("Summary c1")));
    }

    // --- changing read state ---

    @Test
    void anArticleCanBeMarkedReadAndUnread() throws Exception {
        Feed feed = feeds.insert(userA, "Blog", "https://d.example/feed", "https://d.example", null);
        long id = article(feed, "d1", 1);

        mockMvc.perform(json(post("/api/v1/articles/" + id + "/read"), tokenA, "{\"read\":true}"))
                .andExpect(jsonPath("$.read").value(true));
        assertEquals(0, articles.count(userA, feed.id(), Boolean.TRUE));
        mockMvc.perform(json(post("/api/v1/articles/" + id + "/read"), tokenA, "{\"read\":false}"))
                .andExpect(jsonPath("$.read").value(false));
        assertEquals(1, articles.count(userA, feed.id(), Boolean.TRUE));
    }

    @Test
    void markAllReadFollowsTheFilterAndLeavesOthersAlone() throws Exception {
        Feed news = feeds.insert(userA, "News", "https://e.example/feed", "https://e.example", "News");
        Feed other = feeds.insert(userA, "Other", "https://f.example/feed", "https://f.example", "Other");
        Feed foreign = feeds.insert(userB, "Foreign", "https://g.example/feed", "https://g.example", "News");
        for (int i = 0; i < 5; i++) {
            article(news, "e" + i, i + 1);
        }
        article(other, "f1", 1);
        article(foreign, "g1", 1);

        mockMvc.perform(json(post("/api/v1/items/mark-read"), tokenA, "{\"category\":\"News\"}"))
                .andExpect(jsonPath("$.marked").value(5))
                .andExpect(jsonPath("$.hasMore").value(false));

        assertEquals(0, articles.count(userA, news.id(), Boolean.TRUE));
        assertEquals(1, articles.count(userA, other.id(), Boolean.TRUE));
        assertEquals(1, articles.count(userB, foreign.id(), Boolean.TRUE));

        mockMvc.perform(bearer(post("/api/v1/items/mark-read"), tokenA))
                .andExpect(jsonPath("$.marked").value(1));
        assertEquals(0, articles.count(userA, other.id(), Boolean.TRUE));
        assertEquals(1, articles.count(userB, foreign.id(), Boolean.TRUE));
    }

    // --- following, sending, account ---

    @Test
    void followingAFeedGoesThroughTheFeedServiceAndIsListedAndRemovable() throws Exception {
        when(httpClient.get("https://example.com/feed.xml"))
                .thenReturn(new SafeHttpClient.FetchedContent(URI.create("https://example.com/feed.xml"),
                        FEED_XML, "application/rss+xml"));

        var created = mockMvc.perform(json(post("/api/v1/feeds"), tokenA,
                        "{\"url\":\"https://example.com/feed.xml\",\"category\":\"Tech\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Example Feed"))
                .andExpect(jsonPath("$.category").value("Tech"))
                .andReturn();
        verify(httpClient).get("https://example.com/feed.xml");
        mockMvc.perform(bearer(get("/api/v1/items"), tokenA))
                .andExpect(jsonPath("$.items[0].title").value("First post"));
        mockMvc.perform(json(post("/api/v1/feeds"), tokenA, "{\"url\":\"https://example.com/feed.xml\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("You're already following that site"));

        long id = ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.id")).longValue();
        mockMvc.perform(bearer(delete("/api/v1/feeds/" + id), tokenA)).andExpect(status().isNoContent());
        mockMvc.perform(bearer(get("/api/v1/feeds"), tokenA)).andExpect(jsonPath("$.feeds", hasSize(0)));
    }

    @Test
    void aFeedThatCannotBeFetchedIsAJsonError() throws Exception {
        when(httpClient.get("https://nope.example/")).thenThrow(new SafeHttpClient.FetchException(
                "blocked address 10.0.0.1", "That address is not allowed", null));

        mockMvc.perform(json(post("/api/v1/feeds"), tokenA, "{\"url\":\"https://nope.example/\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("That address is not allowed"))
                .andExpect(content().string(not(containsString("10.0.0.1"))));
        mockMvc.perform(json(post("/api/v1/feeds"), tokenA, "not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void sendingUsesTheKindleServiceAndReportsItsRefusals() throws Exception {
        Feed feed = feeds.insert(userA, "Blog", "https://h.example/feed", "https://h.example", null);
        long id = article(feed, "h1", 1);

        mockMvc.perform(json(post("/api/v1/articles/" + id + "/send"), tokenA, "{\"images\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Sent to Kindle"));
        verify(kindleMailService).sendToKindle(userA, id, true);

        doThrow(new KindleMailService.SetupRequiredException("Add your Kindle e-mail address in Settings first"))
                .when(kindleMailService).sendToKindle(eq(userA), eq(id), eq(false));
        mockMvc.perform(bearer(post("/api/v1/articles/" + id + "/send"), tokenA))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.setup").value(true))
                .andExpect(jsonPath("$.error").value("Add your Kindle e-mail address in Settings first"));

        doThrow(new KindleMailService.SendRefusedException("Daily send limit reached (50). Try again later."))
                .when(kindleMailService).sendToKindle(eq(userA), eq(id), eq(false));
        mockMvc.perform(bearer(post("/api/v1/articles/" + id + "/send"), tokenA))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Daily send limit reached (50). Try again later."));

        doThrow(new IllegalStateException("smtp password=hunter2"))
                .when(kindleMailService).sendToKindle(eq(userA), eq(id), eq(false));
        mockMvc.perform(bearer(post("/api/v1/articles/" + id + "/send"), tokenA))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("hunter2"))));
    }

    @Test
    void pastingAUrlImportsThePageThenSendsIt() throws Exception {
        String page = "<html><head><title>Why paper wins</title></head><body><article>"
                + "<h1>Why paper wins</h1>"
                + "<p>Electronic ink is pleasant, but a printed page still has a quiet authority that a "
                + "glowing rectangle cannot match, and the weight of the paper is part of the reading.</p>"
                + "<p>That is why a document that arrives on a Kindle as a real book, rather than a web "
                + "page with menus and pop-ups, feels like something you can finish in one sitting.</p>"
                + "<p>Extractors that look for the longest run of paragraphs will keep this block and drop "
                + "the navigation around it, which is exactly what we want here.</p>"
                + "</article></body></html>";
        when(httpClient.get("https://paper.example/post"))
                .thenReturn(new SafeHttpClient.FetchedContent(URI.create("https://paper.example/post"),
                        page, "text/html"));

        var result = mockMvc.perform(json(post("/api/v1/articles/from-url"), tokenA,
                        "{\"url\":\"https://paper.example/post\",\"images\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articleId").isNumber())
                .andReturn();

        long articleId = ((Number) com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.articleId")).longValue();
        verify(kindleMailService).sendToKindle(userA, articleId, false);
        mockMvc.perform(bearer(get("/api/v1/articles/" + articleId), tokenA)).andExpect(status().isOk());
        mockMvc.perform(bearer(get("/api/v1/articles/" + articleId), tokenB)).andExpect(status().isNotFound());
    }

    @Test
    void pastingABlankUrlSendsNothing() throws Exception {
        mockMvc.perform(json(post("/api/v1/articles/from-url"), tokenA, "{\"url\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Paste an article URL first"));
        verify(kindleMailService, never()).sendToKindle(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void theAccountShowsSettingsAndPlanButNoSecrets() throws Exception {
        users.updateKindleEmail(userA, "reader@kindle.com");

        mockMvc.perform(bearer(get("/api/v1/account"), tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").isNotEmpty())
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.kindleEmail").value("reader@kindle.com"))
                .andExpect(jsonPath("$.kindleConfigured").value(true))
                .andExpect(jsonPath("$.markReadOnNextPage").value(true))
                .andExpect(jsonPath("$.entitlement.plan").value("SUPPORTER"))
                .andExpect(content().string(not(containsString("hash"))))
                .andExpect(content().string(not(containsString(tokenA))));
    }

    @Test
    void anUnknownApiPathIsAJson404AndTheWrongMethodIsAJson405() throws Exception {
        mockMvc.perform(bearer(get("/api/v1/nothing-here"), tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
        mockMvc.perform(bearer(post("/api/v1/account"), tokenA))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void theDataExportListsTokenMetadataButNeverTheHashOrSecret() {
        String export = new String(dataExportService.exportJson(userA), java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(export.contains("\"api_tokens\""), export);
        assertTrue(export.contains("\"name\" : \"A\""), export);
        assertTrue(export.contains(tokenA.substring(0, 8)), export);
        assertFalse(export.contains(tokenA), export);
        assertFalse(export.contains(ApiTokenService.hashToken(tokenA)), export);
        assertFalse(export.contains(ApiTokenService.hashToken(tokenB)), export);
    }

    @Test
    void theWebsiteKeepsCsrfOnItsForms() throws Exception {
        mockMvc.perform(post("/settings/api-tokens").param("name", "x"))
                .andExpect(status().isForbidden());
    }
}
