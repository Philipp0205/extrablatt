package com.kindlerss.web;

import com.kindlerss.domain.AppUser;
import com.kindlerss.domain.Entitlement;
import com.kindlerss.domain.Plan;
import com.kindlerss.domain.Subscription;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.security.RateLimiter;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.service.ArticleService;
import com.kindlerss.service.DataExportService;
import com.kindlerss.service.EntitlementService;
import com.kindlerss.service.RetentionService;
import com.kindlerss.service.SubscriptionService;
import com.kindlerss.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A separate context from {@link SettingsControllerTest} because billing has to be
 * on for the subscription page to render at all.
 */
@WebMvcTest(controllers = SettingsController.class)
@Import({com.kindlerss.config.SecurityConfig.class, GlobalExceptionHandler.class,
        RateLimiter.class, RateLimitingFilter.class})
@TestPropertySource(properties = {
        "app.mail-from=from@example.com",
        "app.remember-me-key=test-remember-key",
        "app.billing.enabled=true"
})
class SettingsControllerSubscriptionTest {

    private static final long UID = 1L;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DataExportService dataExportService;

    @MockitoBean
    RetentionService retentionService;

    @MockitoBean
    EntitlementService entitlementService;

    @MockitoBean
    SubscriptionService subscriptionService;

    @MockitoBean
    UserService userService;

    @MockitoBean
    ArticleService articleService;

    @MockitoBean
    CurrentUser currentUser;

    @MockitoBean
    UserDetailsService userDetailsService;

    @BeforeEach
    void signInAsUserOne() {
        AppUser user = new AppUser(UID, "user@example.com", "hash", "reader@kindle.com",
                Instant.now(), null, Instant.now(), Instant.now());
        when(currentUser.requireId()).thenReturn(UID);
        when(currentUser.details()).thenReturn(Optional.of(new AppUserDetails(user)));
        when(userService.findById(UID)).thenReturn(Optional.of(user));
        when(subscriptionService.forUser(UID)).thenReturn(Subscription.free(UID));
        when(entitlementService.forUser(UID))
                .thenReturn(new Entitlement(Plan.FREE, 10, 10, 15, false));
    }

    @Test
    @WithMockUser
    void theSubscriptionPageSaysToUseAPhoneNotTheKindle() throws Exception {
        mockMvc.perform(get("/settings/subscription"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Do this on your phone")))
                .andExpect(content().string(containsString("not on the Kindle e-reader")));
    }
}
