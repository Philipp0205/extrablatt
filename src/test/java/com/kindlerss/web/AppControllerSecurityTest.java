package com.kindlerss.web;

import com.kindlerss.domain.Article;
import com.kindlerss.service.ArticleService;
import com.kindlerss.service.FeedService;
import com.kindlerss.service.KindleMailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
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

@WebMvcTest(controllers = AppController.class)
@Import({com.kindlerss.config.SecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "app.password=test-password-123",
        "app.kindle-email=kindle@example.com",
        "app.mail-from=from@example.com",
        "app.remember-me-key=test-remember-key",
        "app.articles.page-size=20"
})
class AppControllerSecurityTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    FeedService feedService;

    @MockitoBean
    ArticleService articleService;

    @MockitoBean
    KindleMailService kindleMailService;

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
    void formLoginSucceedsWithConfiguredPassword() throws Exception {
        mockMvc.perform(formLogin().user("kindle").password("test-password-123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    @WithMockUser(username = "kindle")
    void homeRequiresAuthAndRenders() throws Exception {
        when(feedService.listFeeds()).thenReturn(List.of());
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"));
    }

    @Test
    @WithMockUser(username = "kindle")
    void postWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/refresh"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "kindle")
    void refreshWithCsrfWorks() throws Exception {
        doNothing().when(feedService).refreshAll();
        mockMvc.perform(post("/refresh").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
        verify(feedService).refreshAll();
    }

    @Test
    @WithMockUser(username = "kindle")
    void missingArticleReturns404() throws Exception {
        when(articleService.findById(99L)).thenReturn(Optional.empty());
        mockMvc.perform(get("/articles/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "kindle")
    void itemsPageRenders() throws Exception {
        when(articleService.findPage(isNull(), isNull(), eq(1), eq(20))).thenReturn(List.of());
        when(articleService.count(isNull(), isNull())).thenReturn(0L);
        when(feedService.listFeeds()).thenReturn(List.of());
        mockMvc.perform(get("/items"))
                .andExpect(status().isOk())
                .andExpect(view().name("items"));
    }

    @Test
    @WithMockUser(username = "kindle")
    void homePageShowsBuildIdentity() throws Exception {
        when(feedService.listFeeds()).thenReturn(List.of());
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
    @WithMockUser(username = "kindle")
    void articlePageRendersPagedReader() throws Exception {
        Article article = new Article(7L, 1L, "guid", "Paged article", "https://example.com/a", null,
                null, null, null, null, true, null, null, null, "Example Feed");
        when(articleService.findById(7L)).thenReturn(Optional.of(article));
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
    @WithMockUser(username = "kindle")
    void itemsPageOffersBothMarkingAndPlainForwardNavigation() throws Exception {
        List<Article> articles = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            articles.add(new Article((long) i, 1L, "guid-" + i, "Article " + i, null, null,
                    null, null, null, null, false, null, null, null, "Example Feed"));
        }
        when(articleService.findPage(isNull(), isNull(), eq(1), eq(20))).thenReturn(articles);
        when(articleService.count(isNull(), isNull())).thenReturn(33L);
        when(feedService.listFeeds()).thenReturn(List.of());

        mockMvc.perform(get("/items"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("1–20 of 33 articles")))
                .andExpect(content().string(containsString("Mark read &amp; continue")))
                .andExpect(content().string(containsString("Older articles")))
                .andExpect(content().string(not(containsString("data-reader-prev-url"))));
    }

    @Test
    @WithMockUser(username = "kindle")
    void lastItemsPageOnlyLeadsBackwards() throws Exception {
        when(articleService.findPage(isNull(), isNull(), eq(2), eq(20)))
                .thenReturn(List.of(new Article(21L, 1L, "guid-21", "Article 21", null, null,
                        null, null, null, null, false, null, null, null, "Example Feed")));
        when(articleService.count(isNull(), isNull())).thenReturn(21L);
        when(feedService.listFeeds()).thenReturn(List.of());

        mockMvc.perform(get("/items").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-reader-prev-url")))
                .andExpect(content().string(containsString("Mark these read")))
                .andExpect(content().string(not(containsString("Older articles"))))
                .andExpect(content().string(containsString("21–21 of 21 articles")));
    }

    @Test
    @WithMockUser(username = "kindle")
    void emptyItemsPageHasNothingToMarkRead() throws Exception {
        when(articleService.findPage(isNull(), isNull(), eq(1), eq(20))).thenReturn(List.of());
        when(articleService.count(isNull(), isNull())).thenReturn(0L);
        when(feedService.listFeeds()).thenReturn(List.of());

        mockMvc.perform(get("/items"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("data-reader-next-form"))))
                .andExpect(content().string(not(containsString("/items/advance"))));
    }

    @Test
    @WithMockUser(username = "kindle")
    void advanceMarksThePostedArticlesReadAndMovesOn() throws Exception {
        when(articleService.markRead(anyList(), eq(true))).thenReturn(3);

        mockMvc.perform(post("/items/advance").with(csrf())
                        .param("page", "1")
                        .param("id", "1", "2", "3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/items?page=2#start"));

        verify(articleService).markRead(List.of(1L, 2L, 3L), true);
    }

    @Test
    @WithMockUser(username = "kindle")
    void advanceOnAnUnreadListStaysOnTheSamePage() throws Exception {
        when(articleService.markRead(anyList(), eq(true))).thenReturn(20);

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
    @WithMockUser(username = "kindle")
    void advanceWithoutArticlesMarksNothing() throws Exception {
        mockMvc.perform(post("/items/advance").with(csrf()).param("page", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/items?page=2#start"));

        verify(articleService, never()).markRead(anyList(), anyBoolean());
    }

    @Test
    @WithMockUser(username = "kindle")
    void itemsPagePostsItsArticleIdsWhenPagingForward() throws Exception {
        when(articleService.findPage(isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(List.of(new Article(4L, 1L, "guid-4", "Article 4", null, null,
                        null, null, null, null, false, null, null, null, "Example Feed")));
        when(articleService.count(isNull(), isNull())).thenReturn(1L);
        when(feedService.listFeeds()).thenReturn(List.of());

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
    @WithMockUser(username = "kindle")
    void listEntriesOpenThroughTheirTitleAndSendBackToTheList() throws Exception {
        when(articleService.findPage(isNull(), isNull(), eq(Boolean.TRUE),
                eq(Instant.ofEpochMilli(100)), eq(1), eq(20)))
                .thenReturn(List.of(new Article(4L, 1L, "guid-4", "Article 4", null, null,
                        null, null, null, null, false, null, null, null, "Example Feed")));
        when(articleService.count(isNull(), isNull(), eq(Boolean.TRUE), eq(Instant.ofEpochMilli(100))))
                .thenReturn(1L);
        when(feedService.listFeeds()).thenReturn(List.of());

        mockMvc.perform(get("/items").param("unread", "true").param("snapshot", "100"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<a class=\"item-title\" href=\"/articles/4\">")))
                .andExpect(content().string(not(containsString(">Read</a>"))))
                .andExpect(content().string(containsString(
                        "name=\"redirect\" value=\"/items?page=1&amp;unread=true&amp;snapshot=100\"")));
    }

    @Test
    @WithMockUser(username = "kindle")
    void unreadListGetsAStableSnapshotBeforeItIsShown() throws Exception {
        mockMvc.perform(get("/items").param("feed", "5").param("unread", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/items?page=1&feed=5&unread=true&snapshot=*"));
    }

    @Test
    @WithMockUser(username = "kindle")
    void sendingFromTheListReturnsToTheList() throws Exception {
        mockMvc.perform(post("/articles/4/send").with(csrf())
                        .param("redirect", "/items?unread=true&page=2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/items?unread=true&page=2"));

        verify(kindleMailService).sendToKindle(4L, false);
    }

    @Test
    @WithMockUser(username = "kindle")
    void sendingFromTheArticleStaysOnTheArticle() throws Exception {
        mockMvc.perform(post("/articles/4/send").with(csrf()).param("images", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/articles/4?images=true"));
    }

    @Test
    @WithMockUser(username = "kindle")
    void articleCanBeSentWithoutAFullPageRedirect() throws Exception {
        mockMvc.perform(post("/articles/4/send-async").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string(containsString("Sent to Kindle")));

        verify(kindleMailService).sendToKindle(4L, false);
    }

    @Test
    @WithMockUser(username = "kindle")
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
