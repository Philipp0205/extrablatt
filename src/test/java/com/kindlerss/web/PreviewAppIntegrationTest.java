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
                "static/js/preview/app.js", "static/css/preview.css"}) {
            assertTrue(new ClassPathResource(path).exists(), path + " is on the classpath");
        }
    }
}
