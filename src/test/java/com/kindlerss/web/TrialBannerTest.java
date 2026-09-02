package com.kindlerss.web;

import com.kindlerss.domain.AppUser;
import com.kindlerss.domain.Entitlement;
import com.kindlerss.domain.Plan;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.security.RateLimiter;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.service.ArticleService;
import com.kindlerss.service.EntitlementService;
import com.kindlerss.service.FeedService;
import com.kindlerss.service.KindleMailService;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The trial notice is the one flash every brand-new account sees, so it renders on
 * the pages a new account lands on. It once carried a formatted date string as an
 * operand of SpEL's {@code and}, which is not convertible to a boolean: the
 * expression threw and took the feeds and article pages down for anybody whose
 * trial was still running — that is, for everybody who had just registered.
 */
@WebMvcTest(controllers = AppController.class)
@Import({com.kindlerss.config.SecurityConfig.class, GlobalExceptionHandler.class,
        RateLimiter.class, RateLimitingFilter.class})
@TestPropertySource(properties = {
        "app.mail-from=from@example.com",
        "app.remember-me-key=test-remember-key",
        "app.articles.page-size=20",
        "app.billing.enabled=true"
})
class TrialBannerTest {

    private static final long UID = 7L;

    /** Fixed so the rendered notice can be matched on an exact date. */
    private static final Instant TRIAL_ENDS =
            Instant.parse("2026-09-08T12:00:00Z").truncatedTo(ChronoUnit.SECONDS);

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    FeedService feedService;

    @MockitoBean
    ArticleService articleService;

    @MockitoBean
    KindleMailService kindleMailService;

    @MockitoBean
    EntitlementService entitlementService;

    @MockitoBean
    CurrentUser currentUser;

    @MockitoBean
    UserService userService;

    @MockitoBean
    UserDetailsService userDetailsService;

    @BeforeEach
    void signIn() {
        AppUser user = new AppUser(UID, "new@example.com", "hash", "reader@kindle.com",
                Instant.now(), null, Instant.now(), Instant.now());
        when(currentUser.requireId()).thenReturn(UID);
        when(currentUser.details()).thenReturn(Optional.of(new AppUserDetails(user)));
        when(userService.findById(UID)).thenReturn(Optional.of(user));
        when(feedService.listFeeds(UID)).thenReturn(List.of());
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(1), eq(20))).thenReturn(List.of());
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(0L);
    }

    private void onTrialUntil(Instant trialEndsAt) {
        when(entitlementService.forUser(anyLong())).thenReturn(
                new Entitlement(Plan.SUPPORTER, 50, 0, 50, true, trialEndsAt));
    }

    @Test
    @WithMockUser
    void feedsPageNamesTheDayTheTrialEnds() throws Exception {
        onTrialUntil(TRIAL_ENDS);

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("You have a free week of the full plan")))
                .andExpect(content().string(containsString("8 September 2026")))
                .andExpect(content().string(containsString("href=\"/settings/subscription\"")));
    }

    @Test
    @WithMockUser
    void articleListNamesTheDayTheTrialEnds() throws Exception {
        onTrialUntil(TRIAL_ENDS);

        mockMvc.perform(get("/items").param("unread", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("You have a free week of the full plan")))
                .andExpect(content().string(containsString("8 September 2026")));
    }

    @Test
    @WithMockUser
    void noTrialLeavesBothPagesWithoutTheNotice() throws Exception {
        onTrialUntil(null);

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("free week of the full plan"))));

        mockMvc.perform(get("/items").param("unread", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("free week of the full plan"))));
    }
}
