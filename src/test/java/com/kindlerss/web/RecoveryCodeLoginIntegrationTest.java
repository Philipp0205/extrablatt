package com.kindlerss.web;

import com.kindlerss.repository.UserRepository;
import com.kindlerss.service.KindleMailService;
import com.kindlerss.service.RecoveryCodeService;
import com.kindlerss.service.SafeHttpClient;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Logging in with a recovery code, against the whole application and a real database. */
@SpringBootTest
@AutoConfigureMockMvc
class RecoveryCodeLoginIntegrationTest {

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
        registry.add("app.remember-me-key", () -> "recovery-login-it-remember-key");
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RecoveryCodeService recoveryCodes;

    @MockitoBean SafeHttpClient httpClient;
    @MockitoBean KindleMailService kindleMailService;

    private long userId;
    private String clientIp;

    @BeforeEach
    void seedAccount() {
        userId = users.insert("rcl-" + UUID.randomUUID() + "@example.com", "hash").id();
        users.markEmailVerified(userId);
        // Each test is its own client: the login throttle is keyed by address.
        clientIp = "198.51.100." + NEXT_CLIENT.getAndIncrement();
    }

    private MockHttpServletRequestBuilder attempt(String code, String ip) {
        return post("/login/recovery").servletPath("/login/recovery").with(csrf()).param("code", code)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                });
    }

    private MockHttpServletRequestBuilder attempt(String code) {
        return attempt(code, clientIp);
    }

    private void assertSignedIn(MvcResult result) throws Exception {
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session, "a session should have been created");
        mockMvc.perform(get("/settings/account").session(session))
                .andExpect(status().isOk());
    }

    private void assertNotSignedIn(MvcResult result) throws Exception {
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        MockHttpServletRequestBuilder next = get("/settings/account");
        if (session != null) {
            next.session(session);
        }
        mockMvc.perform(next)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void theRightCodeLogsInAndGoesToTheHomePage() throws Exception {
        String code = recoveryCodes.createOrRotate(userId);

        MvcResult result = mockMvc.perform(attempt(code))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();

        assertSignedIn(result);
        assertNotNull(jdbc.queryForObject("SELECT last_login_at FROM users WHERE id = ?",
                java.sql.Timestamp.class, userId));
    }

    @Test
    void aWrongCodeFailsWithTheGenericErrorAndNoSession() throws Exception {
        recoveryCodes.createOrRotate(userId);

        MvcResult result = mockMvc.perform(attempt("ABCD-EFGH-JKLM"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?recoveryError"))
                .andReturn();

        assertNotSignedIn(result);
        // A failed login only ever clears the remember-me cookie, as with a password.
        Cookie cleared = result.getResponse().getCookie("remember-me");
        assertTrue(cleared == null || cleared.getMaxAge() == 0);
    }

    @Test
    void notACodeAtAllFailsTheSameWay() throws Exception {
        MvcResult result = mockMvc.perform(attempt("not a code"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?recoveryError"))
                .andReturn();

        assertNotSignedIn(result);
        mockMvc.perform(attempt(""))
                .andExpect(redirectedUrl("/login?recoveryError"));
    }

    @Test
    void lowercaseAndSpacedAndDashedVariantsOfTheRightCodeWork() throws Exception {
        String code = recoveryCodes.createOrRotate(userId);
        String[] variants = {
                code.toLowerCase(),
                code.replace("-", " "),
                code.replace("-", "").toLowerCase(),
                "  " + code.toLowerCase().replace("-", " - ") + " "
        };

        for (String variant : variants) {
            MvcResult result = mockMvc.perform(attempt(variant))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/"))
                    .andReturn();
            assertSignedIn(result);
        }
    }

    @Test
    void anUnverifiedAccountGetsTheUnverifiedAnswerAndNoSession() throws Exception {
        long unverified = users.insert("rcl-" + UUID.randomUUID() + "@example.com", "hash").id();
        String code = recoveryCodes.createOrRotate(unverified);

        MvcResult result = mockMvc.perform(attempt(code))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?unverified"))
                .andReturn();

        assertNotSignedIn(result);
    }

    @Test
    void aDisabledAccountFailsLikeAWrongCode() throws Exception {
        String code = recoveryCodes.createOrRotate(userId);
        jdbc.update("UPDATE users SET disabled_at = NOW() WHERE id = ?", userId);

        MvcResult result = mockMvc.perform(attempt(code))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?recoveryError"))
                .andReturn();

        assertNotSignedIn(result);
    }

    @Test
    void theRememberMeCookieIsSetOnlyWhenTheBoxIsTicked() throws Exception {
        String code = recoveryCodes.createOrRotate(userId);

        MvcResult without = mockMvc.perform(attempt(code))
                .andExpect(redirectedUrl("/"))
                .andReturn();
        assertNull(without.getResponse().getCookie("remember-me"));

        MvcResult with = mockMvc.perform(attempt(code).param("remember-me", "true"))
                .andExpect(redirectedUrl("/"))
                .andReturn();
        Cookie cookie = with.getResponse().getCookie("remember-me");
        assertNotNull(cookie, "ticking the box should set the remember-me cookie");
        assertEquals(365 * 24 * 60 * 60, cookie.getMaxAge());
    }

    @Test
    void aRememberMeCookieFromACodeLoginSignsTheReaderBackIn() throws Exception {
        String code = recoveryCodes.createOrRotate(userId);
        Cookie cookie = mockMvc.perform(attempt(code).param("remember-me", "true"))
                .andReturn().getResponse().getCookie("remember-me");
        assertNotNull(cookie);

        mockMvc.perform(get("/settings/account").cookie(cookie))
                .andExpect(status().isOk());
    }

    @Test
    void withoutACsrfTokenTheAttemptIsForbidden() throws Exception {
        String code = recoveryCodes.createOrRotate(userId);

        mockMvc.perform(post("/login/recovery").param("code", code)
                        .with(request -> {
                            request.setRemoteAddr(clientIp);
                            return request;
                        }))
                .andExpect(status().isForbidden());
    }

    @Test
    void theEleventhAttemptInTheWindowIsThrottled() throws Exception {
        String ip = "203.0.113.200";
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(attempt("ABCD-EFGH-JKLM", ip))
                    .andExpect(redirectedUrl("/login?recoveryError"));
        }

        mockMvc.perform(attempt("ABCD-EFGH-JKLM", ip))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void aRotatedCodeNoLongerWorksButTheNewOneDoes() throws Exception {
        String old = recoveryCodes.createOrRotate(userId);
        String current = recoveryCodes.createOrRotate(userId);

        mockMvc.perform(attempt(old))
                .andExpect(redirectedUrl("/login?recoveryError"));
        MvcResult result = mockMvc.perform(attempt(current))
                .andExpect(redirectedUrl("/"))
                .andReturn();
        assertSignedIn(result);
    }

    @Test
    void theLoginPageOffersTheRecoveryForm() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sign in with a recovery code")))
                .andExpect(content().string(containsString("action=\"/login/recovery\"")))
                .andExpect(content().string(containsString("name=\"code\"")))
                .andExpect(content().string(containsString("autocapitalize=\"characters\"")))
                .andExpect(content().string(containsString("placeholder=\"ABCD-EFGH-JKLM\"")))
                .andExpect(content().string(containsString("Log in with recovery code")))
                .andExpect(content().string(not(containsString("Invalid recovery code."))));

        mockMvc.perform(get("/login").param("recoveryError", ""))
                .andExpect(content().string(containsString("Invalid recovery code.")));
    }
}
