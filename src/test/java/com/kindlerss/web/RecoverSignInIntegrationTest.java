package com.kindlerss.web;

import com.kindlerss.repository.UserRepository;
import com.kindlerss.service.AccountMailService;
import com.kindlerss.service.KindleMailService;
import com.kindlerss.service.RecoveryCodeService;
import com.kindlerss.service.RecoverySignInService;
import com.kindlerss.service.SafeHttpClient;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.FlashMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The one-time e-mail sign-in link ("Lost your recovery code?"), against the whole application. */
@SpringBootTest
@AutoConfigureMockMvc
class RecoverSignInIntegrationTest {

    private static final String ACK = "If that address has an account with a confirmed e-mail, "
            + "we sent a sign-in link. It is valid for 20 minutes.";
    private static final String PASSWORD = "correct horse battery";
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
        registry.add("app.remember-me-key", () -> "recover-sign-in-it-remember-key");
    }

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RecoveryCodeService recoveryCodes;
    @Autowired RecoverySignInService signInService;
    @Autowired PasswordEncoder passwordEncoder;

    @MockitoBean AccountMailService mail;
    @MockitoBean SafeHttpClient httpClient;
    @MockitoBean KindleMailService kindleMailService;

    private long userId;
    private String email;
    private String clientIp;

    @BeforeEach
    void seedAccount() {
        reset(mail);
        email = "rsi-" + UUID.randomUUID() + "@example.com";
        userId = users.insert(email, passwordEncoder.encode(PASSWORD)).id();
        users.markEmailVerified(userId);
        // Each test is its own client: the throttles are keyed by address.
        clientIp = "198.51.101." + NEXT_CLIENT.getAndIncrement();
    }

    // ---- helpers -------------------------------------------------------------------

    private MockHttpServletRequestBuilder asClient(MockHttpServletRequestBuilder builder) {
        return builder.with(request -> {
            request.setRemoteAddr(clientIp);
            return request;
        });
    }

    private MockHttpServletRequestBuilder requestLinkFor(String address) {
        return asClient(post("/recover/request").servletPath("/recover/request").with(csrf())
                .param("email", address));
    }

    private MockHttpServletRequestBuilder consume(String token) {
        return asClient(post("/recover").servletPath("/recover").with(csrf()).param("token", token));
    }

    /** Requests a link for the seeded account and returns the plain token handed to the mailer. */
    private String requestToken() throws Exception {
        return requestTokenFor(email);
    }

    private String requestTokenFor(String address) throws Exception {
        clearInvocations(mail);
        mockMvc.perform(requestLinkFor(address)).andExpect(redirectedUrl("/check-email"));
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mail).sendSignInLink(eq(address), token.capture());
        return token.getValue();
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private int signInTokens(long forUser) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM email_tokens WHERE user_id = ? AND purpose = 'SIGN_IN'",
                Integer.class, forUser);
    }

    private String recoveryHash(long forUser) {
        return jdbc.queryForObject("SELECT recovery_hash FROM users WHERE id = ?", String.class, forUser);
    }

    private void assertInvalid(MvcResult result) {
        assertEquals("/recover?invalid", result.getResponse().getRedirectedUrl());
        assertNull(result.getFlashMap().get("newRecoveryCode"));
    }

    private void assertSignedIn(MockHttpSession session) throws Exception {
        mockMvc.perform(get("/settings/account").session(session)).andExpect(status().isOk());
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

    // ---- requesting a link ---------------------------------------------------------

    @Test
    void theAcknowledgementIsTheSameWhateverTheAddress() throws Exception {
        String unverified = "rsi-" + UUID.randomUUID() + "@example.com";
        users.insert(unverified, "hash");
        String disabled = "rsi-" + UUID.randomUUID() + "@example.com";
        long disabledId = users.insert(disabled, "hash").id();
        users.markEmailVerified(disabledId);
        jdbc.update("UPDATE users SET disabled_at = NOW() WHERE id = ?", disabledId);

        List<MvcResult> results = new ArrayList<>();
        for (String address : new String[] {email, "nobody-" + UUID.randomUUID() + "@example.com",
                unverified, disabled, "not an address", ""}) {
            results.add(mockMvc.perform(requestLinkFor(address))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/check-email"))
                    .andReturn());
        }

        FlashMap first = results.get(0).getFlashMap();
        assertEquals(ACK, first.get("instruction"));
        assertEquals("/recover/request", first.get("retryPath"));
        assertFalse(first.containsKey("sentTo"), "the address is not echoed back");
        for (MvcResult result : results) {
            assertEquals(first, result.getFlashMap());
        }
    }

    @Test
    void onlyAnEnabledConfirmedAccountGetsAMail() throws Exception {
        String unverified = "rsi-" + UUID.randomUUID() + "@example.com";
        long unverifiedId = users.insert(unverified, "hash").id();
        String disabled = "rsi-" + UUID.randomUUID() + "@example.com";
        long disabledId = users.insert(disabled, "hash").id();
        users.markEmailVerified(disabledId);
        jdbc.update("UPDATE users SET disabled_at = NOW() WHERE id = ?", disabledId);

        for (String address : new String[] {unverified, disabled, "nobody@example.com", "bad"}) {
            mockMvc.perform(requestLinkFor(address)).andExpect(redirectedUrl("/check-email"));
        }
        verify(mail, never()).sendSignInLink(anyString(), anyString());
        assertEquals(0, signInTokens(unverifiedId));
        assertEquals(0, signInTokens(disabledId));

        mockMvc.perform(requestLinkFor("  " + email.toUpperCase() + " "))
                .andExpect(redirectedUrl("/check-email"));
        verify(mail).sendSignInLink(eq(email), anyString());
    }

    @Test
    void aSendFailureGivesTheSameAcknowledgement() throws Exception {
        doThrow(new IllegalStateException("smtp down")).when(mail).sendSignInLink(anyString(), anyString());

        MvcResult result = mockMvc.perform(requestLinkFor(email))
                .andExpect(redirectedUrl("/check-email"))
                .andReturn();

        assertEquals(ACK, result.getFlashMap().get("instruction"));
        assertFalse(result.getFlashMap().containsKey("sentTo"));
    }

    @Test
    void onlyTheHashOfTheTokenIsStored() throws Exception {
        String token = requestToken();

        assertEquals(43, token.length());
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"), "32 bytes, base64url, no padding");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM email_tokens WHERE token = ?",
                Integer.class, token));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM email_tokens WHERE token = ? AND purpose = 'SIGN_IN' AND user_id = ?",
                Integer.class, sha256(token), userId));
        long minutes = jdbc.queryForObject("""
                SELECT EXTRACT(EPOCH FROM (expires_at - created_at)) / 60 FROM email_tokens WHERE token = ?
                """, Long.class, sha256(token));
        assertEquals(20, minutes);
    }

    @Test
    void aNewRequestInvalidatesTheOlderLink() throws Exception {
        String older = requestToken();
        String newer = requestToken();

        assertEquals(1, signInTokens(userId));
        assertInvalid(mockMvc.perform(consume(older)).andReturn());
        MvcResult ok = mockMvc.perform(consume(newer)).andReturn();
        assertEquals("/settings/account", ok.getResponse().getRedirectedUrl());
    }

    @Test
    void theRequestDoesNotTouchOtherTokenPurposes() throws Exception {
        jdbc.update("INSERT INTO email_tokens (token, user_id, purpose, expires_at) "
                + "VALUES ('verify-tok', ?, 'VERIFY', NOW() + interval '1 day'), "
                + "('reset-tok', ?, 'RESET', NOW() + interval '1 day')", userId, userId);

        String token = requestToken();
        mockMvc.perform(consume(token)).andExpect(redirectedUrl("/settings/account"));

        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM email_tokens WHERE user_id = ? AND purpose IN ('VERIFY', 'RESET')",
                Integer.class, userId));
    }

    @Test
    void thePerAddressLimitGivesTheSameAcknowledgementButNoMoreMail() throws Exception {
        List<MvcResult> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(mockMvc.perform(requestLinkFor(email)).andReturn());
        }

        verify(mail, times(3)).sendSignInLink(eq(email), anyString());
        for (MvcResult result : results) {
            assertEquals("/check-email", result.getResponse().getRedirectedUrl());
            assertEquals(results.get(0).getFlashMap(), result.getFlashMap());
        }
    }

    @Test
    void theRequestNeedsACsrfToken() throws Exception {
        mockMvc.perform(asClient(post("/recover/request").param("email", email)))
                .andExpect(status().isForbidden());
        verify(mail, never()).sendSignInLink(anyString(), anyString());
    }

    @Test
    void theRequestIsThrottledPerClientAddress() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(requestLinkFor("nobody-" + i + "@example.com"))
                    .andExpect(redirectedUrl("/check-email"));
        }
        mockMvc.perform(requestLinkFor("nobody-last@example.com"))
                .andExpect(status().isTooManyRequests());
    }

    // ---- the pages -----------------------------------------------------------------

    @Test
    void theLoginPageLinksToTheRequestPageAndBothPagesAreOpen() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lost your recovery code?")))
                .andExpect(content().string(containsString("href=\"/recover/request\"")))
                .andExpect(content().string(containsString("Forgot password?")));

        mockMvc.perform(get("/recover/request"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/recover/request\"")))
                .andExpect(content().string(containsString("name=\"email\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void theLinkPageHasTheFormTheScriptAndNoMessageByDefault() throws Exception {
        mockMvc.perform(get("/recover"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/recover\"")))
                .andExpect(content().string(containsString("name=\"token\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("Sign in</button>")))
                .andExpect(content().string(containsString("/js/recover")))
                .andExpect(content().string(not(containsString("invalid or has expired"))));

        mockMvc.perform(get("/recover").param("invalid", ""))
                .andExpect(content().string(containsString("That sign-in link is invalid or has expired.")))
                .andExpect(content().string(containsString("href=\"/recover/request\"")));
    }

    @Test
    void theScriptIsServedUnderAHashedName() throws Exception {
        String page = mockMvc.perform(get("/recover")).andReturn().getResponse().getContentAsString();
        java.util.regex.Matcher script = java.util.regex.Pattern
                .compile("src=\"(/js/recover-[0-9a-f]{32}\\.js)\"").matcher(page);
        assertTrue(script.find(), "recover.js is linked under its content hash");

        mockMvc.perform(get(script.group(1)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("replaceState")));
    }

    @Test
    void openingThePageNeverSpendsTheToken() throws Exception {
        String token = requestToken();

        mockMvc.perform(get("/recover").param("token", token).param("t", token)).andExpect(status().isOk());
        mockMvc.perform(get("/recover")).andExpect(status().isOk());

        assertEquals(1, signInTokens(userId));
        MvcResult result = mockMvc.perform(consume(token)).andReturn();
        assertEquals("/settings/account", result.getResponse().getRedirectedUrl());
    }

    // ---- using a link --------------------------------------------------------------

    @Test
    void theLinkSignsInWithANewSessionAndShowsTheNewCodeOnce() throws Exception {
        String oldCode = recoveryCodes.createOrRotate(userId);
        String oldHash = recoveryHash(userId);
        String token = requestToken();
        MockHttpSession before = new MockHttpSession();
        before.setAttribute("anonymous", "visit");
        String sessionIdBefore = before.getId();

        MvcResult result = mockMvc.perform(consume(token).session(before))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings/account"))
                .andReturn();

        String newCode = (String) result.getFlashMap().get("newRecoveryCode");
        assertNotNull(newCode);
        assertTrue(newCode.matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}"));
        assertNotEquals(oldCode, newCode);
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        assertNotEquals(sessionIdBefore, session.getId(), "a new session id after signing in");
        assertNull(result.getResponse().getCookie("remember-me"), "no remember-me cookie");
        assertSignedIn(session);
        assertNotNull(jdbc.queryForObject("SELECT last_login_at FROM users WHERE id = ?",
                java.sql.Timestamp.class, userId));
        assertNotEquals(oldHash, recoveryHash(userId));

        // The account page shows the new code once, then never again.
        mockMvc.perform(get("/settings/account").session(session).flashAttrs(result.getFlashMap()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(newCode)));
        mockMvc.perform(get("/settings/account").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(newCode))));
    }

    @Test
    void theOldRecoveryCodeStopsWorkingAndTheNewOneLogsIn() throws Exception {
        String oldCode = recoveryCodes.createOrRotate(userId);
        String token = requestToken();
        MvcResult consumed = mockMvc.perform(consume(token)).andReturn();
        String newCode = (String) consumed.getFlashMap().get("newRecoveryCode");

        MvcResult old = mockMvc.perform(asClient(post("/login/recovery").servletPath("/login/recovery")
                        .with(csrf()).param("code", oldCode)))
                .andExpect(redirectedUrl("/login?recoveryError"))
                .andReturn();
        assertNotSignedIn(old);

        MvcResult fresh = mockMvc.perform(asClient(post("/login/recovery").servletPath("/login/recovery")
                        .with(csrf()).param("code", newCode)))
                .andExpect(redirectedUrl("/"))
                .andReturn();
        assertSignedIn((MockHttpSession) fresh.getRequest().getSession(false));
    }

    @Test
    void thePasswordStillWorksAfterwards() throws Exception {
        String token = requestToken();
        mockMvc.perform(consume(token)).andExpect(redirectedUrl("/settings/account"));

        MvcResult login = mockMvc.perform(asClient(post("/login").servletPath("/login").with(csrf())
                        .param("username", email).param("password", PASSWORD)))
                .andExpect(redirectedUrl("/"))
                .andReturn();
        assertSignedIn((MockHttpSession) login.getRequest().getSession(false));
    }

    @Test
    void aLinkWorksOnlyOnce() throws Exception {
        String token = requestToken();

        mockMvc.perform(consume(token)).andExpect(redirectedUrl("/settings/account"));
        String hashAfterFirst = recoveryHash(userId);

        MvcResult second = mockMvc.perform(consume(token)).andReturn();
        assertInvalid(second);
        assertNotSignedIn(second);
        assertEquals(hashAfterFirst, recoveryHash(userId), "a replayed link rotates nothing");
    }

    @Test
    void anExpiredLinkFails() throws Exception {
        recoveryCodes.createOrRotate(userId);
        String hashBefore = recoveryHash(userId);
        String token = requestToken();
        jdbc.update("UPDATE email_tokens SET expires_at = NOW() - interval '1 second' WHERE token = ?",
                sha256(token));

        MvcResult result = mockMvc.perform(consume(token)).andReturn();

        assertInvalid(result);
        assertNotSignedIn(result);
        assertEquals(hashBefore, recoveryHash(userId));
    }

    @Test
    void anUnknownOrMalformedTokenFailsTheSameWay() throws Exception {
        requestToken();

        for (String bad : new String[] {"", "   ", "nonsense", "A".repeat(43), "A".repeat(500),
                "../../etc/passwd", sha256("x")}) {
            MvcResult result = mockMvc.perform(consume(bad)).andReturn();
            assertInvalid(result);
            assertNotSignedIn(result);
        }
        mockMvc.perform(asClient(post("/recover").servletPath("/recover").with(csrf())))
                .andExpect(redirectedUrl("/recover?invalid"));
        // The stored hash is not itself a way in.
        String stored = jdbc.queryForObject("SELECT token FROM email_tokens WHERE user_id = ?",
                String.class, userId);
        assertInvalid(mockMvc.perform(consume(stored)).andReturn());
        assertEquals(1, signInTokens(userId));
    }

    @Test
    void aSuccessfulUseDeletesAllOfTheUsersSignInTokens() throws Exception {
        String token = requestToken();
        jdbc.update("INSERT INTO email_tokens (token, user_id, purpose, expires_at) "
                + "VALUES ('stray-1', ?, 'SIGN_IN', NOW() + interval '5 minutes'), "
                + "('stray-2', ?, 'SIGN_IN', NOW() - interval '5 minutes')", userId, userId);
        assertEquals(3, signInTokens(userId));

        mockMvc.perform(consume(token)).andExpect(redirectedUrl("/settings/account"));

        assertEquals(0, signInTokens(userId));
    }

    @Test
    void aSwitchedOffAccountCannotUseItsLink() throws Exception {
        recoveryCodes.createOrRotate(userId);
        String hashBefore = recoveryHash(userId);
        String token = requestToken();
        jdbc.update("UPDATE users SET disabled_at = NOW() WHERE id = ?", userId);

        MvcResult result = mockMvc.perform(consume(token)).andReturn();

        assertInvalid(result);
        assertNotSignedIn(result);
        assertEquals(hashBefore, recoveryHash(userId));
    }

    @Test
    void theUseNeedsACsrfToken() throws Exception {
        String token = requestToken();

        mockMvc.perform(asClient(post("/recover").param("token", token)))
                .andExpect(status().isForbidden());

        assertEquals(1, signInTokens(userId));
    }

    @Test
    void theUseIsThrottledPerClientAddress() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(consume("nonsense")).andExpect(redirectedUrl("/recover?invalid"));
        }
        mockMvc.perform(consume("nonsense")).andExpect(status().isTooManyRequests());
    }

    @Test
    void twoParallelUsesCannotBothSucceed() throws Exception {
        String token = requestToken();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            outcomes.add(pool.submit(() -> {
                go.await();
                return signInService.consume(token).isPresent();
            }));
        }
        go.countDown();
        int winners = 0;
        for (Future<Boolean> outcome : outcomes) {
            if (outcome.get()) {
                winners++;
            }
        }
        pool.shutdown();

        assertEquals(1, winners);
        assertEquals(0, signInTokens(userId));
    }
}
