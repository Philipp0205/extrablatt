package com.kindlerss.web;

import com.kindlerss.config.SecurityConfig;
import com.kindlerss.config.WebMvcConfig;
import com.kindlerss.domain.AppUser;
import com.kindlerss.domain.Article;
import com.kindlerss.domain.DisplayPreferences;
import com.kindlerss.domain.Feed;
import com.kindlerss.repository.DisplayPreferencesRepository;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.security.RateLimiter;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.service.ArticleHighlights;
import com.kindlerss.service.ArticleService;
import com.kindlerss.service.DisplayPreferencesService;
import com.kindlerss.service.FeedService;
import com.kindlerss.service.KindleMailService;
import com.kindlerss.service.ReadableTime;
import com.kindlerss.service.TopicCatalog;
import com.kindlerss.service.TopicService;
import com.kindlerss.service.UserService;
import jakarta.servlet.http.Cookie;
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
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The accessible edition end to end through MVC: which host gets which view, and
 * whether the pages a reader with low vision depends on actually render.
 */
@WebMvcTest(controllers = {AccessibleController.class, AppController.class, AuthController.class})
@Import({SecurityConfig.class, WebMvcConfig.class, GlobalExceptionHandler.class,
        RateLimiter.class, RateLimitingFilter.class, EditionResolver.class,
        DisplayPreferencesService.class, TopicCatalog.class, ArticleHighlights.class, ReadableTime.class})
@TestPropertySource(properties = {
        "app.mail-from=from@example.com",
        "app.remember-me-key=test-remember-key",
        "app.public-url=https://reader.extrablatt.app",
        "app.accessibility.domain=accessibility.extrablatt.app"
})
class AccessibleEditionTest {

    private static final long UID = 1L;
    private static final String ACCESSIBLE_HOST = "accessibility.extrablatt.app";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    FeedService feedService;

    @MockitoBean
    ArticleService articleService;

    @MockitoBean
    TopicService topicService;

    @MockitoBean
    KindleMailService kindleMailService;

    @MockitoBean
    UserService userService;

    @MockitoBean
    DisplayPreferencesRepository preferencesRepository;

    @MockitoBean
    CurrentUser currentUser;

    @MockitoBean
    UserDetailsService userDetailsService;

    @BeforeEach
    void signIn() {
        AppUser user = new AppUser(UID, "reader@example.com", "hash", null,
                Instant.now(), null, Instant.now(), Instant.now());
        when(currentUser.requireId()).thenReturn(UID);
        when(currentUser.details()).thenReturn(Optional.of(new AppUserDetails(user)));
        when(preferencesRepository.find(UID)).thenReturn(Optional.empty());
        when(feedService.listFeeds(UID)).thenReturn(List.of());
    }

    // ------------------------------------------------------------ routing

    @Test
    @WithMockUser
    void theAccessibleHostOpensOnTopicsInsteadOfTheKindleHome() throws Exception {
        mockMvc.perform(get("/").header("Host", ACCESSIBLE_HOST))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/topics"));
    }

