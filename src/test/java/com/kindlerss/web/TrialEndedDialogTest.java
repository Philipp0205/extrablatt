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
import com.kindlerss.service.FeedService;
import com.kindlerss.service.KindleMailService;
import com.kindlerss.service.RetentionService;
import com.kindlerss.service.SubscriptionService;
import com.kindlerss.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * After the complimentary week, each new login opens a notice with Subscribe and
 * Close. Close lasts for the rest of that session only.
 */
@WebMvcTest(controllers = {AppController.class, SettingsController.class})
@Import({com.kindlerss.config.SecurityConfig.class, GlobalExceptionHandler.class,
        RateLimiter.class, RateLimitingFilter.class})
@TestPropertySource(properties = {
        "app.mail-from=from@example.com",
        "app.remember-me-key=test-remember-key",
        "app.billing.enabled=true"
})
class TrialEndedDialogTest {

    private static final long UID = 1L;

    private static final Entitlement ENDED_TRIAL =
            new Entitlement(Plan.FREE, 0, 0, 0, false, null, true);

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    EntitlementService entitlementService;

    @MockitoBean
    FeedService feedService;

    @MockitoBean
    ArticleService articleService;

    @MockitoBean
    KindleMailService kindleMailService;

    @MockitoBean
    CurrentUser currentUser;

    @MockitoBean
    UserDetailsService userDetailsService;

    @MockitoBean
    UserService userService;

    @MockitoBean
    com.kindlerss.service.ReadableTime readableTime;

    @MockitoBean
    DataExportService dataExportService;

    @MockitoBean
    RetentionService retentionService;

    @MockitoBean
    SubscriptionService subscriptionService;

    @BeforeEach
    void signInAsUserOne() {
        AppUser user = new AppUser(UID, "user@example.com", "hash", "reader@kindle.com",
                Instant.now(), null, Instant.now(), Instant.now());
        when(currentUser.requireId()).thenReturn(UID);
        when(currentUser.details()).thenReturn(Optional.of(new AppUserDetails(user)));
        when(userService.findById(UID)).thenReturn(Optional.of(user));
        when(userService.markReadOnNextPage(UID)).thenReturn(true);
        when(feedService.listFeeds(UID)).thenReturn(List.of());
        when(entitlementService.forUser(anyLong())).thenReturn(ENDED_TRIAL);
        when(subscriptionService.forUser(UID)).thenReturn(Subscription.free(UID));
    }

    @Test
    @WithMockUser
    void homeOpensTheTrialEndedNoticeWithSubscribeAndClose() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"trial-ended-dialog\"")))
                .andExpect(content().string(containsString("Your trial has ended")))
                .andExpect(content().string(containsString("Thank you for using Extrablatt.")))
                .andExpect(content().string(containsString("href=\"/settings/subscription\"")))
                .andExpect(content().string(containsString(">Subscribe</a>")))
                .andExpect(content().string(containsString(">Close</button>")));
    }

    @Test
    @WithMockUser
    void settingsAlsoOpensTheNoticeUntilItIsClosed() throws Exception {
        mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"trial-ended-dialog\"")));
    }

    @Test
    @WithMockUser
    void theSubscriptionPageIsNotCoveredByTheNotice() throws Exception {
        mockMvc.perform(get("/settings/subscription"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"trial-ended-dialog\""))));
    }

    @Test
    @WithMockUser
    void aPaidAccountIsNotAskedToSubscribeAgain() throws Exception {
        when(entitlementService.forUser(anyLong())).thenReturn(
                new Entitlement(Plan.SUPPORTER, 50, 0, 50, true));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"trial-ended-dialog\""))));
    }

    @Test
    @WithMockUser
    void closingHidesTheNoticeForTheRestOfTheSession() throws Exception {
        MvcResult first = mockMvc.perform(get("/"))
                .andExpect(content().string(containsString("id=\"trial-ended-dialog\"")))
                .andReturn();
        MockHttpSession session = (MockHttpSession) first.getRequest().getSession();

        mockMvc.perform(post("/settings/trial-ended/ack").with(csrf())
                        .session(session)
                        .param("redirect", "/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"trial-ended-dialog\""))));
    }

    @Test
    @WithMockUser
    void aNewSessionShowsTheNoticeAgain() throws Exception {
        mockMvc.perform(post("/settings/trial-ended/ack").with(csrf())
                        .param("redirect", "/"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"trial-ended-dialog\"")));
    }

    @Test
    @WithMockUser
    void theTrialNoticeTakesPriorityOverWhatsNew() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"trial-ended-dialog\"")))
                .andExpect(content().string(not(containsString("id=\"whats-new-dialog\""))));
    }
}
