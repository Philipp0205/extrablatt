package com.kindlerss.web;

import com.kindlerss.domain.CancellationRequest;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.security.RateLimiter;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.service.EntitlementService;
import com.kindlerss.service.UserService;
import com.kindlerss.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * § 312k BGB in test form. The point of every case here is that a consumer can end
 * a contract without being asked to sign in first, and gets the declaration back
 * with the time it arrived.
 */
@WebMvcTest(controllers = CancellationController.class)
@Import({com.kindlerss.config.SecurityConfig.class, GlobalExceptionHandler.class,
        RateLimiter.class, RateLimitingFilter.class})
@TestPropertySource(properties = {
        "app.mail-from=from@example.com",
        "app.remember-me-key=test-remember-key",
        "app.billing.enabled=true"
})
class CancellationControllerTest {

    @Autowired
    MockMvc mockMvc;

    // ChangelogAdvice is picked up by every MVC slice, so the bean it needs has to
    // exist here even though this controller never renders a changelog.
    @MockitoBean
    UserService userService;

    @MockitoBean
    SubscriptionService subscriptionService;

    @MockitoBean
    EntitlementService entitlementService;

    @MockitoBean
    CurrentUser currentUser;

    @MockitoBean
    UserDetailsService userDetailsService;

    /**
     * The requirement a provider's customer portal cannot meet: the OLG Köln held the
     * cancellation path may not sit behind account credentials.
     */
    @Test
    void theCancellationPageIsReachableWithoutSigningIn() throws Exception {
        when(currentUser.details()).thenReturn(Optional.empty());

        mockMvc.perform(get("/cancel"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Cancel your contract")))
                .andExpect(content().string(containsString("do not need to be signed in")));
    }

    /** The confirm button has to declare the cancellation, not an intention to make one. */
    @Test
    void theConfirmationButtonSaysItCancelsNow() throws Exception {
        when(currentUser.details()).thenReturn(Optional.empty());

        mockMvc.perform(get("/cancel"))
                .andExpect(content().string(containsString("Cancel now")));
    }

    @Test
    void anAnonymousDeclarationIsAcceptedAndReadBackWithItsTime() throws Exception {
        when(currentUser.details()).thenReturn(Optional.empty());
        when(subscriptionService.cancel(any(), isNull())).thenReturn(outcome(true));

        mockMvc.perform(post("/cancel").with(csrf())
                        .param("email", "reader@example.com")
                        .param("kind", "ordinary"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Your cancellation is confirmed")))
                .andExpect(content().string(containsString("reader@example.com")))
                .andExpect(content().string(containsString("Received")));
    }

    @Test
    void theDeclarationIsPassedOnExactlyAsItWasGiven() throws Exception {
        when(currentUser.details()).thenReturn(Optional.empty());
        when(subscriptionService.cancel(any(), isNull())).thenReturn(outcome(true));

        mockMvc.perform(post("/cancel").with(csrf())
                        .param("email", "  Reader@Example.COM ")
                        .param("name", "A Reader")
                        .param("contractRef", "ref-1")
                        .param("kind", "immediate")
                        .param("requestedEnd", "2027-01-31")
                        .param("reason", "too many newsletters"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<SubscriptionService.Declaration> captor =
                org.mockito.ArgumentCaptor.forClass(SubscriptionService.Declaration.class);
        verify(subscriptionService).cancel(captor.capture(), isNull());
        SubscriptionService.Declaration declaration = captor.getValue();
        // Normalised, because the address is what finds the account.
        org.junit.jupiter.api.Assertions.assertEquals("reader@example.com", declaration.email());
        org.junit.jupiter.api.Assertions.assertEquals("A Reader", declaration.name());
        org.junit.jupiter.api.Assertions.assertEquals("ref-1", declaration.contractRef());
        org.junit.jupiter.api.Assertions.assertEquals(CancellationRequest.Kind.IMMEDIATE,
                declaration.kind());
        org.junit.jupiter.api.Assertions.assertEquals(LocalDate.of(2027, 1, 31),
                declaration.requestedEnd());
    }

    @Test
    void aMissingAddressGoesBackToTheFormRatherThanFailing() throws Exception {
        when(currentUser.details()).thenReturn(Optional.empty());

        mockMvc.perform(post("/cancel").with(csrf()).param("email", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("e-mail address of the account")));
    }

    /** Cancelling must never look like it deletes what the reader collected. */
    @Test
    void theConfirmationSaysNothingIsDeleted() throws Exception {
        when(currentUser.details()).thenReturn(Optional.empty());
        when(subscriptionService.cancel(any(), isNull())).thenReturn(outcome(true));

        mockMvc.perform(post("/cancel").with(csrf()).param("email", "reader@example.com"))
                .andExpect(content().string(containsString("Nothing you have saved is deleted")));
    }

    private static SubscriptionService.Outcome outcome(boolean applied) {
        CancellationRequest request = new CancellationRequest(1L, 42L, "reader@example.com",
                null, null, CancellationRequest.Kind.ORDINARY, null, null,
                Instant.now(), Instant.now());
        return new SubscriptionService.Outcome(request, Instant.now(), applied);
    }
}
