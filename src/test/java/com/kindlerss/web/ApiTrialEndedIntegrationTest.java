package com.kindlerss.web;

import com.kindlerss.domain.BillingInterval;
import com.kindlerss.domain.Plan;
import com.kindlerss.domain.Subscription;
import com.kindlerss.domain.SubscriptionStatus;
import com.kindlerss.repository.SubscriptionRepository;
import com.kindlerss.repository.UserRepository;
import com.kindlerss.service.ApiTokenService;
import com.kindlerss.service.SafeHttpClient;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The preview client's "Your trial has ended" notice with billing switched on: the account
 * says {@code trialEndedPrompt} after the complimentary week ends, until the reader closes the
 * notice in that session. Real login, real filters, real database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiTrialEndedIntegrationTest {

    private static final String PASSWORD = "trial-ended-api-password-1";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";
    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(1);

    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        registry.add("spring.datasource.url",
                () -> "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("app.mail-from", () -> "noreply@example.com");
        registry.add("app.remember-me-key", () -> "api-trial-ended-it-remember-key");
        registry.add("app.billing.enabled", () -> "true");
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired ApiTokenService tokenService;
    @Autowired PasswordEncoder passwordEncoder;

    @MockitoBean SafeHttpClient httpClient;

    private String email;
    private long userId;
    private String clientIp;

    @BeforeEach
    void seedAccount() {
        email = "te-" + UUID.randomUUID() + "@example.com";
        userId = users.insert(email, passwordEncoder.encode(PASSWORD)).id();
        users.markEmailVerified(userId);
        // The login form is rate limited per client address: each test is its own client.
        clientIp = "192.0.2." + NEXT_CLIENT.getAndIncrement();
    }

    private void endTrial() {
        subscriptions.save(new Subscription(userId, Plan.SUPPORTER, SubscriptionStatus.TRIALING,
                BillingInterval.MONTHLY, null, null, null,
                Instant.now().minus(2, ChronoUnit.DAYS), false, null));
    }

    private void runningTrial() {
        subscriptions.save(new Subscription(userId, Plan.SUPPORTER, SubscriptionStatus.TRIALING,
                BillingInterval.MONTHLY, null, null, null,
                Instant.now().plus(3, ChronoUnit.DAYS), false, null));
    }

    private MockHttpSession signIn() throws Exception {
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

    @Test
    void theAccountSaysBillingIsOnSoTheClientShowsTheBillingLinks() throws Exception {
        mockMvc.perform(get("/api/v1/account").session(signIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billingEnabled").value(true));
    }

    @Test
    void anEndedTrialAsksForTheNoticeUntilItIsClosedInThatSession() throws Exception {
        endTrial();
        MockHttpSession session = signIn();
        String token = xsrfTokenFor(session);

        mockMvc.perform(get("/api/v1/account").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trialEndedPrompt").value(true))
                .andExpect(jsonPath("$.entitlement.onTrial").value(false));

        mockMvc.perform(withCsrf(post("/api/v1/account/trial-ended/ack"), session, token))
                .andExpect(status().isNoContent());
        assertEquals(Boolean.TRUE, session.getAttribute(AccountAdvice.TRIAL_ENDED_ACK));

        mockMvc.perform(get("/api/v1/account").session(session))
                .andExpect(jsonPath("$.trialEndedPrompt").value(false));

        // A new sign-in is a new session, so the notice comes back, as on the website.
        mockMvc.perform(get("/api/v1/account").session(signIn()))
                .andExpect(jsonPath("$.trialEndedPrompt").value(true));
    }

    @Test
    void theAckNeedsTheCsrfHeaderAndRecordsNothingWithout() throws Exception {
        endTrial();
        MockHttpSession session = signIn();
        xsrfTokenFor(session);

        mockMvc.perform(post("/api/v1/account/trial-ended/ack").session(session))
                .andExpect(status().isForbidden());
        assertNull(session.getAttribute(AccountAdvice.TRIAL_ENDED_ACK));
        mockMvc.perform(get("/api/v1/account").session(session))
                .andExpect(jsonPath("$.trialEndedPrompt").value(true));
    }

    @Test
    void aTokenNeverSeesThePromptAndCannotAcknowledgeIt() throws Exception {
        endTrial();
        String bearer = "Bearer " + tokenService.create(userId, "A").secret();

        mockMvc.perform(get("/api/v1/account").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trialEndedPrompt").value(false));
        mockMvc.perform(post("/api/v1/account/trial-ended/ack").header("Authorization", bearer))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRunningTrialAndAnAccountWithoutASubscriptionRowGetNoPrompt() throws Exception {
        runningTrial();
        mockMvc.perform(get("/api/v1/account").session(signIn()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trialEndedPrompt").value(false))
                .andExpect(jsonPath("$.entitlement.onTrial").value(true));

        // A second account, with no row at all, is grandfathered and never asked.
        String otherEmail = "te-" + UUID.randomUUID() + "@example.com";
        long other = users.insert(otherEmail, passwordEncoder.encode(PASSWORD)).id();
        users.markEmailVerified(other);
        email = otherEmail;
        userId = other;
        mockMvc.perform(get("/api/v1/account").session(signIn()))
                .andExpect(jsonPath("$.trialEndedPrompt").value(false));
    }
}
