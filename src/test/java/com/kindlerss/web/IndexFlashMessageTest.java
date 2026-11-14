package com.kindlerss.web;

import com.kindlerss.domain.AppUser;
import com.kindlerss.domain.Entitlement;
import com.kindlerss.domain.Plan;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.security.RateLimiter;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.service.ApiTokenService;
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
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The feeds page renders the controller's flash {@code message} (feed added, deleted,
 * renamed, marked read) as well as its {@code error}. The message line was once lost
 * from the template, so every success confirmation silently disappeared.
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
class IndexFlashMessageTest {

    private static final long UID = 7L;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ApiTokenService apiTokenService;

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
        when(entitlementService.forUser(anyLong())).thenReturn(
                new Entitlement(Plan.SUPPORTER, 50, 0, 50, true, null));
    }

    @Test
    @WithMockUser
    void feedsPageShowsTheFlashMessage() throws Exception {
        mockMvc.perform(get("/").flashAttr("message", "Added feed: Demo"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Added feed: Demo")))
                .andExpect(content().string(containsString("role=\"status\"")));
    }

    @Test
    @WithMockUser
    void feedsPageShowsTheFlashError() throws Exception {
        mockMvc.perform(get("/").flashAttr("error", "Boom"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Boom")))
                .andExpect(content().string(containsString("role=\"alert\"")));
    }
}
