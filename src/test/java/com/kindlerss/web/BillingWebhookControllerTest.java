package com.kindlerss.web;

import com.kindlerss.security.CurrentUser;
import com.kindlerss.security.RateLimiter;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.service.BillingWebhookService;
import com.kindlerss.service.UserService;
import com.kindlerss.service.EntitlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The webhook has to work the way the newsletter webhook does — no session, no CSRF
 * token — while being the one endpoint that can hand out a paid plan. These tests
 * are about the plumbing around it; {@link com.kindlerss.service.BillingWebhookServiceTest}
 * covers what it makes of a payload.
 */
@WebMvcTest(controllers = BillingWebhookController.class)
@Import({com.kindlerss.config.SecurityConfig.class, GlobalExceptionHandler.class,
        RateLimiter.class, RateLimitingFilter.class})
@TestPropertySource(properties = {
        "app.remember-me-key=test-remember-key",
        "app.billing.enabled=true",
        "app.billing.provider=stripe",
        "app.billing.webhook-secret=whsec_test"
})
class BillingWebhookControllerTest {

    @Autowired
    MockMvc mockMvc;

    // ChangelogAdvice is picked up by every MVC slice, so the bean it needs has to
    // exist here even though this controller never renders a changelog.
    @MockitoBean
    UserService userService;

    @MockitoBean
    BillingWebhookService webhookService;

    @MockitoBean
    EntitlementService entitlementService;

    @MockitoBean
    CurrentUser currentUser;

    @MockitoBean
    UserDetailsService userDetailsService;

    /** A payment provider has neither a login nor a CSRF token to offer. */
    @Test
    void theWebhookAnswersWithoutASessionOrACsrfToken() throws Exception {
        when(webhookService.configured()).thenReturn(true);
        when(webhookService.handle(any(), any())).thenReturn(BillingWebhookService.Result.ACCEPTED);

        mockMvc.perform(post("/webhooks/billing")
                        .header("Stripe-Signature", "t=1,v1=abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"evt_1\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aRejectedSignatureIsABadRequest() throws Exception {
        when(webhookService.configured()).thenReturn(true);
        when(webhookService.handle(any(), any())).thenReturn(BillingWebhookService.Result.REJECTED);

        mockMvc.perform(post("/webhooks/billing")
                        .header("Stripe-Signature", "t=1,v1=wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"evt_1\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * An event that cannot be applied still gets a 200: retrying will not change the
     * outcome, and the reason is on the stored row instead.
     */
    @Test
    void anUnapplicableEventIsNotTurnedIntoARetryLoop() throws Exception {
        when(webhookService.configured()).thenReturn(true);
        when(webhookService.handle(any(), any())).thenReturn(BillingWebhookService.Result.FAILED);

        mockMvc.perform(post("/webhooks/billing")
                        .header("Stripe-Signature", "t=1,v1=abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"evt_1\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void withNoProviderConfiguredTheEndpointIsNotThere() throws Exception {
        when(webhookService.configured()).thenReturn(false);

        mockMvc.perform(post("/webhooks/billing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"evt_1\"}"))
                .andExpect(status().isNotFound());
    }

    /** Paddle signs with its own header, and the controller must pass it through. */
    @Test
    void aPaddleSignatureHeaderIsAccepted() throws Exception {
        when(webhookService.configured()).thenReturn(true);
        when(webhookService.handle(eq("ts=1;h1=abc"), any()))
                .thenReturn(BillingWebhookService.Result.ACCEPTED);

        mockMvc.perform(post("/webhooks/billing")
                        .header("Paddle-Signature", "ts=1;h1=abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_id\":\"evt_1\"}"))
                .andExpect(status().isOk());
    }
}
