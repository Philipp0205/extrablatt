package com.kindlerss.web;

import com.kindlerss.domain.BillingInterval;
import com.kindlerss.domain.Entitlement;
import com.kindlerss.domain.Plan;
import com.kindlerss.domain.Subscription;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.security.RateLimiter;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.service.EntitlementService;
import com.kindlerss.service.UserService;
import com.kindlerss.service.SubscriptionService;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The order page carries § 312j BGB, and § 312j Abs. 4 makes a mistake there void
 * the whole contract, so the things this test checks are not cosmetic: the price and
 * terms have to be on the page, the button has to say that ordering costs money, and
 * the two withdrawal statements have to be ticked by hand.
 */
@WebMvcTest(controllers = BillingController.class)
@Import({com.kindlerss.config.SecurityConfig.class, GlobalExceptionHandler.class,
        RateLimiter.class, RateLimitingFilter.class})
@TestPropertySource(properties = {
        "app.mail-from=from@example.com",
        "app.remember-me-key=test-remember-key",
        "app.billing.enabled=true",
        "app.billing.provider=stripe",
        "app.billing.monthly-checkout-url=https://pay.example.com/monthly",
        "app.billing.yearly-checkout-url=https://pay.example.com/yearly"
})
class BillingControllerTest {

    private static final long UID = 42L;

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

    @BeforeEach
    void signIn() {
        com.kindlerss.domain.AppUser user = new com.kindlerss.domain.AppUser(UID,
                "reader@example.com", "hash", "reader@kindle.com", Instant.now(), null,
                Instant.now(), Instant.now());
        when(currentUser.requireId()).thenReturn(UID);
        when(currentUser.details()).thenReturn(Optional.of(new AppUserDetails(user)));
        when(entitlementService.forUser(anyLong()))
                .thenReturn(new Entitlement(Plan.FREE, 10, 10, 15, false));
        when(subscriptionService.forUser(anyLong())).thenReturn(Subscription.free(UID));
    }

    @Test
    @WithMockUser
    void theYearlyOrderPageQuotesThirtyFiveEightyEightAndTwoNinetyNineAMonth() throws Exception {
        mockMvc.perform(get("/billing/order").param("interval", "yearly"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\u20ac35.88")))
                .andExpect(content().string(containsString("\u20ac2.99")))
                .andExpect(content().string(containsString("including VAT")));
    }

    @Test
    @WithMockUser
    void theMonthlyOrderPageQuotesThreeNinetyNine() throws Exception {
        mockMvc.perform(get("/billing/order").param("interval", "monthly"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\u20ac3.99")));
    }

    @Test
    @WithMockUser
    void theOrderPageStatesTheTermAndHowToEndIt() throws Exception {
        mockMvc.perform(get("/billing/order").param("interval", "yearly"))
                .andExpect(content().string(containsString("renewing automatically")))
                .andExpect(content().string(containsString("cancellation page")))
                .andExpect(content().string(containsString("no minimum term")));
    }

    /** § 312j Abs. 3 BGB: "Continue" or "Subscribe" would not do. */
    @Test
    @WithMockUser
    void theOrderButtonSaysThatOrderingCostsMoney() throws Exception {
        mockMvc.perform(get("/billing/order").param("interval", "yearly"))
                .andExpect(content().string(containsString("Order with obligation to pay")));
    }

    /** Neither box may arrive already ticked; § 356 Abs. 4 BGB needs a real act. */
    @Test
    @WithMockUser
    void theWithdrawalBoxesStartEmpty() throws Exception {
        String html = mockMvc.perform(get("/billing/order").param("interval", "yearly"))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("id=\"startNow\""));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("id=\"withdrawalAcknowledged\""));
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("checked"));
    }

    @Test
    @WithMockUser
    void orderingWithoutBothConfirmationsGoesBackToTheForm() throws Exception {
        mockMvc.perform(post("/billing/order").with(csrf())
                        .param("interval", "yearly")
                        .param("startNow", "yes"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/billing/order?interval=yearly"));

        verify(subscriptionService, never()).placeOrder(anyLong(), eq(BillingInterval.YEARLY));
    }

    @Test
    @WithMockUser
    void aCompleteOrderGoesToTheProvidersCheckout() throws Exception {
        when(subscriptionService.placeOrder(UID, BillingInterval.YEARLY))
                .thenReturn("https://pay.example.com/yearly?client_reference_id=42");

        mockMvc.perform(post("/billing/order").with(csrf())
                        .param("interval", "yearly")
                        .param("startNow", "yes")
                        .param("withdrawalAcknowledged", "yes"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("https://pay.example.com/yearly?client_reference_id=42"));
    }

    /**
     * The page a reader lands on after paying must not be what grants access, so it
     * does not claim to have.
     */
    @Test
    @WithMockUser
    void theReturnPageDoesNotClaimTheSubscriptionIsActive() throws Exception {
        mockMvc.perform(get("/billing/return"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("being confirmed")));
    }

    @Test
    void theOrderPageNeedsAnAccount() throws Exception {
        mockMvc.perform(get("/billing/order").param("interval", "yearly"))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * The landing page's "Choose yearly" button links straight here, so being bounced
     * to the login form has to be a detour rather than a dead end: the destination is
     * remembered and returned to. Without this the reader lands on the home page and
     * has to find the plan again, which is where people give up.
     */
    @Test
    void anUnauthenticatedVisitorIsSentToLoginAndTheChosenPlanIsRemembered() throws Exception {
        MvcResult bounced = mockMvc.perform(get("/billing/order").param("interval", "monthly"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"))
                .andReturn();

        SavedRequest saved = new HttpSessionRequestCache()
                .getRequest(bounced.getRequest(), bounced.getResponse());
        assertNotNull(saved, "the login redirect has to remember where the reader was going");
        assertEquals("/billing/order", URI.create(saved.getRedirectUrl()).getPath());
        // The interval matters as much as the path: someone who clicked "Choose
        // monthly" must not come back to the yearly order page.
        assertArrayEquals(new String[]{"monthly"}, saved.getParameterMap().get("interval"));
    }
}
