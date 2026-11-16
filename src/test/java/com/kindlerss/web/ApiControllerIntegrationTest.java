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
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    @Test
    void anArticleCarriesItsOriginalAndCommentsLinksAndNullWhenNotHttp() throws Exception {
        Feed feed = feeds.insert(userA, "Blog", "https://l.example/feed", "https://l.example", null);
        long withLinks = articles.insert(feed.id(), "l1", "Linked", "https://example.com/l1", "Ann", Instant.now(),
                "<p>Summary</p><a href=\"https://news.ycombinator.com/item?id=42\">Comments</a>", null);
        long plain = article(feed, "l2", 1);
        long notHttp = articles.insert(feed.id(), "l3", "Odd", "ftp://example.com/l3", "Ann", Instant.now(),
                "<p>Summary</p><a href=\"javascript:alert(1)\">Comments</a>", null);

        mockMvc.perform(bearer(get("/api/v1/articles/" + withLinks), tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.article.id").value(withLinks))
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/l1"))
                .andExpect(jsonPath("$.commentsUrl").value("https://news.ycombinator.com/item?id=42"));
        mockMvc.perform(bearer(get("/api/v1/articles/" + plain), tokenA))
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/l2"))
                .andExpect(jsonPath("$.commentsUrl").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(bearer(get("/api/v1/articles/" + notHttp), tokenA))
                .andExpect(jsonPath("$.article.url").value("ftp://example.com/l3"))
                .andExpect(jsonPath("$.originalUrl").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.commentsUrl").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void theArticleHtmlIsSanitizedForTheReader() throws Exception {
        Feed feed = feeds.insert(userA, "Blog", "https://m.example/feed", "https://m.example", null);
        long id = articles.insert(feed.id(), "m1", "Hostile", "https://example.com/m1", "Ann", Instant.now(),
                "<p>Summary</p>", "<p>Safe text</p><script>alert(1)</script>"
                        + "<img src=\"https://example.com/i.png\" onerror=\"alert(2)\">"
                        + "<a href=\"javascript:alert(3)\">bad link</a>"
                        + "<p style=\"color:red\">styled</p>");

        for (String images : new String[] {"false", "true"}) {
            mockMvc.perform(bearer(get("/api/v1/articles/" + id).param("images", images), tokenA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.contentHtml").value(containsString("Safe text")))
                    .andExpect(jsonPath("$.contentHtml").value(containsString("bad link")))
                    .andExpect(jsonPath("$.contentHtml").value(not(containsString("<script"))))
                    .andExpect(jsonPath("$.contentHtml").value(not(containsString("alert"))))
                    .andExpect(jsonPath("$.contentHtml").value(not(containsString("onerror"))))
                    .andExpect(jsonPath("$.contentHtml").value(not(containsString("javascript:"))))
                    .andExpect(jsonPath("$.contentHtml").value(not(containsString("style="))))
                    .andExpect(jsonPath("$.contentHtml").value(not(containsString("color:red"))));
        }
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
                .andExpect(jsonPath("$.readTracking").value("open"))
                .andExpect(jsonPath("$.entitlement.plan").value("SUPPORTER"))
                .andExpect(content().string(not(containsString("hash"))))
                .andExpect(content().string(not(containsString(tokenA))));
    }

    @Test
    void theAccountReportsTheReadTrackingMode() throws Exception {
        mockMvc.perform(bearer(get("/api/v1/account"), tokenA))
                .andExpect(jsonPath("$.readTracking").value("open"));

        users.updateMarkReadOnScroll(userA, true);
        mockMvc.perform(bearer(get("/api/v1/account"), tokenA))
                .andExpect(jsonPath("$.readTracking").value("scroll"))
                .andExpect(jsonPath("$.markReadOnNextPage").value(true));

        users.updateMarkReadOnNextPage(userA, false);
        mockMvc.perform(bearer(get("/api/v1/account"), tokenA))
                .andExpect(jsonPath("$.readTracking").value("none"))
                .andExpect(jsonPath("$.markReadOnNextPage").value(false));

        users.updateMarkReadOnScroll(userA, false);
        mockMvc.perform(bearer(get("/api/v1/account"), tokenA))
                .andExpect(jsonPath("$.readTracking").value("none"));
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

    // --- the Feeds screen ---

    @Test
    void aFeedsCategoryCanBeChangedAndIsScopedToTheAccount() throws Exception {
        Feed feed = feeds.insert(userA, "Blog", "https://cat.example/feed", "https://cat.example", "News");
        Feed foreign = feeds.insert(userB, "Theirs", "https://cat2.example/feed", "https://cat2.example", "News");

        mockMvc.perform(json(put("/api/v1/feeds/" + feed.id() + "/category"), tokenA, "{\"category\":\" Tech \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(feed.id()))
                .andExpect(jsonPath("$.title").value("Blog"))
                .andExpect(jsonPath("$.category").value("Tech"));
        assertEquals("Tech", feeds.findById(userA, feed.id()).orElseThrow().category());

        mockMvc.perform(json(put("/api/v1/feeds/" + foreign.id() + "/category"), tokenA, "{\"category\":\"Mine\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Feed not found"));
        assertEquals("News", feeds.findById(userB, foreign.id()).orElseThrow().category());
    }

    @Test
    void aBlankOrMissingCategoryMakesTheFeedUncategorized() throws Exception {
        Feed feed = feeds.insert(userA, "Blog", "https://cat3.example/feed", "https://cat3.example", "News");

        mockMvc.perform(json(put("/api/v1/feeds/" + feed.id() + "/category"), tokenA, "{\"category\":\"  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("Uncategorized"));
        assertNull(feeds.findById(userA, feed.id()).orElseThrow().category());

        feeds.updateCategory(userA, feed.id(), "News");
        mockMvc.perform(json(put("/api/v1/feeds/" + feed.id() + "/category"), tokenA, "{}"))
                .andExpect(jsonPath("$.category").value("Uncategorized"));
        assertNull(feeds.findById(userA, feed.id()).orElseThrow().category());
    }

    @Test
    void renamingACategoryMovesAllItsFeedsAndOnlyTheOwners() throws Exception {
        Feed one = feeds.insert(userA, "One", "https://r1.example/feed", "https://r1.example", "Tech");
        Feed two = feeds.insert(userA, "Two", "https://r2.example/feed", "https://r2.example", "Tech");
        Feed other = feeds.insert(userA, "Other", "https://r3.example/feed", "https://r3.example", "News");
        Feed foreign = feeds.insert(userB, "Theirs", "https://r4.example/feed", "https://r4.example", "Tech");

        mockMvc.perform(json(post("/api/v1/categories/rename"), tokenA,
                        "{\"oldCategory\":\"Tech\",\"newCategory\":\"Technology\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2))
                .andExpect(jsonPath("$.unchanged").value(false))
                .andExpect(jsonPath("$.message").value("Renamed category for 2 feeds"));
        assertEquals("Technology", feeds.findById(userA, one.id()).orElseThrow().category());
        assertEquals("Technology", feeds.findById(userA, two.id()).orElseThrow().category());
        assertEquals("News", feeds.findById(userA, other.id()).orElseThrow().category());
        assertEquals("Tech", feeds.findById(userB, foreign.id()).orElseThrow().category());
    }

    @Test
    void renamingToTheSameNameIsUnchangedAndBadNamesAreA400() throws Exception {
        Feed feed = feeds.insert(userA, "One", "https://r5.example/feed", "https://r5.example", "Tech");

        mockMvc.perform(json(post("/api/v1/categories/rename"), tokenA,
                        "{\"oldCategory\":\"Tech\",\"newCategory\":\"Tech\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.unchanged").value(true))
                .andExpect(jsonPath("$.message").value("That is already the name of this category"));
        mockMvc.perform(json(post("/api/v1/categories/rename"), tokenA,
                        "{\"oldCategory\":\"Uncategorized\",\"newCategory\":\"Misc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Choose a category to rename"));
        mockMvc.perform(json(post("/api/v1/categories/rename"), tokenA,
                        "{\"oldCategory\":\"Tech\",\"newCategory\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("New category name is required"));
        mockMvc.perform(json(post("/api/v1/categories/rename"), tokenA,
                        "{\"oldCategory\":\"Nothing\",\"newCategory\":\"Else\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.unchanged").value(false))
                .andExpect(jsonPath("$.message").value("No feeds found in that category"));
        assertEquals("Tech", feeds.findById(userA, feed.id()).orElseThrow().category());
    }

    @Test
    void markingAFeedReadTouchesOnlyThatFeedAndOnlyTheOwners() throws Exception {
        Feed feed = feeds.insert(userA, "Blog", "https://m1.example/feed", "https://m1.example", null);
        Feed sibling = feeds.insert(userA, "Sibling", "https://m2.example/feed", "https://m2.example", null);
        Feed foreign = feeds.insert(userB, "Theirs", "https://m3.example/feed", "https://m3.example", null);
        article(feed, "k1", 1);
        article(feed, "k2", 2);
        article(sibling, "k3", 1);
        article(foreign, "k4", 1);

        mockMvc.perform(bearer(post("/api/v1/feeds/" + feed.id() + "/read"), tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marked").value(2));
        assertEquals(0, articles.count(userA, feed.id(), Boolean.TRUE));
        assertEquals(1, articles.count(userA, sibling.id(), Boolean.TRUE));
        mockMvc.perform(bearer(post("/api/v1/feeds/" + feed.id() + "/read"), tokenA))
                .andExpect(jsonPath("$.marked").value(0));

        mockMvc.perform(bearer(post("/api/v1/feeds/" + foreign.id() + "/read"), tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Feed not found"));
        assertEquals(1, articles.count(userB, foreign.id(), Boolean.TRUE));
    }

    @Test
    void suggestionsListTheDefaultsWithoutTheirAddressesAndOmitTheFollowedOnes() throws Exception {
        mockMvc.perform(bearer(get("/api/v1/feeds/suggestions"), tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions", hasSize(5)))
                .andExpect(jsonPath("$.suggestions[0].key").value("bbc-world"))
                .andExpect(jsonPath("$.suggestions[0].title").value("BBC World News"))
                .andExpect(jsonPath("$.suggestions[0].category").value("News"))
                .andExpect(jsonPath("$.suggestions[0].url").doesNotExist())
                .andExpect(content().string(not(containsString("bbci.co.uk"))));

        feeds.insert(userA, "BBC", "https://feeds.bbci.co.uk/news/world/rss.xml", "https://www.bbc.com", null);

        mockMvc.perform(bearer(get("/api/v1/feeds/suggestions"), tokenA))
                .andExpect(jsonPath("$.suggestions", hasSize(4)))
                .andExpect(jsonPath("$.suggestions[?(@.key == 'bbc-world')]", hasSize(0)));
        mockMvc.perform(bearer(get("/api/v1/feeds/suggestions"), tokenB))
                .andExpect(jsonPath("$.suggestions", hasSize(5)));
    }

    @Test
    void followingDefaultsNeedsAKeyAndReportsUnknownOnesAndFailures() throws Exception {
        mockMvc.perform(json(post("/api/v1/feeds/defaults"), tokenA, "{\"keys\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Choose at least one newspaper or site"));
        mockMvc.perform(json(post("/api/v1/feeds/defaults"), tokenA, "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Choose at least one newspaper or site"));
        mockMvc.perform(bearer(post("/api/v1/feeds/defaults"), tokenA))
                .andExpect(status().isBadRequest());

        mockMvc.perform(json(post("/api/v1/feeds/defaults"), tokenA, "{\"keys\":[\"nope\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(0))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0]").value("Unknown suggestion: nope"));
        assertEquals(0, feeds.countByUser(userA));
    }

    @Test
    void followingDefaultsAddsTheValidOnesAndCollectsPerSiteFailures() throws Exception {
        when(httpClient.get("https://feeds.bbci.co.uk/news/world/rss.xml"))
                .thenReturn(new SafeHttpClient.FetchedContent(
                        URI.create("https://feeds.bbci.co.uk/news/world/rss.xml"), FEED_XML, "application/rss+xml"));
        when(httpClient.get("https://feeds.npr.org/1001/rss.xml")).thenThrow(new SafeHttpClient.FetchException(
                "timeout", "The site did not answer", null));

        mockMvc.perform(json(post("/api/v1/feeds/defaults"), tokenA,
                        "{\"keys\":[\"bbc-world\",\"npr\",\"nope\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0]").value("NPR: The site did not answer"))
                .andExpect(jsonPath("$.errors[1]").value("Unknown suggestion: nope"));
        var followed = feeds.findAll(userA);
        assertEquals(1, followed.size());
        assertEquals("https://feeds.bbci.co.uk/news/world/rss.xml", followed.get(0).url());
        assertEquals("News", followed.get(0).category());
        mockMvc.perform(bearer(get("/api/v1/feeds/suggestions"), tokenA))
                .andExpect(jsonPath("$.suggestions", hasSize(4)));
    }

    @Test
    void aNewsletterFeedShowsItsSenderAndOtherFeedsHaveNone() throws Exception {
        Feed rss = feeds.insert(userA, "Blog", "https://n1.example/feed", "https://n1.example", null);
        jdbc.update("""
                INSERT INTO feeds (user_id, title, url, category, source)
                VALUES (?, 'Weekly Digest', 'newsletter:weekly@example.com', 'Newsletters', 'NEWSLETTER')
                """, userA);

        mockMvc.perform(bearer(get("/api/v1/feeds"), tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feeds[?(@.source == 'NEWSLETTER')].newsletterSender")
                        .value("weekly@example.com"))
                .andExpect(jsonPath("$.feeds[?(@.id == " + rss.id() + ")].newsletterSender")
                        .value(org.hamcrest.Matchers.contains(nullValue())))
                .andExpect(jsonPath("$.feeds[?(@.id == " + rss.id() + ")].hasError").value(false))
                .andExpect(jsonPath("$.feeds[?(@.id == " + rss.id() + ")].unreadCount").value(0));
        mockMvc.perform(json(put("/api/v1/feeds/" + rss.id() + "/category"), tokenA, "{\"category\":\"X\"}"))
                .andExpect(jsonPath("$.newsletterSender").value(nullValue()));
    }

    @Test
    void theWebsiteKeepsCsrfOnItsForms() throws Exception {
        mockMvc.perform(post("/settings/api-tokens").param("name", "x"))
                .andExpect(status().isForbidden());
    }
}
