package com.kindlerss.web;

import com.kindlerss.repository.UserRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.kindlerss.service.SafeHttpClient;
import com.kindlerss.service.ApiTokenService;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The hidden preview page at {@code /app}: behind the website's sign-in, a shell that
 * stays within the strict CSP, and linked from nowhere. Real login, real filters.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PreviewAppIntegrationTest {

    private static final String PASSWORD = "preview-app-password-1";
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
        registry.add("app.remember-me-key", () -> "preview-app-it-remember-key");
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ApiTokenService apiTokens;

    @MockitoBean SafeHttpClient httpClient;

    private String email;
    private String clientIp;

    @BeforeEach
    void seedAccount() {
        email = "preview-" + UUID.randomUUID() + "@example.com";
        long id = users.insert(email, passwordEncoder.encode(PASSWORD)).id();
        users.markEmailVerified(id);
        // The login form is rate limited per client address: each test is its own client.
        clientIp = "198.51.100." + NEXT_CLIENT.getAndIncrement();
    }

    private MvcResult logIn(MockHttpSession session) throws Exception {
        var request = post("/login").servletPath("/login").with(csrf())
                .with(r -> {
                    r.setRemoteAddr(clientIp);
                    return r;
                })
                .param("username", email)
                .param("password", PASSWORD);
        if (session != null) {
            request.session(session);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MockHttpSession signIn() throws Exception {
        MockHttpSession session = (MockHttpSession) logIn(null).getRequest().getSession(false);
        assertNotNull(session, "signing in should have created a session");
        return session;
    }

    @Test
    void aSignedOutVisitorIsSentToTheLoginPage() throws Exception {
        mockMvc.perform(get("/app"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void afterSigningInTheVisitorComesBackToTheApp() throws Exception {
        MvcResult bounced = mockMvc.perform(get("/app")).andReturn();
        MockHttpSession session = (MockHttpSession) bounced.getRequest().getSession(false);
        assertNotNull(session, "the request to /app should have been saved in a session");

        MvcResult login = logIn(session);

        assertEquals(302, login.getResponse().getStatus());
        String target = login.getResponse().getRedirectedUrl();
        assertTrue(target != null && target.matches("(https?://[^/]+)?/app(\\?continue)?"),
                "signing in should return to /app, went to " + target);
    }

    @Test
    void aSignedInReaderGetsTheShellWithoutInlineCodeOrStyles() throws Exception {
        MockHttpSession session = signIn();

        String body = mockMvc.perform(get("/app").session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/html")))
                .andExpect(content().string(containsString("<meta name=\"robots\" content=\"noindex\"")))
                .andExpect(content().string(containsString("<title>Extrablatt</title>")))
                .andExpect(content().string(containsString("viewport-fit=cover")))
                .andExpect(content().string(matchesPattern("(?s).*/js/preview/api-[0-9a-f]{32}\\.js.*")))
                .andExpect(content().string(matchesPattern("(?s).*/js/preview/articles-[0-9a-f]{32}\\.js.*")))
                .andExpect(content().string(matchesPattern("(?s).*/css/preview-[0-9a-f]{32}\\.css.*")))
                .andExpect(content().string(matchesPattern("(?s).*/app-kit/ak-tabbar-[0-9a-f]{32}\\.js.*")))
                // The old UI is not loaded here.
                .andExpect(content().string(not(containsString("/css/app"))))
                .andExpect(content().string(not(containsString("/js/app"))))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.matches("(?is).*<script(?![^>]*\\bsrc=)[^>]*>.*"), "no inline <script>");
        assertFalse(body.matches("(?is).*<style[\\s>].*"), "no <style> element");
        assertFalse(body.matches("(?is).*\\sstyle\\s*=.*"), "no style attribute");
        assertFalse(body.matches("(?is).*\\son[a-z]+\\s*=.*"), "no inline event handler");
        assertEquals(4, body.split("name=\"theme-color\"", -1).length - 1, "four theme-color metas");
    }

    @Test
    void theExistingPagesDoNotLinkToTheApp() throws Exception {
        MockHttpSession session = signIn();

        for (String path : new String[] {"/items", "/settings"}) {
            String body = mockMvc.perform(get(path).session(session))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertFalse(body.contains("href=\"/app\""), path + " links to /app");
            assertFalse(body.contains("/app\""), path + " mentions /app");
        }
    }

    @Test
    void theScriptsAndTheStylesheetArePartOfTheBuild() {
        for (String path : new String[] {"static/js/preview/api.js", "static/js/preview/send.js", "static/js/preview/articles.js",
                "static/js/preview/reader.js", "static/js/preview/feeds.js", "static/js/preview/settings.js",
                "static/js/preview/legal.js", "static/js/preview/whatsnew.js",
                "static/js/preview/app.js",
                "static/css/preview.css"}) {
            assertTrue(new ClassPathResource(path).exists(), path + " is on the classpath");
        }
    }

    @Test
    void theReaderScriptIsReferencedServedAndNeverAssignsInnerHtml() throws Exception {
        MockHttpSession session = signIn();

        String page = mockMvc.perform(get("/app").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.regex.Matcher script = java.util.regex.Pattern
                .compile("/js/preview/reader-[0-9a-f]{32}\\.js").matcher(page);
        assertTrue(script.find(), "the page references reader.js");

        mockMvc.perform(get(script.group()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Preview.reader")));

        String source = new String(new ClassPathResource("static/js/preview/reader.js").getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(source.contains(".innerHTML ="), "reader.js never assigns innerHTML");
        assertFalse(source.contains(".innerHTML="), "reader.js never assigns innerHTML");
    }

    @Test
    void theFeedsScriptIsReferencedServedAndStaysSafe() throws Exception {
        MockHttpSession session = signIn();

        String page = mockMvc.perform(get("/app").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.regex.Matcher script = java.util.regex.Pattern
                .compile("/js/preview/feeds-[0-9a-f]{32}\\.js").matcher(page);
        assertTrue(script.find(), "the page references feeds.js");
        assertTrue(page.indexOf("/js/preview/feeds-") < page.indexOf("/js/preview/app-"),
                "feeds.js loads before app.js");

        mockMvc.perform(get(script.group()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Preview.feeds")));

        String source = new String(new ClassPathResource("static/js/preview/feeds.js").getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(source.contains(".innerHTML ="), "feeds.js never assigns innerHTML");
        assertFalse(source.contains(".innerHTML="), "feeds.js never assigns innerHTML");
        // The API wants a blank category for Uncategorized, never the word in a feed URL.
        assertFalse(source.matches("(?s).*['\"]/feeds/['\"]\\s*\\+[^;]*Uncategorized.*"),
                "feeds.js never sends the literal Uncategorized category");
        assertFalse(source.contains("/feeds/Uncategorized"), "feeds.js has no /feeds/Uncategorized path");
    }

    @Test
    void theSettingsScriptIsReferencedServedAndStaysSafe() throws Exception {
        MockHttpSession session = signIn();

        String page = mockMvc.perform(get("/app").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.regex.Matcher script = java.util.regex.Pattern
                .compile("/js/preview/settings-[0-9a-f]{32}\\.js").matcher(page);
        assertTrue(script.find(), "the page references settings.js");
        assertTrue(page.indexOf("/js/preview/settings-") < page.indexOf("/js/preview/app-"),
                "settings.js loads before app.js");

        mockMvc.perform(get(script.group()).session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("javascript")))
                .andExpect(content().string(containsString("Preview.settings")));

        String source = classpathText("static/js/preview/settings.js");
        assertFalse(source.contains(".innerHTML ="), "settings.js never assigns innerHTML");
        assertFalse(source.contains(".innerHTML="), "settings.js never assigns innerHTML");
        // The session-only endpoints are for the signed-in browser: no bearer token here.
        assertFalse(source.contains("Authorization"), "settings.js never sends an Authorization header");
    }

    @Test
    void theLegalScriptIsReferencedBeforeAppServedAndStaysSafe() throws Exception {
        MockHttpSession session = signIn();

        String page = mockMvc.perform(get("/app").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.regex.Matcher script = java.util.regex.Pattern
                .compile("/js/preview/legal-[0-9a-f]{32}\\.js").matcher(page);
        assertTrue(script.find(), "the page references legal.js");
        assertTrue(page.indexOf("/js/preview/legal-") < page.indexOf("/js/preview/app-"),
                "legal.js loads before app.js");

        mockMvc.perform(get(script.group()).session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("javascript")))
                .andExpect(content().string(containsString("Preview.legal")));

        String source = classpathText("static/js/preview/legal.js");
        assertFalse(source.contains(".innerHTML ="), "legal.js never assigns innerHTML");
        assertFalse(source.contains(".innerHTML="), "legal.js never assigns innerHTML");
        assertFalse(source.contains("Authorization"), "legal.js never sends an Authorization header");
    }

    @Test
    void theWhatsNewScriptIsReferencedBeforeAppServedAndNeverAssignsInnerHtml() throws Exception {
        MockHttpSession session = signIn();

        String page = mockMvc.perform(get("/app").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.regex.Matcher script = java.util.regex.Pattern
                .compile("/js/preview/whatsnew-[0-9a-f]{32}\\.js").matcher(page);
        assertTrue(script.find(), "the page references whatsnew.js");
        assertTrue(page.indexOf("/js/preview/whatsnew-") < page.indexOf("/js/preview/app-"),
                "whatsnew.js loads before app.js");

        mockMvc.perform(get(script.group()).session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("javascript")))
                .andExpect(content().string(containsString("Preview.whatsNew")));

        for (String path : new String[] {"static/js/preview/whatsnew.js", "static/js/preview/articles.js"}) {
            String source = classpathText(path);
            assertFalse(source.contains(".innerHTML ="), path + " never assigns innerHTML");
            assertFalse(source.contains(".innerHTML="), path + " never assigns innerHTML");
        }
    }

    @Test
    void theArticlesListMarksReadByScrollingInBatches() throws Exception {
        String source = classpathText("static/js/preview/articles.js");

        assertTrue(source.contains("1500"), "the batch is flushed 1500 ms after the first id");
        assertTrue(source.contains("visibilitychange"), "the batch is flushed when the page is hidden");
        assertTrue(source.contains("/api/v1/articles/scroll-read"), "the list posts to scroll-read");
    }

    @Test
    void theAppPageCarriesTheCsrfTokenForTheRealFormPosts() throws Exception {
        MockHttpSession session = signIn();

        String page = mockMvc.perform(get("/app").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(page.matches("(?s).*<meta name=\"csrf-token\" content=\"[^\"]+\".*"),
                "a csrf-token meta with a value");
    }

    @Test
    void theKindleSetupSheetOpensTheInAppKindleGroup() throws Exception {
        String source = classpathText("static/js/preview/send.js");

        assertTrue(source.contains("#/settings/kindle"), "send.js links to the in-app Kindle group");
        assertFalse(source.contains("href: '/settings/kindle'"), "send.js no longer links to the old page");
        assertFalse(source.contains("href=\"/settings/kindle\""), "send.js no longer links to the old page");
        assertFalse(source.contains("_blank"), "the link no longer opens a new tab");
        assertTrue(source.contains("Where do I find it?"), "the visible link text is kept");
    }

    @Test
    void deletingTheAccountNeedsTheCsrfTokenAndASession() throws Exception {
        MockHttpSession session = signIn();
        long id = users.findByEmail(email).orElseThrow().id();

        mockMvc.perform(post("/account/delete").session(session))
                .andExpect(status().isForbidden());
        assertTrue(users.findById(id).isPresent(), "no CSRF token: the account is still there");

        // A bearer token is not a session. Even with a valid CSRF token the request is
        // not signed in, so it is sent to the login page and nothing is deleted.
        String secret = apiTokens.create(id, "settings test").secret();
        mockMvc.perform(post("/account/delete").with(csrf())
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/account/delete")
                        .header("Authorization", "Bearer " + secret))
                .andExpect(status().isForbidden());
        assertTrue(users.findById(id).isPresent(), "bearer auth only: the account is still there");
    }

    @Test
    void signingOutNeedsTheCsrfToken() throws Exception {
        MockHttpSession session = signIn();

        mockMvc.perform(post("/logout").session(session))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/app").session(session))
                .andExpect(status().isOk());
    }

    private static String classpathText(String path) throws java.io.IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
