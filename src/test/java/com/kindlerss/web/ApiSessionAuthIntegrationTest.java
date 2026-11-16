package com.kindlerss.web;

import com.kindlerss.domain.BillingInterval;
import com.kindlerss.domain.Feed;
import com.kindlerss.domain.Plan;
import com.kindlerss.domain.Subscription;
import com.kindlerss.domain.SubscriptionStatus;
import com.kindlerss.repository.ArticleRepository;
import com.kindlerss.repository.FeedRepository;
import com.kindlerss.repository.SubscriptionRepository;
import com.kindlerss.repository.UserRepository;
import com.kindlerss.service.ApiTokenService;
import com.kindlerss.service.KindleMailService;
import com.kindlerss.service.SafeHttpClient;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The browser client's way into {@code /api/v1}: the signed-in session cookie, with a
 * double-submit CSRF cookie ({@code XSRF-TOKEN} / {@code X-XSRF-TOKEN}). Bearer tokens
 * must keep working exactly as before. Real login, real filters, real database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiSessionAuthIntegrationTest {

    private static final String PASSWORD = "session-api-password-1";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";
    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(1);

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
        registry.add("app.remember-me-key", () -> "api-session-it-remember-key");
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired FeedRepository feeds;
    @Autowired ArticleRepository articles;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired ApiTokenService tokenService;
    @Autowired PasswordEncoder passwordEncoder;

    @MockitoBean SafeHttpClient httpClient;
    @MockitoBean KindleMailService kindleMailService;

    private String emailA;
    private String emailB;
    private long userA;
    private long userB;
    private String clientIp;

    @BeforeEach
    void seedTwoAccounts() {
        emailA = "sa-" + UUID.randomUUID() + "@example.com";
        emailB = "sb-" + UUID.randomUUID() + "@example.com";
        userA = verifiedUser(emailA);
        userB = verifiedUser(emailB);
        // The login form is rate limited per client address: each test is its own client.
        clientIp = "203.0.113." + NEXT_CLIENT.getAndIncrement();
    }

    private long verifiedUser(String email) {
        long id = users.insert(email, passwordEncoder.encode(PASSWORD)).id();
        users.markEmailVerified(id);
        return id;
    }

    private long article(Feed feed, String guid) {
        return articles.insert(feed.id(), guid, "Title " + guid, "https://example.com/" + guid, "Ann",
                Instant.now().minus(1, ChronoUnit.MINUTES), "<p>Summary " + guid + "</p>", null);
    }

    /** A real sign-in through the website's login form. */
    private MockHttpSession signIn(String email) throws Exception {
        MvcResult login = mockMvc.perform(post("/login").servletPath("/login").with(csrf())
                        .with(request -> {
                            request.setRemoteAddr(clientIp);
                            return request;
                        })
                        .param("username", email)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(session, "signing in should have created a session");
        return session;
    }

    /** What the browser client does first: a GET with the session, to receive the CSRF cookie. */
    private String xsrfTokenFor(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/account").session(session))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse().getCookie(XSRF_COOKIE);
        assertNotNull(cookie, "the first GET should set the XSRF-TOKEN cookie");
        return cookie.getValue();
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request,
                                                          MockHttpSession session, String token) {
        return request.session(session).cookie(new Cookie(XSRF_COOKIE, token)).header(XSRF_HEADER, token);
    }

    // --- reading with a session ---

    @Test
    void aSignedInSessionReadsTheApiAndGetsAScriptReadableCsrfCookieOnTheFirstGet() throws Exception {
        MockHttpSession session = signIn(emailA);

        MvcResult account = mockMvc.perform(get("/api/v1/account").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(emailA))
                .andReturn();
        MvcResult items = mockMvc.perform(get("/api/v1/items").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andReturn();

        for (MvcResult result : new MvcResult[] {account, items}) {
            Cookie xsrf = result.getResponse().getCookie(XSRF_COOKIE);
            assertNotNull(xsrf, "every API response carries the XSRF-TOKEN cookie");
            assertFalse(xsrf.isHttpOnly(), "the page's script has to be able to read it");
            assertEquals("/", xsrf.getPath());
            assertEquals("Lax", xsrf.getAttribute("SameSite"));
            assertFalse(xsrf.getValue().isBlank());
            assertFalse(result.getResponse().getHeaders("Set-Cookie").stream()
                    .anyMatch(value -> value.startsWith(XSRF_COOKIE + "=") && value.contains("HttpOnly")));
        }
        assertEquals(session.getId(), account.getRequest().getSession(false).getId());
    }

    @Test
    void theCsrfCookieIsSecureOnASecureRequest() throws Exception {
        MockHttpSession session = signIn(emailA);

        MvcResult result = mockMvc.perform(get("/api/v1/account").session(session).secure(true))
                .andExpect(status().isOk())
                .andReturn();

        assertTrue(result.getResponse().getCookie(XSRF_COOKIE).getSecure());
    }

    // --- changing things with a session ---

    @Test
    void sendingWithASessionNeedsTheCsrfHeader() throws Exception {
        Feed feed = feeds.insert(userA, "Blog", "https://s.example/feed", "https://s.example", null);
        long id = article(feed, "s1");
        MockHttpSession session = signIn(emailA);
        String token = xsrfTokenFor(session);

        mockMvc.perform(post("/api/v1/articles/" + id + "/send").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", containsString("application/json")))
                .andExpect(jsonPath("$.error").isNotEmpty());
        // A cookie alone is what a forged cross-site request would carry.
        mockMvc.perform(post("/api/v1/articles/" + id + "/send").session(session)
                        .cookie(new Cookie(XSRF_COOKIE, token))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/articles/" + id + "/send").session(session)
                        .cookie(new Cookie(XSRF_COOKIE, token)).header(XSRF_HEADER, "not-the-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").isNotEmpty());
        verify(kindleMailService, never()).sendToKindle(anyLong(), anyLong(), anyBoolean());

        mockMvc.perform(withCsrf(post("/api/v1/articles/" + id + "/send"), session, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Sent to Kindle"));
        verify(kindleMailService).sendToKindle(userA, id, false);
    }

    @Test
    void theKindleAddressNeedsCsrfAndCanBeSavedAndCleared() throws Exception {
        MockHttpSession session = signIn(emailA);
        String token = xsrfTokenFor(session);

        mockMvc.perform(put("/api/v1/account/kindle-email").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kindleEmail\":\"me@kindle.com\"}"))
                .andExpect(status().isForbidden());
        assertNull(users.findById(userA).orElseThrow().kindleEmail());

        mockMvc.perform(withCsrf(put("/api/v1/account/kindle-email"), session, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kindleEmail\":\" me@kindle.com \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kindleEmail").value("me@kindle.com"))
                .andExpect(jsonPath("$.kindleConfigured").value(true));
        assertEquals("me@kindle.com", users.findById(userA).orElseThrow().kindleEmail());
        mockMvc.perform(get("/api/v1/account").session(session))
                .andExpect(jsonPath("$.kindleEmail").value("me@kindle.com"))
                .andExpect(jsonPath("$.kindleConfigured").value(true));

        mockMvc.perform(withCsrf(put("/api/v1/account/kindle-email"), session, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kindleEmail\":\"not an address\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
        assertEquals("me@kindle.com", users.findById(userA).orElseThrow().kindleEmail());

        mockMvc.perform(withCsrf(put("/api/v1/account/kindle-email"), session, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kindleEmail\":\"  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kindleEmail").doesNotExist())
                .andExpect(jsonPath("$.kindleConfigured").value(false));
        assertNull(users.findById(userA).orElseThrow().kindleEmail());

        mockMvc.perform(withCsrf(put("/api/v1/account/kindle-email"), session, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kindleEmail\":\"me@kindle.com\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(withCsrf(put("/api/v1/account/kindle-email"), session, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kindleEmail\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kindleConfigured").value(false));
        assertNull(users.findById(userA).orElseThrow().kindleEmail());
    }

    // --- the in-app reader ---

    @Test
    void aSessionReadsASanitizedArticleWithItsLinks() throws Exception {
        Feed feed = feeds.insert(userA, "Blog", "https://r.example/feed", "https://r.example", null);
        long id = articles.insert(feed.id(), "r1", "Hostile", "https://example.com/r1", "Ann", Instant.now(),
                "<p>Summary</p>", "<p>Safe text</p><script>alert(1)</script>"
                        + "<img src=\"https://example.com/i.png\" onerror=\"alert(2)\">"
                        + "<a href=\"javascript:alert(3)\">bad link</a>"
                        + "<p style=\"color:red\">styled</p>");
        MockHttpSession session = signIn(emailA);

        mockMvc.perform(get("/api/v1/articles/" + id).param("images", "true").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/r1"))
                .andExpect(jsonPath("$.commentsUrl").value(nullValue()))
                .andExpect(jsonPath("$.contentHtml").value(containsString("Safe text")))
                .andExpect(jsonPath("$.contentHtml").value(not(containsString("<script"))))
                .andExpect(jsonPath("$.contentHtml").value(not(containsString("alert"))))
                .andExpect(jsonPath("$.contentHtml").value(not(containsString("onerror"))))
                .andExpect(jsonPath("$.contentHtml").value(not(containsString("javascript:"))))
                .andExpect(jsonPath("$.contentHtml").value(not(containsString("style="))))
                .andExpect(jsonPath("$.contentHtml").value(not(containsString("color:red"))));
    }

    @Test
    void aSessionCanMarkAnArticleReadAndUnreadWithCsrf() throws Exception {
        Feed feed = feeds.insert(userA, "Blog", "https://u.example/feed", "https://u.example", null);
        long id = article(feed, "u1");
        MockHttpSession session = signIn(emailA);
        String token = xsrfTokenFor(session);

        mockMvc.perform(post("/api/v1/articles/" + id + "/read").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
                .andExpect(status().isForbidden());
        assertEquals(1, articles.count(userA, feed.id(), Boolean.TRUE));

        mockMvc.perform(withCsrf(post("/api/v1/articles/" + id + "/read"), session, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));
        assertEquals(0, articles.count(userA, feed.id(), Boolean.TRUE));

        mockMvc.perform(withCsrf(post("/api/v1/articles/" + id + "/read"), session, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"read\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(false));
        assertEquals(1, articles.count(userA, feed.id(), Boolean.TRUE));
    }

    // --- the Feeds screen ---

    @Test
    void theFeedsScreenWritesNeedTheCsrfHeaderAndWorkWithIt() throws Exception {
        Feed feed = feeds.insert(userA, "Blog", "https://fs.example/feed", "https://fs.example", "Tech");
        article(feed, "fs1");
        MockHttpSession session = signIn(emailA);
        String token = xsrfTokenFor(session);

        mockMvc.perform(put("/api/v1/feeds/" + feed.id() + "/category").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"News\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").isNotEmpty());
        mockMvc.perform(post("/api/v1/categories/rename").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldCategory\":\"Tech\",\"newCategory\":\"News\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").isNotEmpty());
        mockMvc.perform(post("/api/v1/feeds/" + feed.id() + "/read").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").isNotEmpty());
        mockMvc.perform(post("/api/v1/feeds/defaults").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"nope\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").isNotEmpty());
        assertEquals("Tech", feeds.findById(userA, feed.id()).orElseThrow().category());
        assertEquals(1, articles.count(userA, feed.id(), Boolean.TRUE));

        mockMvc.perform(withCsrf(put("/api/v1/feeds/" + feed.id() + "/category"), session, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"News\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("News"));
        mockMvc.perform(withCsrf(post("/api/v1/categories/rename"), session, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldCategory\":\"News\",\"newCategory\":\"World\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1));
        mockMvc.perform(withCsrf(post("/api/v1/feeds/" + feed.id() + "/read"), session, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marked").value(1));
        mockMvc.perform(withCsrf(post("/api/v1/feeds/defaults"), session, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keys\":[\"nope\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(0));
        mockMvc.perform(get("/api/v1/feeds/suggestions").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestions[0].key").value("bbc-world"));
        assertEquals("World", feeds.findById(userA, feed.id()).orElseThrow().category());
        assertEquals(0, articles.count(userA, feed.id(), Boolean.TRUE));
    }

    // --- bearer tokens are unchanged ---

    @Test
    void aBearerTokenNeedsNoCsrfAndSetsNoCookieOrSession() throws Exception {
        String token = tokenService.create(userA, "A").secret();

        MvcResult read = mockMvc.perform(get("/api/v1/account").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();
        assertNull(read.getRequest().getSession(false));

        MvcResult write = mockMvc.perform(put("/api/v1/account/kindle-email")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kindleEmail\":\"tok@kindle.com\"}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();
        assertNull(write.getRequest().getSession(false));
        assertEquals("tok@kindle.com", users.findById(userA).orElseThrow().kindleEmail());
    }

    @Test
    void aBearerRequestIgnoresASessionCookieAndAWrongTokenIsStillA401() throws Exception {
        MockHttpSession sessionOfB = signIn(emailB);
        String tokenA = tokenService.create(userA, "A").secret();

        // The token's account answers, not the session's.
        mockMvc.perform(get("/api/v1/account").session(sessionOfB)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(emailA));
        // A bad token is not rescued by a good session, and needs no CSRF to be told so.
        mockMvc.perform(get("/api/v1/account").session(sessionOfB)
                        .header("Authorization", "Bearer eb_notatoken"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.error").isNotEmpty());
        mockMvc.perform(put("/api/v1/account/kindle-email").session(sessionOfB)
                        .header("Authorization", "Bearer eb_notatoken")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kindleEmail\":\"x@kindle.com\"}"))
                .andExpect(status().isUnauthorized());
        assertNull(users.findById(userB).orElseThrow().kindleEmail());
    }

    // --- no credentials ---

    @Test
    void noCredentialsIsA401WithJsonAndNoRedirectOrSession() throws Exception {
        MvcResult read = mockMvc.perform(get("/api/v1/account"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", containsString("application/json")))
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.error").isNotEmpty())
                .andReturn();
        assertNull(read.getRequest().getSession(false));
        // A write with neither credentials nor CSRF token is stopped by the CSRF check first:
        // still JSON and no redirect.
        mockMvc.perform(put("/api/v1/account/kindle-email")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kindleEmail\":\"a@b.com\"}"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", containsString("application/json")))
                .andExpect(header().doesNotExist("Location"));
    }

    // --- isolation ---

    @Test
    void oneAccountsSessionCannotReadTheOthersArticleOrFeed() throws Exception {
        Feed feedB = feeds.insert(userB, "B blog", "https://bb.example/feed", "https://bb.example", null);
        long articleB = article(feedB, "b1");
        MockHttpSession sessionA = signIn(emailA);

        mockMvc.perform(get("/api/v1/articles/" + articleB).session(sessionA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
        mockMvc.perform(get("/api/v1/items").param("feed", String.valueOf(feedB.id())).session(sessionA))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/feeds").session(sessionA))
                .andExpect(content().string(not(containsString("B blog"))));

        mockMvc.perform(get("/api/v1/articles/" + articleB).session(signIn(emailB)))
                .andExpect(status().isOk());
    }

    // --- stale sessions ---

    @Test
    void aSessionOfAnAccountWhoseCredentialsChangedIsRejected() throws Exception {
        MockHttpSession session = signIn(emailA);
        mockMvc.perform(get("/api/v1/account").session(session)).andExpect(status().isOk());

        users.updatePasswordHash(userA, passwordEncoder.encode("a-brand-new-password-1"));

        mockMvc.perform(get("/api/v1/account").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void aSessionOfAnAccountThatWasSwitchedOffIsRejected() throws Exception {
        MockHttpSession session = signIn(emailA);

        jdbc.update("UPDATE users SET disabled_at = NOW() WHERE id = ?", userA);

        mockMvc.perform(get("/api/v1/account").session(session)).andExpect(status().isUnauthorized());
    }

    // --- trial fields ---

    @Test
    void theAccountSaysWhetherTheReaderIsOnATrialAndWhenItEnds() throws Exception {
        Instant ends = Instant.now().plus(5, ChronoUnit.DAYS);
        subscriptions.save(new Subscription(userA, Plan.SUPPORTER, SubscriptionStatus.TRIALING,
                BillingInterval.MONTHLY, null, null, null, ends, false, null));
        String expected = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
                .format(ends.atZone(ZoneId.of("Europe/Berlin")));

        mockMvc.perform(get("/api/v1/account").session(signIn(emailA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entitlement.onTrial").value(true))
                .andExpect(jsonPath("$.entitlement.trialEndsOn").value(expected))
                .andExpect(jsonPath("$.entitlement.trialEndsAt").isNotEmpty());

        // Not on a trial: the flag is false and there is no date.
        mockMvc.perform(get("/api/v1/account").session(signIn(emailB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entitlement.onTrial").value(false))
                .andExpect(jsonPath("$.entitlement.trialEndsOn").doesNotExist());
    }
}