    @Test
    @WithMockUser
    void theStandardHostIsUntouched() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"));
    }

    @Test
    @WithMockUser
    void oldReadingLinksLandOnTheirAccessibleEquivalent() throws Exception {
        mockMvc.perform(get("/items").param("category", "Clinical trials").header("Host", ACCESSIBLE_HOST))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/list?topic=Clinical+trials"));

        mockMvc.perform(get("/articles/7").header("Host", ACCESSIBLE_HOST))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/read/7"));
    }

    @Test
    void theLoginFormItselfIsServedInTheAccessibleEdition() throws Exception {
        mockMvc.perform(get("/login").header("Host", ACCESSIBLE_HOST))
                .andExpect(status().isOk())
                .andExpect(view().name("accessible/login"))
                .andExpect(content().string(containsString("/css/a11y.css")))
                .andExpect(content().string(containsString("Skip to the main content")))
                // Theme classes and a tiny inline theme land in the first document so
                // the page is never painted in the browser default before a11y.css.
                .andExpect(content().string(containsString("theme-black-bright size-3")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<head>.*--bg:\\s*#000000.*<link rel=\"stylesheet\"[^>]*a11y\\.css.*</head>.*")))
                .andExpect(content().string(containsString("action=\"/display/size\"")))
                .andExpect(content().string(containsString("Make the text bigger")))
                .andExpect(content().string(containsString("name=\"redirect\" value=\"/login\"")));

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"));
    }

    @Test
    void anyHostCanBeAskedForTheAccessibleEditionAndItIsRemembered() throws Exception {
        mockMvc.perform(get("/login").param("display", "accessible"))
                .andExpect(status().isOk())
                .andExpect(view().name("accessible/login"))
                .andExpect(cookie().value(EditionResolver.COOKIE, "accessible"));

        mockMvc.perform(get("/login").cookie(new Cookie(EditionResolver.COOKIE, "accessible")))
                .andExpect(view().name("accessible/login"));

        // And the choice can be taken back on the accessible host as well.
        mockMvc.perform(get("/login").header("Host", ACCESSIBLE_HOST)
                        .cookie(new Cookie(EditionResolver.COOKIE, "standard")))
                .andExpect(view().name("login"));
    }

    // ------------------------------------------------------------- topics

    @Test
    @WithMockUser
    void anEmptyAccountIsOfferedSubjectsRatherThanAnAddressBox() throws Exception {
        mockMvc.perform(get("/topics").header("Host", ACCESSIBLE_HOST))
                .andExpect(status().isOk())
                .andExpect(view().name("accessible/topics"))
                .andExpect(content().string(containsString("Blindness and low vision")))
                .andExpect(content().string(containsString("Clinical trials and medical research")))
                .andExpect(content().string(containsString("value=\"blindness\"")))
                // Nothing on this page asks anyone to find a feed URL.
                .andExpect(content().string(not(containsString("RSS/Atom"))))
                .andExpect(content().string(containsString("theme-black-bright size-3")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<head>.*--bg:\\s*#000000.*<link rel=\"stylesheet\"[^>]*a11y\\.css.*</head>.*")));
    }

    @Test
    @WithMockUser
    void followingATopicReportsWhatItDidInPlainWords() throws Exception {
        when(topicService.subscribe(UID, "blindness")).thenReturn(
                new TopicService.SubscribeResult("Blindness and low vision", 5, 0, List.of("RNIB")));

        mockMvc.perform(post("/topics/follow").with(csrf())
                        .header("Host", ACCESSIBLE_HOST)
                        .param("topic", "blindness"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/topics"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .flash().attribute("message",
                                containsString("Added 5 sources to Blindness and low vision")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .flash().attribute("note", containsString("RNIB")));
    }

    @Test
    @WithMockUser
    void followedTopicsAreListedWithTheirUnreadCounts() throws Exception {
        when(feedService.listFeeds(UID)).thenReturn(List.of(
                new Feed(1L, "AppleVis", "https://applevis.com/rss", null, "Blindness and low vision",
                        null, null, null, 4, null),
                new Feed(2L, "STAT News", "https://statnews.com/feed", null, "Clinical trials",
                        null, null, null, 2, null)));

        mockMvc.perform(get("/topics").header("Host", ACCESSIBLE_HOST))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Blindness and low vision")))
                .andExpect(content().string(containsString("<strong>6</strong>")))
                .andExpect(content().string(containsString("/list?topic=Clinical%20trials")));
    }

    // ----------------------------------------------------------- articles

    @Test
    @WithMockUser
    void anArticleListPinsItselfToTheMomentItWasOpened() throws Exception {
        mockMvc.perform(get("/list").header("Host", ACCESSIBLE_HOST))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/list?page=1&show=new&since=*"));
    }

    @Test
    @WithMockUser
    void theListShowsTitlesSourcesAndASaveButton() throws Exception {
        Article article = new Article(4L, 1L, "guid", "A trial result", "https://example.com/a", null,
                Instant.now().minusSeconds(7200), null, null, null, false, null, null, null, "STAT News", null);
        when(articleService.count(eq(UID), isNull(), isNull(), eq(Boolean.TRUE), any()))
                .thenReturn(1L);
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(Boolean.TRUE), any(), eq(1), eq(20)))
                .thenReturn(List.of(article));

        mockMvc.perform(get("/list").param("since", "100").header("Host", ACCESSIBLE_HOST))
                .andExpect(status().isOk())
                .andExpect(view().name("accessible/list"))
                .andExpect(content().string(containsString("A trial result")))
                .andExpect(content().string(containsString("STAT News")))
                .andExpect(content().string(containsString("2 hours ago")))
                .andExpect(content().string(containsString("/articles/4/save")));
    }

    @Test
    @WithMockUser
    void readingAnArticleLeadsWithItsOwnHeadingsAndBulletPoints() throws Exception {
        Article article = new Article(4L, 1L, "guid", "How the trial went", null, null,
                Instant.now(), null, null, null, false, null, null, null, "STAT News", null);
        when(articleService.findById(UID, 4L)).thenReturn(Optional.of(article));
        when(articleService.getContentHtml(any(Article.class), eq(false))).thenReturn("""
                <h2>What the study found</h2>
                <p>The trial enrolled six hundred people over two years at nine sites.</p>
                <ul><li>Vision improved in four in ten participants</li>
                    <li>No serious side effects were reported</li></ul>
                """);

        mockMvc.perform(get("/read/4").header("Host", ACCESSIBLE_HOST))
                .andExpect(status().isOk())
                .andExpect(view().name("accessible/article"))
                .andExpect(content().string(containsString("The main points")))
                .andExpect(content().string(containsString("What the study found")))
                .andExpect(content().string(containsString("Vision improved in four in ten participants")))
                .andExpect(content().string(containsString("Listen to this article")))
                // No Kindle button for an account that has never set a Kindle address.
                .andExpect(content().string(not(containsString("Send it to my Kindle"))));
    }

    @Test
    @WithMockUser
    void theMainPointsAreNotLabelledWithWhatKindOfPointTheyAre() throws Exception {
        // An article with no structure falls back to its lead sentences, which is
        // the common case — and used to label every line of the summary "Opening".
        Article article = new Article(4L, 1L, "guid", "How the trial went", null, null,
                Instant.now(), null, null, null, false, null, null, null, "STAT News", null);
        when(articleService.findById(UID, 4L)).thenReturn(Optional.of(article));
        when(articleService.getContentHtml(any(Article.class), eq(false))).thenReturn("""
                <p>The trial enrolled six hundred people over two years at nine sites in four countries,
                   and followed all of them for a further year afterwards.</p>
                <p>Half of them were given the treatment and the other half were given a placebo instead,
                   without either the patients or their doctors knowing which was which.</p>
                """);

        mockMvc.perform(get("/read/4").header("Host", ACCESSIBLE_HOST))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The main points")))
                .andExpect(content().string(containsString("The trial enrolled six hundred people")))
                .andExpect(content().string(not(containsString("point-kind"))))
                .andExpect(content().string(not(containsString(">Opening<"))));
    }

    @Test
    @WithMockUser
    void keyPointsCanBeTurnedOff() throws Exception {
        Article article = new Article(4L, 1L, "guid", "How the trial went", null, null, null,
                null, null, null, true, null, null, null, "STAT News", null);
        when(articleService.findById(UID, 4L)).thenReturn(Optional.of(article));
        when(articleService.getContentHtml(any(Article.class), anyBoolean()))
                .thenReturn("<h2>A heading</h2><p>Some text.</p>");

        DisplayPreferences withoutPoints = new DisplayPreferences(
                DisplayPreferences.Theme.BLACK_BRIGHT, 3, 2, DisplayPreferences.Font.SANS, false, false);
        mockMvc.perform(get("/read/4").header("Host", ACCESSIBLE_HOST)
                        .cookie(new Cookie(DisplayPreferencesService.COOKIE, withoutPoints.encode())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("The main points"))));
    }

    @Test
    @WithMockUser
    void savingAnArticleWithoutLeavingThePageAnswersInJson() throws Exception {
        when(articleService.setSaved(UID, 4L, true)).thenReturn(null);

        mockMvc.perform(post("/articles/4/save-async").with(csrf())
                        .header("Host", ACCESSIBLE_HOST)
                        .param("saved", "true"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().string(containsString("Saved")));

        verify(articleService).setSaved(UID, 4L, true);
    }

    @Test
    @WithMockUser
    void aMissingArticleGetsTheAccessibleErrorPage() throws Exception {
        when(articleService.findById(anyLong(), anyLong())).thenReturn(Optional.empty());

        mockMvc.perform(get("/read/99").header("Host", ACCESSIBLE_HOST))
                .andExpect(status().isNotFound())
                .andExpect(view().name("accessible/error"))
                .andExpect(content().string(containsString("/css/a11y.css")))
                .andExpect(content().string(containsString("nothing you did caused this")));
    }

    // ------------------------------------------------------------ display

    @Test
    @WithMockUser
    void theTextSizeButtonsChangeTheSettingAndComeBackToThePage() throws Exception {
        var result = mockMvc.perform(post("/display/size").with(csrf())
                        .header("Host", ACCESSIBLE_HOST)
                        .param("step", "bigger")
                        .param("redirect", "/list?page=1&show=new"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/list?page=1&show=new"))
                .andReturn();

        Cookie saved = result.getResponse().getCookie(DisplayPreferencesService.COOKIE);
        assertNotNull(saved);
        assertEquals(DisplayPreferences.DEFAULTS.textSize() + 1,
                DisplayPreferences.decode(saved.getValue()).textSize());
        verify(preferencesRepository).save(eq(UID), any(DisplayPreferences.class));
    }

    @Test
    void theTextSizeButtonsOnTheLoginFormWorkWithoutSigningIn() throws Exception {
        // The cookie is the point of display preferences: the login form itself must
        // already arrive in the reader's chosen size. Nobody is signed in yet, so the
        // account copy must not be touched.
        when(currentUser.details()).thenReturn(Optional.empty());

        var result = mockMvc.perform(post("/display/size").with(csrf())
                        .header("Host", ACCESSIBLE_HOST)
                        .param("step", "bigger")
                        .param("redirect", "/login"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"))
                .andReturn();

        Cookie saved = result.getResponse().getCookie(DisplayPreferencesService.COOKIE);
        assertNotNull(saved);
        assertEquals(DisplayPreferences.DEFAULTS.textSize() + 1,
                DisplayPreferences.decode(saved.getValue()).textSize());
        verify(preferencesRepository, never()).save(anyLong(), any(DisplayPreferences.class));
    }

    @Test
    @WithMockUser
    void chosenSettingsSurviveIntoTheMarkupOfEveryPage() throws Exception {
        DisplayPreferences yellow = new DisplayPreferences(
                DisplayPreferences.Theme.BLACK_YELLOW, 5, 3, DisplayPreferences.Font.SERIF, true, true);

        mockMvc.perform(get("/display").header("Host", ACCESSIBLE_HOST)
                        .cookie(new Cookie(DisplayPreferencesService.COOKIE, yellow.encode())))
                .andExpect(status().isOk())
                .andExpect(view().name("accessible/display"))
                .andExpect(content().string(
                        containsString("theme-black-yellow size-5 lines-3 font-serif letters-wide")));
    }

    @Test
    @WithMockUser
    void theWayOutOfTheAccessibleHostIsTheStandardHost() throws Exception {
        // Not ?display=standard: that would leave this host serving the standard
        // edition for a year, to the one reader who came here to avoid it.
        mockMvc.perform(get("/topics").header("Host", ACCESSIBLE_HOST))
                .andExpect(content().string(containsString("href=\"https://reader.extrablatt.app\"")))
                .andExpect(content().string(not(containsString("display=standard"))));
    }

    @Test
    void theHelpPageCanBeReadBeforeSigningUp() throws Exception {
        mockMvc.perform(get("/help").header("Host", ACCESSIBLE_HOST))
                .andExpect(status().isOk())
                .andExpect(view().name("accessible/help"))
                .andExpect(content().string(containsString("You do not need to know anything about feeds")));
    }

    @Test
    @WithMockUser
    void topicsDoesNotOfferAPasteUrlForm() throws Exception {
        mockMvc.perform(get("/topics").header("Host", ACCESSIBLE_HOST))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("action=\"/articles/from-url\""))));
    }

    @Test
    @WithMockUser
    void pastingAUrlFromTheAccessibleEditionOpensTheAccessibleArticle() throws Exception {
        Article imported = new Article(8L, 11L, "https://example.com/a", "A story",
                "https://example.com/a", null, Instant.now(), null, null, "<p>Hi</p>",
                false, null, Instant.now(), Instant.now(), "Pasted URLs");
        when(articleService.importFromUrl(UID, "https://example.com/a")).thenReturn(imported);

        mockMvc.perform(post("/articles/from-url").with(csrf())
                        .header("Host", ACCESSIBLE_HOST)
                        .param("url", "https://example.com/a"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/read/8"));

        verify(kindleMailService).sendToKindle(UID, 8L, false);
    }
}
