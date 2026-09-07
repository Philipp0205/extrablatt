package com.kindlerss;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the whole application context (security, filters, scheduler, mail, and
 * JDBC + Flyway) against an embedded Postgres to catch wiring regressions that the
 * sliced web tests cannot.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationContextSmokeTest {

    private static EmbeddedPostgres postgres;

    @LocalServerPort
    int port;

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

    /**
     * A first-time visitor has to receive the whole login page.
     *
     * <p>Worth a test against a real server rather than a mocked one, because what
     * broke here only breaks on a real one. Pages carry the stylesheet in their
     * {@code <head>}, which spends Tomcat's 8 KB response buffer before the
     * {@code <body>} starts; with Thymeleaf writing as it rendered, the response was
     * committed by then, and the login form's CSRF token — created on demand, and
     * creating one starts a session — could no longer be made. The page arrived
     * truncated at the {@code <form>}. A mocked response has no buffer to fill and
     * so never notices.
     */
    @Test
    void aFirstTimeVisitorGetsTheWholeLoginPage() {
        ResponseEntity<String> response = new TestRestTemplate()
                .getForEntity("http://localhost:" + port + "/login", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String page = response.getBody();
        assertNotNull(page);
        assertTrue(page.contains("box-sizing: border-box"), "the stylesheet rides in the page");
        assertTrue(page.contains("name=\"_csrf\""), "the form is complete enough to submit");
        assertTrue(page.stripTrailing().endsWith("</html>"), "the page is not cut short");
    }
}
