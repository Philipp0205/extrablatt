package com.kindlerss;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Boots the whole application context (security, filters, scheduler, mail, and
 * JDBC + Flyway) against an embedded Postgres to catch wiring regressions that the
 * sliced web tests cannot.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ApplicationContextSmokeTest {

    private static EmbeddedPostgres postgres;

    @LocalServerPort
    int port;

    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        registry.add("spring.datasource.url",
                () -> "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        // Mail is not exercised here; keep it pointed at localhost so nothing dials out.
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("app.mail-from", () -> "noreply@example.com");
        registry.add("app.remember-me-key", () -> "smoke-test-remember-key");
    }

    @Test
    void contextLoads() {
    }

    @Autowired
    org.springframework.mail.javamail.JavaMailSender mailSender;

    /** Every mail service gets the sender that refuses scrubbed staging addresses. */
    @Test
    void theMailSenderRefusesInvalidRecipients() {
        assertTrue(mailSender instanceof com.kindlerss.config.RecipientGuardMailSender,
                mailSender.getClass().getName());
    }

    /**
     * A first-time visitor has to receive the whole login page.
     *
     * <p>Worth a test against a real server rather than a mocked one, because what
     * broke here only breaks on a real one: a page that fills Tomcat's response
     * buffer before the login form's CSRF token is made (creating one starts a
     * session) arrives cut short at the {@code <form>}. A mocked response has no
     * buffer to fill and so never notices.
     */
    @Test
    void aFirstTimeVisitorGetsTheWholeLoginPage() {
        ResponseEntity<String> response = new TestRestTemplate()
                .getForEntity("http://localhost:" + port + "/login", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String page = response.getBody();
        assertNotNull(page);
        assertTrue(HASHED_STYLESHEET.matcher(page).find(), "the page links the hashed stylesheet");
        assertTrue(page.contains("name=\"_csrf\""), "the form is complete enough to submit");
        assertTrue(page.stripTrailing().endsWith("</html>"), "the page is not cut short");
    }

    /**
     * The address a page links is one the real server answers, without a session.
     * The hashed name is resolved by the resource chain, which only the whole
     * context wires the way production does.
     */
    @Test
    void theLinkedStylesheetIsServed() {
        TestRestTemplate client = new TestRestTemplate();
        String page = client.getForObject("http://localhost:" + port + "/login", String.class);
        java.util.regex.Matcher link = HASHED_STYLESHEET.matcher(page);
        assertTrue(link.find(), "the page links the hashed stylesheet");

        ResponseEntity<String> css = client.getForEntity("http://localhost:" + port + link.group(1), String.class);
        assertEquals(HttpStatus.OK, css.getStatusCode());
        assertNotNull(css.getBody());
        assertTrue(css.getBody().contains("box-sizing: border-box"), "it is the app stylesheet");
        assertFalse(css.getHeaders().containsKey("Set-Cookie"), "fetching it starts no session");
    }

    /**
     * The error page is styled too.
     *
     * <p>It is the one page not rendered by a controller of ours (Spring's own
     * {@code BasicErrorController} renders it), so it is the one that would quietly
     * miss whatever the others are given. Hence the whole context rather than a
     * sliced one: a slice would supply that controller from our own configuration
     * and never ask the question.
     */
    @Test
    @WithMockUser
    void theErrorPageCarriesTheStylesheetToo() throws Exception {
        String page = mockMvc.perform(get("/error").accept(MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString();

        assertTrue(HASHED_STYLESHEET.matcher(page).find(), "the error page links the hashed stylesheet");
        assertFalse(page.contains("<style"), "and carries no inline styling");
    }

    private static final java.util.regex.Pattern HASHED_STYLESHEET =
            java.util.regex.Pattern.compile("<link rel=\"stylesheet\" href=\"(/css/app-[0-9a-f]{32}\\.css)\"");

    /**
     * The rate limiter keys on the address ClientIpResolver works out, with the
     * whole filter chain in place, ForwardedHeaderFilter included. That filter
     * hands everything after it the leftmost X-Forwarded-For entry as the remote
     * address, so a client that changed it on every attempt used to get a fresh
     * allowance each time.
     */
    @Test
    void loginAttemptsAreLimitedPerResolvedClientNotPerForgedHeader() throws Exception {
        String chain = ", 203.0.113.77, 172.68.10.20";
        for (int i = 0; i < 10; i++) {
            assertTrue(loginFrom("100.64.0.2", "198.51.100." + i + chain) != 429, "attempt " + i);
        }
        assertEquals(429, loginFrom("100.64.0.2", "198.51.100.99" + chain));
        assertTrue(loginFrom("100.64.0.2", "203.0.113.78, 172.68.10.20") != 429,
                "another reader behind the same proxies is not affected");
    }

    private int loginFrom(String peer, String forwardedFor) throws Exception {
        // MockMvc leaves the servlet path empty; Tomcat sets it to the path.
        return mockMvc.perform(post("/login").servletPath("/login")
                        .with(csrf())
                        .with(request -> {
                            request.setRemoteAddr(peer);
                            return request;
                        })
                        .header("X-Forwarded-For", forwardedFor)
                        .param("username", "nobody@example.com")
                        .param("password", "wrong"))
                .andReturn().getResponse().getStatus();
    }

    /**
     * An address nothing serves reaches the static resource handler, which throws
     * NoResourceFoundException. That has to come out as a 404 on the styled error
     * page, not as a 500 from the catch-all handler.
     */
    @Test
    @WithMockUser
    void anUnknownAddressIsAStyled404() throws Exception {
        String page = mockMvc.perform(get("/no-such-page").accept(MediaType.TEXT_HTML))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertTrue(HASHED_STYLESHEET.matcher(page).find(), "the 404 page links the hashed stylesheet");
        assertTrue(page.contains("Not found"), page);
    }
}
