package com.kindlerss.web;

import com.kindlerss.repository.FeedRepository;
import com.kindlerss.repository.UserRepository;
import com.kindlerss.service.KindleMailService;
import com.kindlerss.service.SafeHttpClient;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signing in from a Kindle, over real HTTP so that the cookies are the ones a browser
 * would be handed: the e-ink login page, a password login that asks to be remembered,
 * and coming back later with nothing but the remember-me cookie.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KindleSignInIntegrationTest {

    private static final String KINDLE_UA = "Mozilla/5.0 (X11; U; Linux armv7l like Android; en-us)"
            + " AppleWebKit/531.2+ (KHTML, like Gecko) Version/5.0 Safari/533.2+ Kindle/3.0+";
    private static final String PASSWORD = "kindle-signin-password-1";
    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

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
        registry.add("app.remember-me-key", () -> "kindle-signin-it-remember-key");
    }

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired FeedRepository feeds;
    @Autowired PasswordEncoder passwordEncoder;

    @MockitoBean SafeHttpClient httpClient;
    @MockitoBean KindleMailService kindleMailService;

    /** Redirects are the thing under test, so they are never followed. */
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private String email;

    @BeforeEach
    void seedAccount() {
        email = "ksi-" + UUID.randomUUID() + "@example.com";
        long id = users.insert(email, passwordEncoder.encode(PASSWORD)).id();
        users.markEmailVerified(id);
        feeds.insert(id, "Coastal Notes", "https://coast.example/feed", "https://coast.example", null);
    }

    /** A response with the cookies it set, by name, with their attributes kept. */
    private record Reply(HttpResponse<String> response, Map<String, String> setCookies) {
        int status() {
            return response.statusCode();
        }

        String body() {
            return response.body();
        }

        String location() {
            return response.headers().firstValue("Location").orElse(null);
        }

        String cookieValue(String name) {
            String header = setCookies.get(name);
            return header == null ? null : header.split(";", 2)[0].substring(name.length() + 1);
        }

        /** The Max-Age attribute of a cookie that was set, or -1 when there is none. */
        long maxAge(String name) {
            String header = setCookies.get(name);
            if (header == null) {
                return -1;
            }
            Matcher m = Pattern.compile("(?i);\\s*Max-Age=(-?\\d+)").matcher(header);
            return m.find() ? Long.parseLong(m.group(1)) : -1;
        }
    }

    private Reply send(HttpRequest.Builder request, Map<String, String> cookies) throws Exception {
        request.header("User-Agent", KINDLE_UA);
        if (!cookies.isEmpty()) {
            StringBuilder header = new StringBuilder();
            cookies.forEach((name, value) -> header.append(header.length() == 0 ? "" : "; ")
                    .append(name).append('=').append(value));
            request.header("Cookie", header.toString());
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        Map<String, String> setCookies = new LinkedHashMap<>();
        for (String header : response.headers().allValues("Set-Cookie")) {
            setCookies.put(header.substring(0, header.indexOf('=')), header);
        }
        return new Reply(response, setCookies);
    }

    private Reply get(String path, Map<String, String> cookies) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET(), cookies);
    }

    /** Opens the login page as a first-time visitor and submits the form. */
    private Reply logIn(String password, boolean rememberMe) throws Exception {
        Reply form = get("/login", Map.of());
        Matcher token = CSRF.matcher(form.body());
        assertTrue(token.find(), "the login form carries a CSRF token");
        String session = form.cookieValue("JSESSIONID");
        assertNotNull(session, "the login page starts the session the token belongs to");

        StringBuilder body = new StringBuilder("_csrf=" + enc(token.group(1))
                + "&username=" + enc(email) + "&password=" + enc(password));
        if (rememberMe) {
            body.append("&remember-me=true");
        }
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString())),
                Map.of("JSESSIONID", session));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Test
    void theLoginPageIsTheEinkPageWithoutTheAppKit() throws Exception {
        Reply page = get("/login", Map.of());

        assertEquals(200, page.status());
        assertTrue(Pattern.compile("<html[^>]*\\sclass=\"[^\"]*\\beink\\b").matcher(page.body()).find(),
                "html carries the eink class");
        assertFalse(page.body().contains("app-kit"), "the e-ink page links nothing from the app kit");
        assertTrue(page.body().contains("name=\"remember-me\""), "the form offers remember-me");
    }

    @Test
    void aPasswordLoginThatAsksToBeRememberedSetsASessionAndALongLivedCookie() throws Exception {
        Reply login = logIn(PASSWORD, true);

        assertEquals(302, login.status());
        assertEquals("/", URI.create(login.location()).getPath());
        assertNotNull(login.cookieValue("JSESSIONID"), "a session cookie is set");
        assertNotNull(login.cookieValue("remember-me"), "a remember-me cookie is set");
        // SecurityConfig: one year.
        assertEquals(365L * 24 * 60 * 60, login.maxAge("remember-me"));
    }

    @Test
    void theRememberMeCookieAloneOpensTheArticleList() throws Exception {
        Reply login = logIn(PASSWORD, true);
        String remember = login.cookieValue("remember-me");
        assertNotNull(remember);

        // A new visit: no session cookie, only what a Kindle kept in its cookie jar.
        Reply items = get("/items", Map.of("remember-me", remember));

        assertEquals(200, items.status(), "not a redirect to /login: " + items.location());
        assertTrue(items.body().contains("class=\"flash") || items.body().contains("<main"),
                "the article list was rendered");
    }

    @Test
    void aWrongPasswordIsTheNormalFailedLoginRedirectWithoutARememberMeCookie() throws Exception {
        Reply login = logIn("not-the-password", true);

        assertEquals(302, login.status());
        assertEquals("/login", URI.create(login.location()).getPath());
        assertEquals("error", URI.create(login.location()).getQuery());
        // A failed login only ever clears the cookie, it never sets one.
        assertTrue(login.cookieValue("remember-me") == null || login.maxAge("remember-me") == 0,
                String.valueOf(login.setCookies().get("remember-me")));
    }
}
