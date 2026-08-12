package com.kindlerss.web;

import com.kindlerss.domain.AppUser;
import com.kindlerss.domain.Article;
import com.kindlerss.domain.Feed;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.security.RateLimiter;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.service.ArticleService;
import com.kindlerss.service.FeedService;
import com.kindlerss.service.KindleMailService;
import com.kindlerss.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

    @Autowired
    MockMvc mockMvc;

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
                .andExpect(view().name("login"));
    }

    @Test
    void registrationPageIsAccessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(view().name("register"));
    }

    @Test
    void registrationSubmitsAndRedirectsToLogin() throws Exception {
        mockMvc.perform(post("/register").with(csrf())
                        .param("email", "new@example.com")
                        .param("password", "supersecret"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        verify(userService).register("new@example.com", "supersecret");
    }

    @Test
    void verifyLinkRedirectsToLogin() throws Exception {
        when(userService.verifyEmail("tok")).thenReturn(true);
        mockMvc.perform(get("/verify").param("token", "tok"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
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
    }

    @Test
    @WithMockUser
    void homeRequiresAuthAndRenders() throws Exception {
        when(feedService.listFeeds(UID)).thenReturn(List.of());
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"));
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
                .andExpect(content().string(containsString("value=\"hacker-news\"")));

        when(feedService.listFeeds(UID)).thenReturn(List.of(
                new Feed(5L, "Android", "https://example.com/feed", "https://example.com",
                        "Technology", null, null, null)));
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Quick start"))))
                .andExpect(content().string(containsString("action=\"/feeds/5/category\"")))
                .andExpect(content().string(containsString(">Technology</h3>")));
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
        mockMvc.perform(post("/refresh").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
        verify(feedService).refreshForUser(UID);
    }

    @Test
    @WithMockUser
    void refreshComesBackToTheListItWasAskedFrom() throws Exception {
        mockMvc.perform(post("/refresh").with(csrf())
                        .param("redirect", "/items?page=2&feed=5&unread=true&snapshot=100"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/items?page=2&feed=5&unread=true&snapshot=100"));

        mockMvc.perform(post("/refresh").with(csrf()).param("redirect", "https://evil.example"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/items"));
    }

    @Test
    @WithMockUser
    void browsingRefreshesFeedsThatHaveGoneStale() throws Exception {
        when(feedService.listFeeds(UID)).thenReturn(List.of());
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(1), eq(20))).thenReturn(List.of());

        mockMvc.perform(get("/")).andExpect(status().isOk());
        mockMvc.perform(get("/items")).andExpect(status().isOk());

        verify(feedService, times(2)).refreshForUserIfStale(UID);
    }

    @Test
    @WithMockUser
    void theRefreshButtonOnAListStaysOnThatList() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), eq("Technology"), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(List.of());
        when(feedService.listFeeds(UID)).thenReturn(List.of());

        mockMvc.perform(get("/items").param("category", "Technology"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "name=\"redirect\" value=\"/items?page=1&amp;category=Technology\"")));
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
        mockMvc.perform(get("/items"))
                .andExpect(status().isOk())
                .andExpect(view().name("items"));
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
        mockMvc.perform(get("/items"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/items?category=Technology\"")))
                .andExpect(content().string(containsString("href=\"/items?category=Science\"")))
                .andExpect(content().string(not(containsString("Android Police"))));

        mockMvc.perform(get("/items").param("category", "Technology"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/items?feed=5\"")))
                .andExpect(content().string(containsString("Android Police")))
                .andExpect(content().string(not(containsString("Nature"))));
    }

    @Test
    @WithMockUser
    void everyFeedIsInTheRowAndTheRowCanBeTurned() throws Exception {
        List<Feed> feeds = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            feeds.add(new Feed((long) i, "A rather long feed name " + i, "https://example.com/" + i,
                    null, "Technology", null, null, null));
        }
        when(articleService.findPage(eq(UID), isNull(), eq("Technology"), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(List.of());
        when(articleService.count(eq(UID), isNull(), eq("Technology"), isNull(), isNull())).thenReturn(0L);
        when(feedService.listFeeds(UID)).thenReturn(feeds);

        // The whole row is rendered; how much of it fits is settled in the browser.
        mockMvc.perform(get("/items").param("category", "Technology"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/items?feed=1\"")))
                .andExpect(content().string(containsString("href=\"/items?feed=12\"")))
                .andExpect(content().string(containsString("data-strip-track")))
                .andExpect(content().string(containsString("data-strip-prev")))
                .andExpect(content().string(containsString("data-strip-next")))
                .andExpect(content().string(containsString("/js/filters.js")));
    }

    @Test
    @WithMockUser
    void feedsWithoutACategoryStayReachable() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), eq("Uncategorized"), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(List.of());
        when(articleService.count(eq(UID), isNull(), eq("Uncategorized"), isNull(), isNull())).thenReturn(0L);
        when(feedService.listFeeds(UID)).thenReturn(List.of(
                new Feed(9L, "Loose Feed", "https://example.com/l", null, null, null, null, null)));

        mockMvc.perform(get("/items").param("category", "Uncategorized"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/items?feed=9\"")))
                .andExpect(content().string(containsString("Loose Feed")));
    }

    @Test
    @WithMockUser
    void homePageShowsBuildIdentity() throws Exception {
        when(feedService.listFeeds(UID)).thenReturn(List.of());
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"build-info\"")))
                .andExpect(content().string(containsString("revision")));
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
    void articlePageRendersPagedReader() throws Exception {
        Article article = new Article(7L, 1L, "guid", "Paged article", "https://example.com/a", null,
                null, null, null, null, true, null, null, null, "Example Feed");
        when(articleService.findById(UID, 7L)).thenReturn(Optional.of(article));
        when(articleService.getContentHtml(any(Article.class), eq(false))).thenReturn("<p>Body</p>");

        mockMvc.perform(get("/articles/7"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-reader-frame")))
                .andExpect(content().string(containsString("data-reader-prev")))
                .andExpect(content().string(containsString("data-reader-next")))
                .andExpect(content().string(containsString("/js/reader.js")))
                .andExpect(content().string(containsString("<button class=\"btn\" type=\"submit\">Send to Kindle</button>")));
    }

    @Test
    @WithMockUser
    void anArticleCanBeLeftByAButtonForTheListItWasOpenedFrom() throws Exception {
        Article article = new Article(7L, 1L, "guid", "Paged article", "https://example.com/a", null,
                null, null, null, null, true, null, null, null, "Example Feed");
        when(articleService.findById(UID, 7L)).thenReturn(Optional.of(article));
        when(articleService.getContentHtml(any(Article.class), eq(false))).thenReturn("<p>Body</p>");

        mockMvc.perform(get("/articles/7").param("back", "/items?page=2&feed=5"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "href=\"/items?page=2&amp;feed=5\">Back</a>")))
                .andExpect(content().string(containsString("href=\"/\">Feeds</a>")));

        // Without one, and for anything pointing out of the app, Back is the article list.
        mockMvc.perform(get("/articles/7"))
                .andExpect(content().string(containsString("href=\"/items\">Back</a>")));
        mockMvc.perform(get("/articles/7").param("back", "https://evil.example"))
                .andExpect(content().string(containsString("href=\"/items\">Back</a>")));
    }

    @Test
    @WithMockUser
    void listEntriesTellTheArticleWhereItWasOpenedFrom() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(2), eq(20)))
                .thenReturn(List.of(new Article(4L, 1L, "guid-4", "Article 4", null, null,
                        null, null, null, null, false, null, null, null, "Example Feed")));
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(30L);
        when(feedService.listFeeds(UID)).thenReturn(List.of());

        mockMvc.perform(get("/items").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "href=\"/articles/4?back=/items?page%3D2\"")));
    }

    @Test
    @WithMockUser
    void itemsPageOffersBothMarkingAndPlainForwardNavigation() throws Exception {
        List<Article> articles = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            articles.add(new Article((long) i, 1L, "guid-" + i, "Article " + i, null, null,
                    null, null, null, null, false, null, null, null, "Example Feed"));
        }
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(1), eq(20))).thenReturn(articles);
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(33L);
        when(feedService.listFeeds(UID)).thenReturn(List.of());

        mockMvc.perform(get("/items"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("1–20 of 33 articles")))
                .andExpect(content().string(containsString("Mark read &amp; continue")))
                .andExpect(content().string(containsString("Older articles")))
                .andExpect(content().string(not(containsString("data-reader-prev-url"))));
    }

    @Test
    @WithMockUser
    void lastItemsPageOnlyLeadsBackwards() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(2), eq(20)))
                .thenReturn(List.of(new Article(21L, 1L, "guid-21", "Article 21", null, null,
                        null, null, null, null, false, null, null, null, "Example Feed")));
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(21L);
        when(feedService.listFeeds(UID)).thenReturn(List.of());

        mockMvc.perform(get("/items").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-reader-prev-url")))
                .andExpect(content().string(containsString("Mark these read")))
                .andExpect(content().string(not(containsString("Older articles"))))
                .andExpect(content().string(containsString("21–21 of 21 articles")));
    }

    @Test
    @WithMockUser
    void emptyItemsPageHasNothingToMarkRead() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(1), eq(20))).thenReturn(List.of());
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(0L);
        when(feedService.listFeeds(UID)).thenReturn(List.of());

        mockMvc.perform(get("/items"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("data-reader-next-form"))))
                .andExpect(content().string(not(containsString("/items/advance"))));
    }

    @Test
    @WithMockUser
    void advanceMarksThePostedArticlesReadAndMovesOn() throws Exception {
        when(articleService.markRead(eq(UID), anyList(), eq(true))).thenReturn(3);

        mockMvc.perform(post("/items/advance").with(csrf())
                        .param("page", "1")
                        .param("id", "1", "2", "3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/items?page=2#start"));

        verify(articleService).markRead(UID, List.of(1L, 2L, 3L), true);
    }

    @Test
    @WithMockUser
    void advanceOnAnUnreadListStaysOnTheSamePage() throws Exception {
        when(articleService.markRead(eq(UID), anyList(), eq(true))).thenReturn(20);

        // The unread list shrinks by the articles just marked, so what comes next
        // moves into the page that was posted from.
        mockMvc.perform(post("/items/advance").with(csrf())
                        .param("page", "2")
                        .param("unread", "true")
                        .param("feed", "5")
                        .param("id", "11", "12"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/items?page=2&feed=5&unread=true#start"));
    }

    @Test
    @WithMockUser
    void advanceWithoutArticlesMarksNothing() throws Exception {
        mockMvc.perform(post("/items/advance").with(csrf()).param("page", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/items?page=2#start"));

        verify(articleService, never()).markRead(eq(UID), anyList(), anyBoolean());
    }

    @Test
    @WithMockUser
    void itemsPagePostsItsArticleIdsWhenPagingForward() throws Exception {
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(List.of(new Article(4L, 1L, "guid-4", "Article 4", null, null,
                        null, null, null, null, false, null, null, null, "Example Feed")));
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(1L);
        when(feedService.listFeeds(UID)).thenReturn(List.of());

        mockMvc.perform(get("/items"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-reader-next-form=\"advance\"")))
                .andExpect(content().string(containsString("action=\"/items/advance\"")))
                .andExpect(content().string(containsString("name=\"id\" value=\"4\"")))
                .andExpect(content().string(containsString("Mark these read")))
                // Paging marks articles read, so entries carry no read/unread button.
                .andExpect(content().string(not(containsString("/articles/4/read"))));
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
                .andExpect(content().string(containsString(
                        "<a class=\"item-title\" href=\"/articles/4?back=/items?page%3D1%26unread%3Dtrue%26snapshot%3D100\">")))
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

    @Test
    @WithMockUser
    void sendingCannotBeTalkedIntoLeavingTheApp() throws Exception {
        mockMvc.perform(post("/articles/4/send").with(csrf())
                        .param("redirect", "https://evil.example"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/items"));
    }

    @Test
    void safeRedirectRejectsOpenRedirects() {
        org.junit.jupiter.api.Assertions.assertEquals("/items", AppController.safeRedirect("https://evil.example"));
        org.junit.jupiter.api.Assertions.assertEquals("/items", AppController.safeRedirect("//evil.example"));
        org.junit.jupiter.api.Assertions.assertEquals("/items?feed=1", AppController.safeRedirect("/items?feed=1"));
        org.junit.jupiter.api.Assertions.assertNull(AppController.safeHttpUrl("javascript:alert(1)"));
        org.junit.jupiter.api.Assertions.assertEquals("https://example.com/a", AppController.safeHttpUrl("https://example.com/a"));
    }
}
