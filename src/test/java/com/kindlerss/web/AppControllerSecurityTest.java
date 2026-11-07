package com.kindlerss.web;

import com.kindlerss.domain.AppUser;
import com.kindlerss.domain.Article;
import com.kindlerss.domain.Feed;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.security.RateLimiter;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.service.ArticleService;
import com.kindlerss.service.ChangelogCatalog;
import com.kindlerss.service.EntitlementService;
import com.kindlerss.service.FeedService;
import com.kindlerss.service.KindleMailService;
import com.kindlerss.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(controllers = {AppController.class, AuthController.class})
@Import({com.kindlerss.config.SecurityConfig.class, GlobalExceptionHandler.class,
        RateLimiter.class, RateLimitingFilter.class})
@TestPropertySource(properties = {
        "app.mail-from=from@example.com",
        "app.remember-me-key=test-remember-key",
        "app.articles.page-size=20"
})
class AppControllerSecurityTest {

    private static final long UID = 1L;

    /**
     * Pages load the one shared script under a name that carries a hash of its
     * contents, which is what lets a browser keep it for good. Asserted as a pattern
     * rather than a fixed name, because the hash changes whenever the script does.
     */
    private static final String CONTENT_ADDRESSED_APP = "(?s).*/js/app-[0-9a-f]{32}\\.js.*";

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

    @BeforeEach
    void signInAsUserOne() {
        AppUser user = new AppUser(UID, "user@example.com", "hash", "reader@kindle.com",
                Instant.now(), null, Instant.now(), Instant.now());
        when(currentUser.requireId()).thenReturn(UID);
        when(currentUser.details()).thenReturn(Optional.of(new AppUserDetails(user)));
        when(userService.markReadOnNextPage(UID)).thenReturn(true);
        when(entitlementService.forUser(anyLong())).thenReturn(
                new com.kindlerss.domain.Entitlement(
                        com.kindlerss.domain.Plan.SUPPORTER, 50, 0, 50, true));
    }

