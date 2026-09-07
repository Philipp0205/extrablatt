package com.kindlerss.web;

import com.kindlerss.domain.AppUser;
import com.kindlerss.domain.Article;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.security.RateLimiter;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.service.AdminTelemetryService;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A page has to be styled the instant it is drawn.
 *
 * <p>A stylesheet linked from the {@code <head>} stops the browser painting until
 * it arrives, so on a phone the page could be seen with no styling at all while
 * that request was in flight — and the request happened on every navigation,
 * because static files were served {@code no-store}. The rules now travel inside
 * the page, which is what these tests hold in place: a page that goes back to
 * linking its stylesheet goes back to flashing.
 */
@WebMvcTest(controllers = {AppController.class, AuthController.class, SettingsController.class})
@Import({com.kindlerss.config.SecurityConfig.class, GlobalExceptionHandler.class,
        RateLimiter.class, RateLimitingFilter.class})
@TestPropertySource(properties = {
        "app.mail-from=from@example.com",
        "app.remember-me-key=test-remember-key"
})
class PageStylingTest {

    private static final long UID = 1L;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    EntitlementService entitlementService;

    @MockitoBean
    SubscriptionService subscriptionService;

    @MockitoBean
    FeedService feedService;

    @MockitoBean
    ArticleService articleService;

    @MockitoBean
    KindleMailService kindleMailService;

    @MockitoBean
    AdminTelemetryService telemetryService;

    @MockitoBean
    DataExportService dataExportService;

    @MockitoBean
    RetentionService retentionService;

    @MockitoBean
    CurrentUser currentUser;

    @MockitoBean
    UserService userService;

    @MockitoBean
    UserDetailsService userDetailsService;

    @BeforeEach
    void signIn() {
        AppUser user = new AppUser(UID, "user@example.com", "hash", "reader@kindle.com",
                Instant.now(), null, Instant.now(), Instant.now());
        when(currentUser.requireId()).thenReturn(UID);
        when(currentUser.details()).thenReturn(Optional.of(new AppUserDetails(user)));
        when(userService.findById(UID)).thenReturn(Optional.of(user));
        when(feedService.listFeeds(UID)).thenReturn(List.of());
        when(articleService.findPage(eq(UID), isNull(), isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(articleService.count(eq(UID), isNull(), isNull(), isNull(), isNull())).thenReturn(0L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/items?unread=false", "/settings", "/settings/kindle",
            "/settings/reading", "/settings/account", "/settings/data", "/login", "/register",
            "/forgot-password", "/privacy", "/terms", "/imprint", "/withdrawal"})
    @WithMockUser
    void everyPageCarriesItsStylingRatherThanPointingAtIt(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                // The first rule in the stylesheet, so an empty <style> element or a
                // stylesheet that failed to load fails this rather than passing quietly.
                .andExpect(content().string(containsString("box-sizing: border-box")))
                .andExpect(content().string(matchesPattern("(?s).*<head>.*<style>.*</style>.*</head>.*")))
                .andExpect(content().string(not(containsString("rel=\"stylesheet\""))));
    }

    /** The page a reader spends the most time on, and the one that pages as it goes. */
    @Test
    @WithMockUser
    void anArticleIsStyledFromTheFirstPaintToo() throws Exception {
        Article article = new Article(7L, 1L, "guid", "Paged article", "https://example.com/a", null,
                null, null, null, null, true, null, null, null, "Example Feed");
        when(articleService.findById(UID, 7L)).thenReturn(Optional.of(article));
        when(articleService.getReaderHtml(any(Article.class), eq(false))).thenReturn("<p>Body</p>");

        mockMvc.perform(get("/articles/7"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("box-sizing: border-box")))
                .andExpect(content().string(not(containsString("rel=\"stylesheet\""))));
    }

    /**
     * The prose in the stylesheet is written for whoever edits it next. Sending it to
     * every reader on every page would more than double what carrying the rules costs.
     */
    @Test
    void theRulesReachThePageWithoutTheProseThatExplainsThem() throws Exception {
        String page = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(page.contains("/*"), "no comment survives into the page");
        assertTrue(page.contains("box-sizing: border-box"), "the rules themselves do");
    }

    @Test
    void aCommentIsDroppedButAQuotedValueThatLooksLikeOneIsNot() {
        assertEquals("a { color: red }", StylesheetAdvice.withoutComments(
                "/* the brand colour */\na { color: red }"));
        assertEquals("a { color: red }", StylesheetAdvice.withoutComments(
                "a { color: red } /* an unclosed trailing note"));
        assertEquals("a::after { content: \"/* not a comment */\" }",
                StylesheetAdvice.withoutComments("a::after { content: \"/* not a comment */\" }"));
        assertEquals("a { b: c }\nd { e: f }", StylesheetAdvice.withoutComments(
                "a { b: c }\n\n/* why d is like this,\n   at length */\n\nd { e: f }"));
    }
}
