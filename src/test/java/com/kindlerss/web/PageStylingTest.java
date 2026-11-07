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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every page is styled by the one linked, content-addressed stylesheet and nothing
 * else.
 *
 * <p>The stylesheet used to travel inside every page, because a linked one was
 * served {@code no-store} and refetched on each navigation. It is now served as
 * {@code /css/app-<hash>.css} with a year-long immutable cache, so after the first
 * visit it costs no request at all, and the pages carry no inline styling: that is
 * what keeps them within a {@code style-src 'self'} policy. These tests hold both in
 * place, along with the frame every page shares.
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

    private static final String HASHED_STYLESHEET =
            "(?s).*<link rel=\"stylesheet\" href=\"/css/app-[0-9a-f]{32}\\.css\"/?>.*";
    private static final String HASHED_SCRIPT =
            "(?s).*<script src=\"/js/app-[0-9a-f]{32}\\.js\" defer(=\"defer\")?></script>.*";

    @ParameterizedTest
    @ValueSource(strings = {"/", "/items?unread=false", "/settings", "/settings/kindle",
            "/settings/reading", "/settings/account", "/settings/data", "/login", "/register",
            "/forgot-password", "/privacy", "/terms", "/imprint", "/withdrawal"})
    @WithMockUser
    void everyPageLinksTheHashedStylesheetAndCarriesNoInlineStyling(String path) throws Exception {
        String page = mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().string(matchesPattern(HASHED_STYLESHEET)))
                .andExpect(content().string(matchesPattern(HASHED_SCRIPT)))
                .andExpect(content().string(containsString("<meta name=\"color-scheme\" content=\"light dark\"")))
                .andExpect(content().string(containsString("viewport-fit=cover")))
                .andReturn().getResponse().getContentAsString();

        assertFalse(page.contains("<style"), "no <style> element");
        assertFalse(page.matches("(?s).*\\sstyle=\".*"), "no style attribute");
        assertFalse(page.matches("(?s).*\\son[a-z]+=\".*"), "no inline event handler");
        assertFalse(page.matches("(?s).*<script(?![^>]*\\ssrc=)[^>]*>.*"), "no inline script");
    }

    /** A skip link and one main landmark on every page, signed in or not. */
    @ParameterizedTest
    @ValueSource(strings = {"/", "/items?unread=false", "/settings", "/login", "/privacy"})
    @WithMockUser
    void everyPageHasASkipLinkToItsMainLandmark(String path) throws Exception {
        String page = mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<a class=\"skip-link\" href=\"#main\">Skip to content</a>")))
                .andReturn().getResponse().getContentAsString();

        assertEquals(1, page.split("<main id=\"main\"", -1).length - 1, "exactly one <main id=\"main\">");
    }

    /** Signed-in pages carry the three destinations, with the open one marked. */
    @Test
    @WithMockUser
    void signedInPagesMarkTheDestinationThatIsOpen() throws Exception {
        mockMvc.perform(get("/items?unread=false"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"tabbar\"")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<a href=\"/items\" aria-current=\"page\">.*<span>Articles</span>.*")));
        mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(matchesPattern(
                        "(?s).*<a href=\"/settings\" aria-current=\"page\">.*<span>Settings</span>.*")));
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(matchesPattern(
                        "(?s).*<a href=\"/\" aria-current=\"page\">.*<span>Feeds</span>.*")));
    }

    /** A signed-out page has nowhere to send its reader but the form in front of them. */
    @Test
    void signedOutPagesCarryNoAppNavigation() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("class=\"tabbar\""))))
                .andExpect(content().string(not(containsString("class=\"appnav\""))));
    }

    /** The page a reader spends the most time on scrolls; nothing pages it any more. */
    @Test
    @WithMockUser
    void anArticleIsAPlainScrollingPage() throws Exception {
        Article article = new Article(7L, 1L, "guid", "Scrolling article", "https://example.com/a", null,
                null, null, null, null, true, null, null, null, "Example Feed");
        when(articleService.findById(UID, 7L)).thenReturn(Optional.of(article));
        when(articleService.getReaderHtml(any(Article.class), eq(false))).thenReturn("<p>Body</p>");

        mockMvc.perform(get("/articles/7"))
                .andExpect(status().isOk())
                .andExpect(content().string(matchesPattern(HASHED_STYLESHEET)))
                .andExpect(content().string(not(containsString("<style"))))
                .andExpect(content().string(not(containsString("data-reader"))))
                .andExpect(content().string(not(containsString("/js/reader"))));
    }

    /**
     * The Content-Security-Policy allows styles and scripts from the app's own files
     * only, which is what the pages above are kept within; nothing inline is let in.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/", "/items?unread=false", "/settings/kindle", "/settings/account", "/login",
            "/register", "/withdrawal"})
    @WithMockUser
    void thePolicyAllowsOnlyTheAppsOwnStylesAndScripts(String path) throws Exception {
        String policy = mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self';")))
                .andExpect(header().string("Content-Security-Policy", containsString("style-src 'self';")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Content-Security-Policy", containsString("object-src 'none'")))
                .andReturn().getResponse().getHeader("Content-Security-Policy");
        assertFalse(policy.contains("unsafe-inline"), policy);
    }

    /** The confirmation prompts survive the move out of inline handlers (app.js asks). */
    @Test
    @WithMockUser
    void destructiveFormsStillAskFirst() throws Exception {
        mockMvc.perform(get("/settings/account"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "data-confirm=\"Delete your account and all its data? This cannot be undone.\"")))
                .andExpect(content().string(matchesPattern(HASHED_SCRIPT)));
    }
}