    @Test
    void unauthenticatedRootRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void loginPageIsAccessible() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(content().string(not(containsString("Klarblatt"))))
                .andExpect(content().string(not(containsString("accessible reader"))))
                .andExpect(content().string(containsString("Show password")))
                .andExpect(content().string(containsString("data-show-password=\"password\"")))
                .andExpect(content().string(containsString("/js/show-password")));
    }

    @Test
    void registrationPageIsAccessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(view().name("register"));
    }

    @Test
    void registrationConfirmsThatAnEmailWasSent() throws Exception {
        mockMvc.perform(post("/register").with(csrf())
                        .param("email", "new@example.com")
                        .param("password", "supersecret"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-email"))
                .andExpect(flash().attribute("sentTo", "new@example.com"));
        verify(userService).register("new@example.com", "supersecret");
    }

    @Test
    void aFailedVerificationEmailReportsThatNoAccountWasCreated() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("Could not send e-mail"))
                .when(userService).register(anyString(), anyString());

        mockMvc.perform(post("/register").with(csrf())
                        .param("email", "new@example.com")
                        .param("password", "supersecret"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/register"))
                .andExpect(flash().attribute("error",
                        containsString("account was not created")));
    }

    @Test
    void passwordResetConfirmsThatAnEmailWasSent() throws Exception {
        mockMvc.perform(post("/forgot-password").with(csrf())
                        .param("email", "someone@example.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/check-email"))
                .andExpect(flash().attribute("sentTo", "someone@example.com"));
        verify(userService).requestPasswordReset("someone@example.com");
    }

    @Test
    void theConfirmationPageNamesTheAddressItWrote() throws Exception {
        mockMvc.perform(get("/check-email")
                        .flashAttr("sentTo", "new@example.com")
                        .flashAttr("instruction", "Open the confirmation link."))
                .andExpect(status().isOk())
                .andExpect(view().name("check-email"))
                .andExpect(content().string(containsString("new@example.com")))
                .andExpect(content().string(containsString("Open the confirmation link.")));
    }

    @Test
    void visitingTheConfirmationPageDirectlyHasNothingToReport() throws Exception {
        mockMvc.perform(get("/check-email"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void verifyLinkShowsAConfirmationResult() throws Exception {
        when(userService.verifyEmail("tok")).thenReturn(true);
        mockMvc.perform(get("/verify").param("token", "tok"))
                .andExpect(status().isOk())
                .andExpect(view().name("verify-result"))
                .andExpect(content().string(containsString("E-mail confirmed")))
                .andExpect(content().string(containsString("Go to login")));
    }

    @Test
    void formLoginSucceedsWithConfiguredPassword() throws Exception {
        String hash = new BCryptPasswordEncoder().encode("test-password-123");
        AppUser account = new AppUser(UID, "user@example.com", hash, null,
                Instant.now(), null, Instant.now(), Instant.now());
        when(userDetailsService.loadUserByUsername("user@example.com"))
                .thenReturn(new AppUserDetails(account));

        mockMvc.perform(formLogin().user("user@example.com").password("test-password-123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
        verify(userService, org.mockito.Mockito.atLeastOnce()).recordLastLogin(UID);
    }

    @Test
    void unverifiedAccountCannotLogInAndGetsSpecificError() throws Exception {
        String hash = new BCryptPasswordEncoder().encode("test-password-123");
        AppUser account = new AppUser(UID, "user@example.com", hash, null,
                null, null, Instant.now(), Instant.now());
        when(userDetailsService.loadUserByUsername("user@example.com"))
                .thenReturn(new AppUserDetails(account));

        mockMvc.perform(formLogin().user("user@example.com").password("test-password-123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?unverified"));
        verify(userService, never()).recordLastLogin(anyLong());

        mockMvc.perform(get("/login").param("unverified", ""))
                .andExpect(content().string(containsString("Confirm your e-mail address before logging in")));
    }

    @Test
    @WithMockUser
    void homeRequiresAuthAndRenders() throws Exception {
        when(feedService.listFeeds(UID)).thenReturn(List.of());
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(content().string(not(containsString("action=\"/refresh\""))))
                .andExpect(content().string(not(containsString(">Refresh</button>"))));
        verify(feedService).refreshForUserSoon(UID);
    }

    @Test
    @WithMockUser
    void homeWelcomePromptExplainsWhereToFindTheKindleEmail() throws Exception {
        when(feedService.listFeeds(UID)).thenReturn(List.of());
        when(userService.findById(UID)).thenReturn(Optional.of(new AppUser(UID, "user@example.com",
                "hash", null, Instant.now(), null, Instant.now(), Instant.now())));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Welcome! Two quick steps")))
                .andExpect(content().string(containsString(
                        "Manage Your Content and Devices → Preferences → Personal Document Settings")));
    }

    @Test
    @WithMockUser
    void homeHidesTheWelcomePromptOnceAKindleEmailIsSaved() throws Exception {
        when(feedService.listFeeds(UID)).thenReturn(List.of());
        when(userService.findById(UID)).thenReturn(Optional.of(new AppUser(UID, "user@example.com",
                "hash", "reader@kindle.com", Instant.now(), null, Instant.now(), Instant.now())));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Welcome! Two quick steps"))));
    }

    @Test
    @WithMockUser
    void homeShowsWhatsNewUntilTheLatestReleaseIsAcknowledged() throws Exception {
        when(feedService.listFeeds(UID)).thenReturn(List.of());
        when(userService.findById(UID)).thenReturn(Optional.of(new AppUser(UID, "user@example.com",
                "hash", "reader@kindle.com", Instant.now(), null, Instant.now(), Instant.now())));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"whats-new-dialog\"")))
                .andExpect(content().string(containsString("What's new")));

        String latest = ChangelogCatalog.instance().latestId().orElseThrow();
        when(userService.findById(UID)).thenReturn(Optional.of(new AppUser(UID, "user@example.com",
                "hash", "reader@kindle.com", Instant.now(), null, Instant.now(), Instant.now(),
                null, true, latest)));
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"whats-new-dialog\""))));
    }

    @Test
    @WithMockUser
    void homeOffersOptionalDefaultsAndFeedCategories() throws Exception {
        when(feedService.defaultFeeds(UID)).thenReturn(List.of(
                new FeedService.DefaultFeed("hacker-news", "Hacker News",
                        "https://hnrss.org/frontpage", "Technology")));

        // Suggested feeds are only offered before anything has been subscribed.
        when(feedService.listFeeds(UID)).thenReturn(List.of());
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Quick start")))
                .andExpect(content().string(containsString("value=\"hacker-news\"")))
                .andExpect(content().string(containsString("<summary>Follow a site</summary>")))
                .andExpect(content().string(not(containsString("aria-label=\"Feed views\""))));

        when(feedService.listFeeds(UID)).thenReturn(List.of(
                new Feed(5L, "Android", "https://example.com/feed", "https://example.com",
                        "Technology", null, null, null)));
        // Level one shows one compact category row, not every feed.
        mockMvc.perform(get("/").param("view", "free-test"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Quick start"))))
                .andExpect(content().string(not(containsString("Your test is complete"))))
                .andExpect(content().string(containsString("href=\"/?category=Technology\"")))
                .andExpect(content().string(containsString("1 feed · 0 unread")))
                .andExpect(content().string(not(containsString(">Android</a>"))));

        // Level two shows only the feeds in the selected category and a way back.
        mockMvc.perform(get("/").param("category", "Technology"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/\">← All categories</a>")))
                .andExpect(content().string(containsString("action=\"/feeds/5/category\"")))
                .andExpect(content().string(containsString("action=\"/feeds/5/read\"")))
                .andExpect(content().string(containsString("<summary>Edit</summary>")))
                .andExpect(content().string(containsString(">Mark read</button>")))
                .andExpect(content().string(containsString(">Technology</h1>")))
                .andExpect(content().string(containsString(">Android</a>")));
    }

    @Test
    @WithMockUser
    void postWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/refresh"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser
    void refreshWithCsrfWorks() throws Exception {
        doNothing().when(feedService).refreshForUser(UID);
        mockMvc.perform(post("/refresh").with(csrf())
                        .param("redirect", "/items?page=2&unread=false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/items?page=2&unread=false"));
        verify(feedService).refreshForUser(UID);
    }

    @Test
    @WithMockUser
    void missingArticleReturns404() throws Exception {
        when(articleService.findById(UID, 99L)).thenReturn(Optional.empty());
        mockMvc.perform(get("/articles/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void itemsPageRenders() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(1), eq(20))).thenReturn(List.of());
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(0L);
        when(feedService.listFeeds(UID)).thenReturn(List.of());
        mockMvc.perform(get("/items").param("unread", "false"))
                .andExpect(status().isOk())
                .andExpect(view().name("items"))
                .andExpect(content().string(not(containsString("action=\"/refresh\""))))
                .andExpect(content().string(not(containsString(">Refresh</button>"))));
        verify(feedService).refreshForUserSoon(UID);
    }

    @Test
    @WithMockUser
    void itemsDefaultToAStableUnreadList() throws Exception {
        mockMvc.perform(get("/items"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/items?page=1&unread=true&snapshot=*"));
    }

    @Test
    @WithMockUser
    void itemsPageFiltersByCategoryAndOpensItsFeedsWhenOneIsChosen() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(1), eq(20))).thenReturn(List.of());
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(0L);
        when(articleService.findPage(eq(UID), isNull(), eq("Technology"), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(List.of());
        when(articleService.count(eq(UID), isNull(), eq("Technology"), isNull(), isNull())).thenReturn(0L);
        when(feedService.listFeeds(UID)).thenReturn(List.of(
                new Feed(5L, "Android Police", "https://example.com/a", null, "Technology", null, null, null),
                new Feed(6L, "The Verge", "https://example.com/b", null, "Technology", null, null, null),
                new Feed(7L, "Nature", "https://example.com/c", null, "Science", null, null, null)));

        // Without a category the bar is a list of categories, not of every feed.
        mockMvc.perform(get("/items").param("unread", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/items?category=Technology&amp;unread=false\"")))
                .andExpect(content().string(containsString("href=\"/items?category=Science&amp;unread=false\"")))
                .andExpect(content().string(not(containsString("Android Police"))));

        mockMvc.perform(get("/items").param("category", "Technology").param("unread", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/items?feed=5&amp;unread=false\"")))
                .andExpect(content().string(containsString("Android Police")))
                .andExpect(content().string(not(containsString("Nature"))));
    }

    @Test
    @WithMockUser
    void openingACategoryReplacesTheFilterRowRatherThanAddingASecondOne() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(1), eq(20))).thenReturn(List.of());
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(0L);
        when(articleService.findPage(eq(UID), isNull(), eq("Technology"), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(List.of());
        when(articleService.count(eq(UID), isNull(), eq("Technology"), isNull(), isNull())).thenReturn(0L);
        when(feedService.listFeeds(UID)).thenReturn(List.of(
                new Feed(5L, "Android Police", "https://example.com/a", null, "Technology", null, null, null),
                new Feed(7L, "Nature", "https://example.com/c", null, "Science", null, null, null)));

        // One strip on either level, so the list never gives up two lines to filters.
        mockMvc.perform(get("/items").param("unread", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(stringContainsCount("data-strip-track", 1)))
                .andExpect(content().string(containsString("Science")))
                .andExpect(content().string(not(containsString(BACK_CONTROL))));

        // Inside a category the row is that category and its feeds; the other
        // categories are behind the back control rather than beside them.
        mockMvc.perform(get("/items").param("category", "Technology").param("unread", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(stringContainsCount("data-strip-track", 1)))
                .andExpect(content().string(containsString(BACK_CONTROL)))
                .andExpect(content().string(not(containsString("Science"))));
    }

    /**
     * The way out of a level. Named by what a screen reader is told rather than by the
     * arrow: the arrow and the word are separate elements, because the narrowest
     * screens keep the arrow and drop the word.
     */
    private static final String BACK_CONTROL = "aria-label=\"All categories\"";

    @Test
    @WithMockUser
    void theChipsThatLeaveALevelStayOutsideTheScrollingRow() throws Exception {
        when(articleService.findPage(eq(UID), eq(5L), isNull(), eq(1), eq(20))).thenReturn(List.of());
        when(articleService.count(eq(UID), eq(5L), isNull())).thenReturn(0L);
        when(feedService.findById(UID, 5L)).thenReturn(Optional.of(
                new Feed(5L, "Android Police", "https://example.com/a", null, "Technology", null, null, null)));
        when(feedService.listFeeds(UID)).thenReturn(List.of(
                new Feed(5L, "Android Police", "https://example.com/a", null, "Technology", null, null, null)));

        // Arriving by feed alone still opens that feed's category, and back and the
        // unread switch sit ahead of the row, where scrolling it cannot hide them.
        String body = mockMvc.perform(get("/items").param("feed", "5").param("unread", "false"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String markup = body;
        int back = markup.indexOf(BACK_CONTROL);
        int unreadToggle = markup.indexOf("/items?feed=5&amp;unread=true");
        int divide = markup.indexOf("filter-divide");
        int strip = markup.indexOf("data-strip-track");
        org.junit.jupiter.api.Assertions.assertTrue(back > 0 && back < divide,
                "back chip belongs before the divide");
        org.junit.jupiter.api.Assertions.assertTrue(unreadToggle > 0 && unreadToggle < divide,
                "unread switch belongs before the divide");
        org.junit.jupiter.api.Assertions.assertTrue(divide < strip,
                "the divide fences the level off from the controls that are not part of it");
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("href=\"/items?category=Technology&amp;unread=false\""),
                "the open category leads its own feeds");
    }

    @Test
    @WithMockUser
    void theUnreadSwitchIsNotDrawnLikeAFeed() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), eq("Technology"), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(List.of());
        when(articleService.count(eq(UID), isNull(), eq("Technology"), isNull(), isNull())).thenReturn(0L);
        when(feedService.listFeeds(UID)).thenReturn(List.of(
                new Feed(5L, "Android Police", "https://example.com/a", null, "Technology", null, null, null)));

        // Off: a switch that says it is off, and none of the marks a place carries.
        mockMvc.perform(get("/items").param("category", "Technology").param("unread", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"chip filter-mode\"")))
                .andExpect(content().string(containsString("aria-pressed=\"false\"")));

        // On: the switch says so, and exactly one chip in the row is where the reader is.
        String body = mockMvc.perform(get("/items")
                        .param("category", "Technology").param("unread", "true")
                        .param("snapshot", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("aria-pressed=\"true\"")))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(
                body.contains("class=\"chip filter-mode active\""),
                "the switch marks itself on");
        org.junit.jupiter.api.Assertions.assertEquals(1,
                body.split("aria-current=\"true\"", -1).length - 1,
                "exactly one chip in the row is where the reader is");
    }

    @Test
    @WithMockUser
    void theArticleListStartsAtTheFirstArticle() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(1), eq(20))).thenReturn(
                List.of(new Article(1L, 1L, "guid-1", "Article 1", null, null,
                        null, null, null, null, false, null, null, null, "Example Feed")));
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(1L);
        when(feedService.listFeeds(UID)).thenReturn(List.of());

        String body = mockMvc.perform(get("/items").param("unread", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<h1>Articles</h1>")))
                .andExpect(content().string(containsString("1–1 of 1 articles")))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(
                body.indexOf("1–1 of 1 articles") > body.indexOf("class=\"item-title\""),
                "the count reads under the list, not above it");
    }

    /** Matches a body holding exactly {@code times} copies of {@code needle}. */
    private static org.hamcrest.Matcher<String> stringContainsCount(String needle, int times) {
        return new org.hamcrest.CustomTypeSafeMatcher<>(needle + " exactly " + times + " time(s)") {
            @Override
            protected boolean matchesSafely(String body) {
                int found = 0;
                for (int at = body.indexOf(needle); at >= 0; at = body.indexOf(needle, at + needle.length())) {
                    found++;
                }
                return found == times;
            }
        };
    }

    @Test
    @WithMockUser
    void everyFeedIsInTheRowAndTheRowScrollsNatively() throws Exception {
        List<Feed> feeds = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            feeds.add(new Feed((long) i, "A rather long feed name " + i, "https://example.com/" + i,
                    null, "Technology", null, null, null));
        }
        when(articleService.findPage(eq(UID), isNull(), eq("Technology"), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(List.of());
        when(articleService.count(eq(UID), isNull(), eq("Technology"), isNull(), isNull())).thenReturn(0L);
        when(feedService.listFeeds(UID)).thenReturn(feeds);

        // The whole row is rendered and scrolls sideways on its own; there are no
        // arrows to turn it and no script that measures it.
        mockMvc.perform(get("/items").param("category", "Technology").param("unread", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/items?feed=1&amp;unread=false\"")))
                .andExpect(content().string(containsString("href=\"/items?feed=12&amp;unread=false\"")))
                .andExpect(content().string(containsString("data-strip-track")))
                .andExpect(content().string(not(containsString("data-strip-prev"))))
                .andExpect(content().string(not(containsString("data-strip-next"))))
                .andExpect(content().string(not(containsString("/js/filters"))));
    }

    @Test
    @WithMockUser
    void feedsWithoutACategoryStayReachable() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), eq("Uncategorized"), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(List.of());
        when(articleService.count(eq(UID), isNull(), eq("Uncategorized"), isNull(), isNull())).thenReturn(0L);
        when(feedService.listFeeds(UID)).thenReturn(List.of(
                new Feed(9L, "Loose Feed", "https://example.com/l", null, null, null, null, null)));

        mockMvc.perform(get("/items").param("category", "Uncategorized").param("unread", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/items?feed=9&amp;unread=false\"")))
                .andExpect(content().string(containsString("Loose Feed")));
    }

    @Test
    void buildIdentityFallsBackWhenNotPackaged() {
        BuildInfoAdvice.Version version = BuildInfoAdvice.describe(null);
        org.junit.jupiter.api.Assertions.assertEquals("development build", version.number());
        org.junit.jupiter.api.Assertions.assertEquals("unknown", version.revision());
        org.junit.jupiter.api.Assertions.assertEquals("unknown", version.builtAt());
    }

    @Test
    void buildIdentityReadsRevisionAndTimeFromBuildProperties() {
        Properties properties = new Properties();
        properties.setProperty("version", "1.0.0");
        properties.setProperty("revision", "abc1234");
        properties.setProperty("time", "1767225600000");
        BuildInfoAdvice.Version version = BuildInfoAdvice.describe(new BuildProperties(properties));
        org.junit.jupiter.api.Assertions.assertEquals("1.0.0", version.number());
        org.junit.jupiter.api.Assertions.assertEquals("abc1234", version.revision());
        org.junit.jupiter.api.Assertions.assertEquals("2026-01-01 00:00 UTC", version.builtAt());
    }

    @Test
    @WithMockUser
    void articlePageIsAScrollingReadingView() throws Exception {
        Article article = new Article(7L, 1L, "guid", "A long read", "https://example.com/a", "Ada",
                Instant.parse("2026-01-01T10:00:00Z"), null, null, null, true, null, null, null, "Example Feed");
        when(articleService.findById(UID, 7L)).thenReturn(Optional.of(article));
        when(articleService.getReaderHtml(any(Article.class), eq(false))).thenReturn("<p>Body</p>");

        String body = mockMvc.perform(get("/articles/7"))
                .andExpect(status().isOk())
                .andExpect(content().string(matchesPattern(CONTENT_ADDRESSED_APP)))
                .andExpect(content().string(not(containsString("data-reader"))))
                .andExpect(content().string(not(containsString("/js/reader"))))
                .andExpect(content().string(containsString("<h1>A long read</h1>")))
                .andExpect(content().string(containsString("<time datetime=\"2026-01-01T10:00:00Z\">")))
                .andExpect(content().string(containsString("By Ada")))
                .andExpect(content().string(containsString("<details class=\"action-menu\" data-action-menu>")))
                .andExpect(content().string(containsString(">More</summary>")))
                .andExpect(content().string(containsString(">Mark unread</button>")))
                .andExpect(content().string(containsString("<p>Body</p>")))
                .andReturn().getResponse().getContentAsString();

        // Sending is offered at the top and again where the article ends, and both
        // work as a plain form post without the script.
        org.junit.jupiter.api.Assertions.assertEquals(2,
                body.split("action=\"/articles/7/send\"", -1).length - 1,
                "a send form above the article and one below it");
        org.junit.jupiter.api.Assertions.assertTrue(
                body.contains("<a href=\"/items\" aria-current=\"page\">"),
                "an article belongs to Articles in the navigation");
    }

    @Test
    @WithMockUser
    void aListWithMoreToComeEndsInALoadMoreLink() throws Exception {
        List<Article> articles = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            articles.add(new Article((long) i, 1L, "guid-" + i, "Article " + i, null, null,
                    null, null, null, null, false, null, null, null, "Example Feed"));
        }
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(1), eq(20))).thenReturn(articles);
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(33L);
        when(feedService.listFeeds(UID)).thenReturn(List.of());

        // A plain link to the next page, which works without the script; app.js
        // fetches the batch address instead and appends what it answers with.
        mockMvc.perform(get("/items").param("unread", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("1–20 of 33 articles")))
                .andExpect(content().string(containsString("rel=\"next\" data-load-more")))
                .andExpect(content().string(containsString("href=\"/items?page=2&amp;unread=false\"")))
                .andExpect(content().string(containsString(
                        "data-batch-url=\"/items?page=2&amp;unread=false&amp;batch=true\"")))
                .andExpect(content().string(containsString(">Load more articles</a>")))
                .andExpect(content().string(not(containsString("Newer articles"))))
                .andExpect(content().string(not(containsString("/items/advance"))))
                .andExpect(content().string(not(containsString("data-reader"))));
    }

    @Test
    @WithMockUser
    void theAddressCanCarryEveryBatchThatWasLoaded() throws Exception {
        for (int p = 1; p <= 3; p++) {
            long id = p;
            when(articleService.findPage(eq(UID), isNull(), isNull(), eq(p), eq(20))).thenReturn(List.of(
                    new Article(id, 1L, "guid-" + id, "Article " + id, null, null,
                            null, null, null, null, false, null, null, null, "Example Feed")));
        }
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(80L);
        when(feedService.listFeeds(UID)).thenReturn(List.of());

        // Load-more writes through=3 into the address, so a reload, or coming back
        // with the browser's back button, shows all three batches in place.
        String body = mockMvc.perform(get("/items").param("unread", "false")
                        .param("page", "1").param("through", "3"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-article-id=\"1\"")))
                .andExpect(content().string(containsString("data-article-id=\"2\"")))
                .andExpect(content().string(containsString("data-article-id=\"3\"")))
                .andExpect(content().string(containsString("href=\"/items?page=4&amp;unread=false\"")))
                .andExpect(content().string(containsString("name=\"through\" data-mark-read-through value=\"3\"")))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(body.contains(
                        "name=\"redirect\" value=\"/items?page=1&amp;through=3&amp;unread=false\""),
                "sending from the list returns to the whole extended list");
    }

    @Test
    void anExtendedAddressIsKeptWithinTheListAndTheCap() {
        org.junit.jupiter.api.Assertions.assertEquals(1, AppController.lastBatch(1, null, 5));
        org.junit.jupiter.api.Assertions.assertEquals(2, AppController.lastBatch(2, 1, 5));
        org.junit.jupiter.api.Assertions.assertEquals(3, AppController.lastBatch(1, 3, 5));
        org.junit.jupiter.api.Assertions.assertEquals(5, AppController.lastBatch(1, 9, 5));
        org.junit.jupiter.api.Assertions.assertEquals(AppController.MAX_BATCHES_PER_PAGE,
                AppController.lastBatch(1, 500, 500));
    }

    @Test
    @WithMockUser
    void aBatchIsJustTheRowsAndTheLinkToTheNextOne() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(Boolean.TRUE), eq(Instant.ofEpochMilli(100)),
                eq(2), eq(20)))
                .thenReturn(List.of(new Article(21L, 1L, "guid-21", "Article 21", null, null,
                        Instant.now().minusSeconds(3 * 3600), null, null, null, false, null, null, null,
                        "Example Feed")));
        when(articleService.count(eq(UID), isNull(), isNull(), eq(Boolean.TRUE), eq(Instant.ofEpochMilli(100))))
                .thenReturn(60L);

        String body = mockMvc.perform(get("/items").param("unread", "true").param("snapshot", "100")
                        .param("page", "2").param("batch", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-batch")))
                .andExpect(content().string(containsString("data-article-id=\"21\"")))
                .andExpect(content().string(containsString("3 hours ago</time>")))
                .andExpect(content().string(containsString(
                        "data-batch-url=\"/items?page=3&amp;unread=true&amp;snapshot=100&amp;batch=true\"")))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(body.contains("<html"), "a fragment, not a page");
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("class=\"filters\""), "no filter row");
        // Fetched by script while reading; it is not a visit and starts no refresh.
        verify(feedService, never()).refreshForUserSoon(UID);
        verify(articleService, never()).markRead(eq(UID), anyList(), anyBoolean());
    }

    @Test
    @WithMockUser
    void aBatchPastTheEndIsEmptyRatherThanTheLastPageAgain() throws Exception {
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(21L);
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(5), eq(20))).thenReturn(List.of());

        mockMvc.perform(get("/items").param("unread", "false").param("page", "5").param("batch", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("data-article-id"))))
                .andExpect(content().string(not(containsString("data-load-more"))));

        verify(articleService, never()).findPage(eq(UID), isNull(), isNull(), eq(2), eq(20));
    }

    @Test
    @WithMockUser
    void aLaterPageLeadsBackToNewerArticlesAndEndsWhereTheListDoes() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(2), eq(20)))
                .thenReturn(List.of(new Article(21L, 1L, "guid-21", "Article 21", null, null,
                        null, null, null, null, false, null, null, null, "Example Feed")));
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(21L);
        when(feedService.listFeeds(UID)).thenReturn(List.of());

        mockMvc.perform(get("/items").param("page", "2").param("unread", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("rel=\"prev\"")))
                .andExpect(content().string(containsString(">Newer articles</a>")))
                .andExpect(content().string(not(containsString("data-load-more"))))
                .andExpect(content().string(containsString("21–21 of 21 articles")));
    }

    @Test
    @WithMockUser
    void emptyItemsPageHasNothingToMarkRead() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(1), eq(20))).thenReturn(List.of());
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(0L);
        when(feedService.listFeeds(UID)).thenReturn(List.of());

        mockMvc.perform(get("/items").param("unread", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No articles yet")))
                .andExpect(content().string(not(containsString("/items/mark-read"))))
                .andExpect(content().string(not(containsString("data-load-more"))));
    }

    @Test
    @WithMockUser
    void markingTheListReadMarksExactlyThePostedArticlesAndStartsAFreshUnreadList() throws Exception {
        when(articleService.markRead(eq(UID), anyList(), eq(true))).thenReturn(3);

        mockMvc.perform(post("/items/mark-read").with(csrf())
                        .param("feed", "5")
                        .param("unread", "true")
                        .param("snapshot", "100")
                        .param("page", "1").param("through", "2")
                        .param("id", "1", "2", "3", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/items?page=1&feed=5&unread=true"))
                .andExpect(flash().attribute("message", "Marked 3 articles as read"));

        verify(articleService).markRead(UID, List.of(1L, 2L, 3L), true);
    }

    @Test
    @WithMockUser
    void markingTheListReadIgnoresThePreferenceBecauseItWasAskedFor() throws Exception {
        when(userService.markReadOnNextPage(UID)).thenReturn(false);
        when(articleService.markRead(eq(UID), anyList(), eq(true))).thenReturn(1);

        mockMvc.perform(post("/items/mark-read").with(csrf())
                        .param("unread", "false").param("page", "1").param("through", "3")
                        .param("id", "9"))
                .andExpect(status().is3xxRedirection())
                // A list of everything comes back where it was, extended range and all.
                .andExpect(redirectedUrl("/items?page=1&through=3&unread=false"))
                .andExpect(flash().attribute("message", "Marked 1 article as read"));

        verify(articleService).markRead(UID, List.of(9L), true);
    }

    @Test
    @WithMockUser
    void markingAnEmptyListReadMarksNothingAndSaysSo() throws Exception {
        mockMvc.perform(post("/items/mark-read").with(csrf()).param("unread", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "Nothing left to mark as read"));

        verify(articleService, never()).markRead(eq(UID), anyList(), anyBoolean());
    }

    @Test
    @WithMockUser
    void markingTheListReadTakesNoMoreThanItsCap() throws Exception {
        String[] ids = new String[AppController.MAX_MARK_READ + 50];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = String.valueOf(i + 1);
        }
        when(articleService.markRead(eq(UID), anyList(), eq(true))).thenReturn(AppController.MAX_MARK_READ);

        mockMvc.perform(post("/items/mark-read").with(csrf()).param("unread", "false").param("id", ids))
                .andExpect(status().is3xxRedirection());

        org.mockito.ArgumentCaptor<List<Long>> marked = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(articleService).markRead(eq(UID), marked.capture(), eq(true));
        org.junit.jupiter.api.Assertions.assertEquals(AppController.MAX_MARK_READ, marked.getValue().size());
    }

    @Test
    @WithMockUser
    void markingTheListReadNeedsCsrf() throws Exception {
        mockMvc.perform(post("/items/mark-read").param("id", "1"))
                .andExpect(status().isForbidden());

        verify(articleService, never()).markRead(eq(UID), anyList(), anyBoolean());
    }

    @Test
    @WithMockUser
    void thePagedEndpointsAreGone() throws Exception {
        mockMvc.perform(post("/items/advance").with(csrf()).param("id", "1"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/items/read").with(csrf()).param("id", "1"))
                .andExpect(status().is4xxClientError());

        verify(articleService, never()).markRead(eq(UID), anyList(), anyBoolean());
    }

    /*
     * Loading and scrolling are not reading. Whatever the preference says, showing
     * the list never marks anything, and nothing on it does so behind the reader's
     * back: the one control that marks is the explicit button.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @WithMockUser
    void showingTheListNeverMarksAnythingRead(boolean keepTrack) throws Exception {
        when(userService.markReadOnNextPage(UID)).thenReturn(keepTrack);
        when(articleService.findPage(eq(UID), isNull(), isNull(), anyInt(), eq(20)))
                .thenReturn(List.of(new Article(4L, 1L, "guid-4", "Article 4", null, null,
                        null, null, null, null, false, null, null, null, "Example Feed")));
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(50L);
        when(feedService.listFeeds(UID)).thenReturn(List.of());

        mockMvc.perform(get("/items").param("unread", "false").param("through", "3"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">Mark all as read</button>")));
        mockMvc.perform(get("/items").param("unread", "false").param("page", "2").param("batch", "true"))
                .andExpect(status().isOk());

        verify(articleService, never()).markRead(eq(UID), anyList(), anyBoolean());
        verify(articleService, never()).markRead(eq(UID), anyLong(), anyBoolean());
    }

    @Test
    @WithMockUser
    void itemsPageCountsWhatIsStillUnreadInWords() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(List.of(new Article(4L, 1L, "guid-4", "Article 4", null, null,
                        null, null, null, null, false, null, null, null, "Example Feed")));
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(4L);
        // Counted without the snapshot: three of the four have been read.
        when(articleService.count(eq(UID), isNull(), isNull(), eq(Boolean.TRUE), isNull())).thenReturn(1L);
        when(feedService.listFeeds(UID)).thenReturn(List.of());

        mockMvc.perform(get("/items").param("unread", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">1 unread<")))
                .andExpect(content().string(not(containsString("unread-meter"))))
                .andExpect(content().string(not(containsString("width:"))))
                .andExpect(content().string(containsString("data-article-id=\"4\"")));
    }

    @Test
    @WithMockUser
    void theMarkReadButtonCarriesTheIdsOfTheRowsOnScreen() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(List.of(new Article(4L, 1L, "guid-4", "Article 4", null, null,
                        null, null, null, null, false, null, null, null, "Example Feed")));
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(1L);
        when(feedService.listFeeds(UID)).thenReturn(List.of());

        mockMvc.perform(get("/items").param("unread", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/items/mark-read\"")))
                .andExpect(content().string(containsString("name=\"id\" data-mark-read-id value=\"4\"")))
                // Entries open through their title; there is no per-row read toggle.
                .andExpect(content().string(not(containsString("/articles/4/read"))));
    }

    @Test
    @WithMockUser
    void aRowSaysItIsUnreadInWordsAsWellAsWithTheDot() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(List.of(
                        new Article(4L, 1L, "guid-4", "Fresh", null, null,
                                null, null, null, null, false, null, null, null, "Example Feed"),
                        new Article(5L, 1L, "guid-5", "Old", null, null,
                                null, null, null, null, true, null, null, null, "Example Feed")));
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(2L);
        when(feedService.listFeeds(UID)).thenReturn(List.of());

        String body = mockMvc.perform(get("/items").param("unread", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("aria-label=\"Send to Kindle: Fresh\"")))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertEquals(1,
                body.split("<span class=\"offscreen\">Unread: </span>", -1).length - 1,
                "only the unread row is announced as unread");
    }

    @Test
    @WithMockUser
    void listEntriesOpenThroughTheirTitleAndSendBackToTheList() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(Boolean.TRUE),
                eq(Instant.ofEpochMilli(100)), eq(1), eq(20)))
                .thenReturn(List.of(new Article(4L, 1L, "guid-4", "Article 4", null, null,
                        null, null, null, null, false, null, null, null, "Example Feed")));
        when(articleService.count(eq(UID), isNull(), isNull(), eq(Boolean.TRUE), eq(Instant.ofEpochMilli(100))))
                .thenReturn(1L);
        when(feedService.listFeeds(UID)).thenReturn(List.of());

        mockMvc.perform(get("/items").param("unread", "true").param("snapshot", "100"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<a class=\"item-title\" href=\"/articles/4\">")))
                .andExpect(content().string(not(containsString(">Read</a>"))))
                .andExpect(content().string(containsString(
                        "name=\"redirect\" value=\"/items?page=1&amp;unread=true&amp;snapshot=100\"")));
    }

    @Test
    @WithMockUser
    void unreadListGetsAStableSnapshotBeforeItIsShown() throws Exception {
        when(feedService.findById(UID, 5L)).thenReturn(Optional.of(
                new Feed(5L, "Android", "https://example.com/feed", "https://example.com",
                        null, null, null)));
        mockMvc.perform(get("/items").param("feed", "5").param("unread", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/items?page=1&feed=5&unread=true&snapshot=*"));
    }

    @Test
    @WithMockUser
    void sendingFromTheListReturnsToTheList() throws Exception {
        mockMvc.perform(post("/articles/4/send").with(csrf())
                        .param("redirect", "/items?unread=true&page=2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/items?unread=true&page=2"));

        verify(kindleMailService).sendToKindle(UID, 4L, false);
    }

    @Test
    @WithMockUser
    void sendingFromTheArticleStaysOnTheArticle() throws Exception {
        mockMvc.perform(post("/articles/4/send").with(csrf()).param("images", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/articles/4?images=true"));
    }

    @Test
    @WithMockUser
    void articleCanBeSentWithoutAFullPageRedirect() throws Exception {
        mockMvc.perform(post("/articles/4/send-async").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string(containsString("Sent to Kindle")));

        verify(kindleMailService).sendToKindle(UID, 4L, false);
    }

    /*
     * The page the send was started from stays put, so this answer is the only
     * thing the reader is ever told about a failure. It has to carry the reason.
     */
    @Test
    @WithMockUser
    void aFailedSendSaysWhyInsteadOfAnsweringWithNothing() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("Failed to send EPUB to Kindle: connection refused"))
                .when(kindleMailService).sendToKindle(UID, 4L, false);

        mockMvc.perform(post("/articles/4/send-async").with(csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error")
                        .value("Failed to send EPUB to Kindle: connection refused"));
    }

    /* A failure with no message of its own still gets a sentence to show. */
    @Test
    @WithMockUser
    void aFailedSendWithoutAMessageStillNamesTheProblem() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException())
                .when(kindleMailService).sendToKindle(UID, 4L, false);

        mockMvc.perform(post("/articles/4/send-async").with(csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Could not send article"));
    }

    /*
     * A missing Kindle address is the reader's to fix, and is marked as such so
     * that the banner can offer the settings page next to the sentence.
     */
    @Test
    @WithMockUser
    void aSendRefusedForWantOfSetupIsMarkedAsSomethingToPutRight() throws Exception {
        org.mockito.Mockito.doThrow(new KindleMailService.SetupRequiredException(
                        "Add your Kindle e-mail address in Settings first"))
                .when(kindleMailService).sendToKindle(UID, 4L, false);

        mockMvc.perform(post("/articles/4/send-async").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Add your Kindle e-mail address in Settings first"))
                .andExpect(jsonPath("$.setup").value(true));
    }

    @Test
    @WithMockUser
    void aSendForAnArticleThatIsGoneIsNotFound() throws Exception {
        org.mockito.Mockito.doThrow(new ArticleService.NotFoundException("Article not found"))
                .when(kindleMailService).sendToKindle(UID, 4L, false);

        mockMvc.perform(post("/articles/4/send-async").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Article not found"));
    }

    /*
     * Signed out, the send is answered with a redirect to the login page rather
     * than with JSON. fetch() follows it and reports a plain 200, so app.js
     * checks the content type before it believes a send happened; this pins the
     * behaviour that check is there for.
     */
    @Test
    void aSignedOutSendIsAnsweredWithTheLoginPageRatherThanJson() throws Exception {
        mockMvc.perform(post("/articles/4/send-async").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        verify(kindleMailService, never()).sendToKindle(anyLong(), anyLong(), anyBoolean());
    }

    /* The reader has to be able to see the banner, wherever the send started. */
    @Test
    @WithMockUser
    void theArticlePageCarriesASlotForSendFeedback() throws Exception {
        Article article = new Article(7L, 1L, "guid", "A story", "https://example.com/a", null,
                null, null, null, null, true, null, null, null, "Example Feed");
        when(articleService.findById(UID, 7L)).thenReturn(Optional.of(article));
        when(articleService.getContentHtml(any(Article.class), eq(false))).thenReturn("<p>Body</p>");

        mockMvc.perform(get("/articles/7"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-send-feedback")))
                .andExpect(content().string(containsString("data-send-settings-url=\"/settings/kindle\"")));
    }

    @Test
    @WithMockUser
    void theListPageCarriesASlotForSendFeedback() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(1), eq(20))).thenReturn(List.of());
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(0L);
        when(feedService.listFeeds(UID)).thenReturn(List.of());

        mockMvc.perform(get("/items").param("unread", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-send-feedback")))
                .andExpect(content().string(containsString("data-send-settings-url=\"/settings/kindle\"")));
    }

    @Test
    @WithMockUser
    void sendingCannotBeTalkedIntoLeavingTheApp() throws Exception {
        mockMvc.perform(post("/articles/4/send").with(csrf())
                        .param("redirect", "https://evil.example"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/items"));
    }

    @Test
    @WithMockUser
    void markingAFeedReadStaysOnTheCategoryAndReportsHowManyChanged() throws Exception {
        when(articleService.markFeedRead(UID, 5L)).thenReturn(3);

        mockMvc.perform(post("/feeds/5/read").with(csrf())
                        .param("redirect", "/?category=Technology"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?category=Technology"))
                .andExpect(flash().attribute("message", "3 articles marked as read"));

        verify(articleService).markFeedRead(UID, 5L);
    }

    @Test
    @WithMockUser
    void markingAFeedReadWhenNothingIsUnreadSaysSo() throws Exception {
        when(articleService.markFeedRead(UID, 5L)).thenReturn(0);

        mockMvc.perform(post("/feeds/5/read").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("message", "Nothing left to mark as read"));
    }

    @Test
    @WithMockUser
    void markingAMissingFeedReadSaysSo() throws Exception {
        when(articleService.markFeedRead(UID, 99L))
                .thenThrow(new ArticleService.NotFoundException("Feed not found"));

        mockMvc.perform(post("/feeds/99/read").with(csrf())
                        .param("redirect", "/?category=Technology"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?category=Technology"))
                .andExpect(flash().attribute("error", "Feed not found"));
    }

    @Test
    @WithMockUser
    void markingAFeedReadWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/feeds/5/read"))
                .andExpect(status().isForbidden());
        verify(articleService, never()).markFeedRead(anyLong(), anyLong());
    }

    @Test
    @WithMockUser
    void renamingACategoryUpdatesEveryFeedInIt() throws Exception {
        when(feedService.renameCategory(UID, "Technology", "Tech")).thenReturn(2);

        mockMvc.perform(post("/categories/rename").with(csrf())
                        .param("oldCategory", "Technology")
                        .param("newCategory", "Tech"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("message", "Renamed category for 2 feeds"));

        verify(feedService).renameCategory(UID, "Technology", "Tech");
    }

    @Test
    @WithMockUser
    void renamingACategoryToTheNameItAlreadyHasSaysSoInsteadOfClaimingItIsEmpty() throws Exception {
        when(feedService.renameCategory(UID, "Technology", "Technology"))
                .thenReturn(FeedService.CATEGORY_NAME_UNCHANGED);

        mockMvc.perform(post("/categories/rename").with(csrf())
                        .param("oldCategory", "Technology")
                        .param("newCategory", "Technology"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("message", "That is already the name of this category"));
    }

    @Test
    @WithMockUser
    void renamingUncategorizedIsRejected() throws Exception {
        when(feedService.renameCategory(UID, "Uncategorized", "Tech"))
                .thenThrow(new IllegalArgumentException("Choose a category to rename"));

        mockMvc.perform(post("/categories/rename").with(csrf())
                        .param("oldCategory", "Uncategorized")
                        .param("newCategory", "Tech"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("error", "Choose a category to rename"));
    }

    @Test
    void resolveCategoryPrefersATypedNewNameAndTreatsTheSentinelAsNone() {
        // A newly typed category wins over whatever the drop-down still shows.
        org.junit.jupiter.api.Assertions.assertEquals("Science",
                AppController.resolveCategory("__new__", "Science"));
        org.junit.jupiter.api.Assertions.assertEquals("Science",
                AppController.resolveCategory("Technology", "  Science  "));
        // The "New category" sentinel and the blank "Uncategorized" choice mean none.
        org.junit.jupiter.api.Assertions.assertNull(AppController.resolveCategory("__new__", null));
        org.junit.jupiter.api.Assertions.assertNull(AppController.resolveCategory("", ""));
        org.junit.jupiter.api.Assertions.assertNull(AppController.resolveCategory(null, null));
        // A plain drop-down choice is used as-is (trimmed).
        org.junit.jupiter.api.Assertions.assertEquals("Technology",
                AppController.resolveCategory(" Technology ", "  "));
    }

    @Test
    void safeRedirectRejectsOpenRedirects() {
        org.junit.jupiter.api.Assertions.assertEquals("/items", AppController.safeRedirect("https://evil.example"));
        org.junit.jupiter.api.Assertions.assertEquals("/items", AppController.safeRedirect("//evil.example"));
        org.junit.jupiter.api.Assertions.assertEquals("/items?feed=1", AppController.safeRedirect("/items?feed=1"));
        org.junit.jupiter.api.Assertions.assertNull(AppController.safeHttpUrl("javascript:alert(1)"));
        org.junit.jupiter.api.Assertions.assertEquals("https://example.com/a", AppController.safeHttpUrl("https://example.com/a"));
    }

    @Test
    @WithMockUser
    void homeOffersAPasteUrlFormOnTheFeedsView() throws Exception {
        when(feedService.listFeeds(UID)).thenReturn(List.of());
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Send a page to Kindle")))
                .andExpect(content().string(containsString("action=\"/articles/from-url\"")));

        mockMvc.perform(get("/").param("view", "add"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("action=\"/articles/from-url\""))));

        when(feedService.listFeeds(UID)).thenReturn(List.of(
                new Feed(5L, "Android", "https://example.com/feed", "https://example.com",
                        "Technology", null, null, null)));
        mockMvc.perform(get("/").param("category", "Technology"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("action=\"/articles/from-url\""))));
    }

    @Test
    @WithMockUser
    void itemsPageDoesNotOfferAPasteUrlForm() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(1), eq(20))).thenReturn(List.of());
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(0L);
        when(feedService.listFeeds(UID)).thenReturn(List.of());
        mockMvc.perform(get("/items").param("unread", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("action=\"/articles/from-url\""))));
    }

    @Test
    @WithMockUser
    void pastingAUrlImportsTheArticleAndSendsIt() throws Exception {
        Article imported = new Article(8L, 11L, "https://example.com/a", "A story",
                "https://example.com/a", null, Instant.now(), null, null, "<p>Hi</p>",
                false, null, Instant.now(), Instant.now(), "Pasted URLs");
        when(articleService.importFromUrl(UID, "https://example.com/a")).thenReturn(imported);

        mockMvc.perform(post("/articles/from-url").with(csrf())
                        .param("url", "https://example.com/a"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/articles/8"))
                .andExpect(flash().attribute("message", "Sent to Kindle"));

        verify(kindleMailService).sendToKindle(UID, 8L, false);
    }

    @Test
    @WithMockUser
    void aFailedImportDoesNotTryToSend() throws Exception {
        when(articleService.importFromUrl(UID, "https://example.com/missing"))
                .thenThrow(new IllegalArgumentException("Could not extract an article from that page"));

        mockMvc.perform(post("/articles/from-url").with(csrf())
                        .param("url", "https://example.com/missing"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("error", "Could not extract an article from that page"));

        verify(kindleMailService, never()).sendToKindle(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    @WithMockUser
    void aFailedSendStillKeepsTheImportedArticle() throws Exception {
        Article imported = new Article(8L, 11L, "https://example.com/a", "A story",
                "https://example.com/a", null, Instant.now(), null, null, "<p>Hi</p>",
                false, null, Instant.now(), Instant.now(), "Pasted URLs");
        when(articleService.importFromUrl(UID, "https://example.com/a")).thenReturn(imported);
        org.mockito.Mockito.doThrow(new IllegalStateException("Add your Kindle e-mail address in Settings first"))
                .when(kindleMailService).sendToKindle(UID, 8L, false);

        mockMvc.perform(post("/articles/from-url").with(csrf())
                        .param("url", "https://example.com/a"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/articles/8"))
                .andExpect(flash().attribute("error",
                        "Add your Kindle e-mail address in Settings first"));
    }
}
