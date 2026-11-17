package com.kindlerss.web;

import com.kindlerss.domain.AppUser;
import com.kindlerss.domain.Article;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.security.RateLimiter;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.service.AdminTelemetryService;
import com.kindlerss.service.ApiTokenService;
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
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
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
import static org.hamcrest.Matchers.hasItem;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The e-ink layout: an e-ink Kindle gets html.eink, eink.css and the reader scripts,
 * every other device the scrolling page with none of it, and the {@code layout}
 * cookie and parameter override the User-Agent both ways.
 */
@WebMvcTest(controllers = {AppController.class, AuthController.class, SettingsController.class})
@Import({com.kindlerss.config.SecurityConfig.class, GlobalExceptionHandler.class,
        RateLimiter.class, RateLimitingFilter.class})
@TestPropertySource(properties = {
        "app.mail-from=from@example.com",
        "app.remember-me-key=test-remember-key"
})
class EinkLayoutTest {

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
    ApiTokenService apiTokenService;

    @MockitoBean
    com.kindlerss.service.RecoveryCodeService recoveryCodeService;

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

    static final String PAPERWHITE = "Mozilla/5.0 (X11; U; Linux armv7l like Android; en-us) AppleWebKit/531.2+ "
            + "(KHTML, like Gecko) Version/5.0 Safari/533.2+ Kindle/3.0+";
    static final String KINDLE_CHROMIUM = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/130.0.0.0 Mobile Safari/537.36 Kindle/3.0+";
    static final String PIXEL = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/130.0.0.0 Mobile Safari/537.36";
    static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1";
    static final String DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/130.0.0.0 Safari/537.36";
    static final String FIRE = "Mozilla/5.0 (Linux; Android 11; KFTRWI) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Silk/130.1.1 like Chrome/130.0.0.0 Safari/537.36";

    private static final String EINK_HTML = "<html lang=\"en\" class=\"eink\">";
    private static final String PLAIN_HTML = "<html lang=\"en\">";

    private void stubArticle() {
        Article article = new Article(7L, 1L, "guid", "An article", "https://example.com/a", null,
                null, null, null, null, true, null, null, null, "Example Feed");
        when(articleService.findById(UID, 7L)).thenReturn(Optional.of(article));
        when(articleService.getReaderHtml(any(Article.class), eq(false))).thenReturn("<p>Body</p>");
    }

    @Test
    @WithMockUser
    void theEinkListIsNeverArmedToMarkReadByScrolling() throws Exception {
        when(userService.markReadOnScroll(UID)).thenReturn(true);
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(1), anyInt()))
                .thenReturn(List.of(new Article(4L, 1L, "guid-4", "Article 4", null, null,
                        null, null, null, null, false, null, null, null, "Example Feed")));
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(1L);

        mockMvc.perform(get("/items?unread=false").header(HttpHeaders.USER_AGENT, PAPERWHITE))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-article-id=\"4\"")))
                .andExpect(content().string(not(containsString("data-scroll-read"))));
        mockMvc.perform(get("/items?unread=false").header(HttpHeaders.USER_AGENT, PIXEL))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-scroll-read=\"/items/scroll-read\"")));
    }

    private void stubQuietList() {
        Instant now = Instant.now();
        when(articleService.findPage(eq(UID), isNull(), isNull(), eq(1), anyInt()))
                .thenReturn(List.of(
                        new Article(1L, 1L, "g1", "Unread unsent", null, null, now, null, null, null,
                                false, null, null, null, "Example Feed"),
                        new Article(2L, 1L, "g2", "Read and sent", null, null, now, null, null, null,
                                true, now, null, null, "Example Feed")));
        when(articleService.count(eq(UID), isNull(), isNull())).thenReturn(2L);
    }

    /** The row for one article, from its opening li up to the next one. */
    private static String row(String page, long id) {
        int start = page.indexOf("data-article-id=\"" + id + "\"");
        assertTrue(start >= 0, "row " + id + " is in the list");
        int end = page.indexOf("data-article-id=\"", start + 10);
        return page.substring(start, end < 0 ? page.length() : end);
    }

    @Test
    @WithMockUser
    void theEinkListMarksOnlyUnreadRowsWithTheSquareAndPutsTheTitleFirst() throws Exception {
        stubQuietList();
        String page = mockMvc.perform(get("/items?unread=false").header(HttpHeaders.USER_AGENT, PAPERWHITE))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String unread = row(page, 1);
        String read = row(page, 2);
        assertTrue(unread.contains("class=\"unread-square\" aria-hidden=\"true\""), "unread row has the square");
        assertTrue(unread.contains("Unread: "), "screen readers still hear Unread");
        assertTrue(unread.indexOf("item-title") < unread.indexOf("item-meta"), "title comes before the meta line");
        assertFalse(read.contains("unread-square"), "a read row has no square");
        assertFalse(read.contains("Unread: "), "a read row is not announced as unread");
    }

    @Test
    @WithMockUser
    void aSentArticleOnEinkSaysSentOnTheButtonAndStillSendsAgain() throws Exception {
        stubQuietList();
        String page = mockMvc.perform(get("/items?unread=false").header(HttpHeaders.USER_AGENT, PAPERWHITE))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sent = row(page, 2);
        assertTrue(sent.contains("Sent ✓"), "the button reads Sent ✓");
        assertTrue(sent.contains("aria-label=\"Send to Kindle again: Read and sent\""), "the label says it sends again");
        assertTrue(sent.contains("action=\"/articles/2/send\"") && sent.contains("method=\"post\"")
                && sent.contains("type=\"submit\"") && sent.contains("data-send-url=\"/articles/2/send-async\""),
                "it is still a working send form");
        assertFalse(sent.contains("class=\"sent-mark\""), "no separate Sent tag on e-ink");

        String unsent = row(page, 1);
        assertTrue(unsent.contains(">Send</span>"), "an unsent article reads Send");
        assertTrue(unsent.contains("aria-label=\"Send to Kindle: Unread unsent\""));
        assertFalse(unsent.contains("Sent ✓"));
    }

    @Test
    @WithMockUser
    void aPhoneKeepsTheOldRowWithTheSentTagAndNoSquare() throws Exception {
        stubQuietList();
        String page = mockMvc.perform(get("/items?unread=false").header(HttpHeaders.USER_AGENT, PIXEL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String unread = row(page, 1);
        String sent = row(page, 2);
        assertFalse(unread.contains("unread-square"), "no e-ink square on a phone");
        assertTrue(unread.indexOf("item-meta") < unread.indexOf("item-title"), "meta line stays above the title");
        assertTrue(sent.contains("class=\"sent-mark\">Sent</span>"), "the Sent tag stays on a phone");
        assertFalse(sent.contains("Sent ✓"));
        assertTrue(sent.contains("aria-label=\"Send to Kindle: Read and sent\""));
    }

    @Test
    @WithMockUser
    void theEinkListChecksOutWithTheForcedLayoutParameterToo() throws Exception {
        stubQuietList();
        mockMvc.perform(get("/items?unread=false&layout=kindle").header(HttpHeaders.USER_AGENT, PIXEL))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"unread-square\"")))
                .andExpect(content().string(containsString("Sent ✓")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/items?unread=false", "/articles/7", "/", "/settings"})
    @WithMockUser
    void aKindleGetsTheEinkLayout(String path) throws Exception {
        stubArticle();
        mockMvc.perform(get(path).header(HttpHeaders.USER_AGENT, PAPERWHITE))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(EINK_HTML)))
                .andExpect(content().string(matchesPattern("(?s).*/css/eink-[0-9a-f]+\\.css.*")))
                .andExpect(content().string(matchesPattern("(?s).*/js/reader-boot-[0-9a-f]+\\.js\"></script>.*")))
                .andExpect(content().string(matchesPattern("(?s).*/js/reader-[0-9a-f]+\\.js\" defer></script>.*")));
    }

    @Test
    void theSignedOutPagesToo() throws Exception {
        for (String ua : List.of(PAPERWHITE, KINDLE_CHROMIUM)) {
            mockMvc.perform(get("/login").header(HttpHeaders.USER_AGENT, ua))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(EINK_HTML)))
                    .andExpect(content().string(containsString("/css/eink-")));
        }
    }

    /** The e-ink layout loads nothing from the app-kit, neither CSS nor JS. */
    @Test
    void theEinkLayoutLoadsNothingFromTheAppKit() throws Exception {
        for (String ua : List.of(PAPERWHITE, KINDLE_CHROMIUM)) {
            String page = mockMvc.perform(get("/login").header(HttpHeaders.USER_AGENT, ua))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertTrue(page.contains(EINK_HTML), "an e-ink page");
            assertFalse(page.contains("/app-kit/"), "no app-kit file for " + ua);
        }
        String forced = mockMvc.perform(get("/login?layout=kindle").header(HttpHeaders.USER_AGENT, PIXEL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(forced.contains("/app-kit/"), "no app-kit file with ?layout=kindle");
    }

    /** Phones and desktops get the kit's stylesheets and its two deferred scripts. */
    @ParameterizedTest
    @ValueSource(strings = {PIXEL, IPHONE, DESKTOP, ""})
    void theScrollingLayoutsLoadTheAppKit(String ua) throws Exception {
        mockMvc.perform(get("/login").header(HttpHeaders.USER_AGENT, ua))
                .andExpect(status().isOk())
                .andExpect(content().string(matchesPattern("(?s).*/app-kit/ak-tokens-[0-9a-f]+\\.css.*")))
                .andExpect(content().string(matchesPattern("(?s).*/app-kit/ak-components-[0-9a-f]+\\.css.*")))
                .andExpect(content().string(matchesPattern("(?s).*/app-kit/app-kit-[0-9a-f]+\\.css.*")))
                .andExpect(content().string(matchesPattern("(?s).*/app-kit/install-prompt-[0-9a-f]+\\.css.*")))
                .andExpect(content().string(matchesPattern("(?s).*/app-kit/theme-color-[0-9a-f]+\\.js\" defer></script>.*")))
                .andExpect(content().string(matchesPattern("(?s).*/app-kit/install-prompt-[0-9a-f]+\\.js\" defer></script>.*")))
                .andExpect(content().string(matchesPattern("(?s).*/js/app-[0-9a-f]+\\.js\" defer></script>\\s*</head>.*")));
    }

    @ParameterizedTest
    @ValueSource(strings = {PIXEL, IPHONE, DESKTOP, FIRE, ""})
    @WithMockUser
    void everythingElseGetsTheScrollingPageUnchanged(String ua) throws Exception {
        stubArticle();
        for (String path : List.of("/items?unread=false", "/articles/7", "/login")) {
            String page = mockMvc.perform(get(path).header(HttpHeaders.USER_AGENT, ua))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertTrue(page.contains(PLAIN_HTML), path + " keeps the plain <html>");
            assertFalse(page.contains("eink"), path + " carries nothing of the e-ink layout");
            assertFalse(page.contains("/js/reader"), path + " loads no reader script");
            assertTrue(page.contains("defer></script>\n</head>") || page.contains("defer></script>\n"),
                    path + " ends the shared assets on the app.js line");
        }
    }

    @Test
    void theCookieOverridesTheUserAgentBothWays() throws Exception {
        mockMvc.perform(get("/login").header(HttpHeaders.USER_AGENT, PIXEL).cookie(new Cookie("layout", "eink")))
                .andExpect(content().string(containsString(EINK_HTML)));
        mockMvc.perform(get("/login").header(HttpHeaders.USER_AGENT, PAPERWHITE).cookie(new Cookie("layout", "scroll")))
                .andExpect(content().string(containsString(PLAIN_HTML)))
                .andExpect(content().string(not(containsString("eink"))));
        mockMvc.perform(get("/login").header(HttpHeaders.USER_AGENT, PIXEL).cookie(new Cookie("layout", "eink-auto")))
                .andExpect(content().string(containsString(EINK_HTML)));
        mockMvc.perform(get("/login").header(HttpHeaders.USER_AGENT, PIXEL).cookie(new Cookie("layout", "bogus")))
                .andExpect(content().string(containsString(PLAIN_HTML)));
    }

    @Test
    void theParameterSetsAndClearsTheCookie() throws Exception {
        String set = mockMvc.perform(get("/login?layout=kindle").header(HttpHeaders.USER_AGENT, PIXEL))
                .andExpect(content().string(containsString(EINK_HTML)))
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(set.startsWith("layout=eink;"), set);
        assertTrue(set.contains("Path=/") && set.contains("SameSite=Lax") && set.contains("Max-Age=31536000"), set);
        assertFalse(set.contains("HttpOnly"), set);
        assertFalse(set.contains("Secure"), set);

        String scroll = mockMvc.perform(get("/login?layout=scroll").header(HttpHeaders.USER_AGENT, PAPERWHITE)
                        .cookie(new Cookie("layout", "eink")))
                .andExpect(content().string(containsString(PLAIN_HTML)))
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(scroll.startsWith("layout=scroll;"), scroll);

        String cleared = mockMvc.perform(get("/login?layout=auto").header(HttpHeaders.USER_AGENT, PAPERWHITE)
                        .cookie(new Cookie("layout", "scroll")))
                .andExpect(content().string(containsString(EINK_HTML)))
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(cleared.startsWith("layout=;") && cleared.contains("Max-Age=0"), cleared);

        mockMvc.perform(get("/login?layout=sideways").header(HttpHeaders.USER_AGENT, PIXEL))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(content().string(containsString(PLAIN_HTML)));
    }

    @Test
    void theCookieIsSecureOverHttps() throws Exception {
        String set = mockMvc.perform(get("/login?layout=eink").secure(true).header(HttpHeaders.USER_AGENT, PIXEL))
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(set.contains("Secure"), set);
    }

    @Test
    @WithMockUser
    void aPostNeitherReadsNorWritesTheLayout() throws Exception {
        mockMvc.perform(post("/items/mark-read?layout=eink").with(csrf()).header(HttpHeaders.USER_AGENT, PIXEL))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void pagesVaryOnUserAgentAndCookie() throws Exception {
        mockMvc.perform(get("/login").header(HttpHeaders.USER_AGENT, PIXEL))
                .andExpect(header().stringValues(HttpHeaders.VARY, hasItem(containsString("User-Agent, Cookie"))));
    }

    @Test
    @WithMockUser
    void settingsDoesNotOfferAPageLayoutPicker() throws Exception {
        mockMvc.perform(get("/settings/reading").header(HttpHeaders.USER_AGENT, PAPERWHITE)
                        .cookie(new Cookie("layout", "scroll")))
                .andExpect(content().string(not(containsString("?layout="))))
                .andExpect(content().string(not(containsString("Page layout"))))
                .andExpect(content().string(containsString("?text=s")))
                .andExpect(content().string(containsString("?font=sans")));
    }

    @Test
    void theTypeParametersSetTheTypeCookieAndTheHtmlClasses() throws Exception {
        String size = mockMvc.perform(get("/login?text=l").header(HttpHeaders.USER_AGENT, PAPERWHITE))
                .andExpect(content().string(containsString("<html lang=\"en\" class=\"eink eink-size-l\">")))
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(size.startsWith("eink-type=l-sans;"), size);
        assertTrue(size.contains("Path=/") && size.contains("SameSite=Lax") && size.contains("Max-Age=31536000"), size);
        assertFalse(size.contains("HttpOnly"), size);

        String face = mockMvc.perform(get("/login?font=serif").header(HttpHeaders.USER_AGENT, PAPERWHITE)
                        .cookie(new Cookie("eink-type", "s-sans")))
                .andExpect(content().string(containsString("class=\"eink eink-size-s eink-face-serif\"")))
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(face.startsWith("eink-type=s-serif;"), face);

        String reset = mockMvc.perform(get("/login?text=m&font=sans").header(HttpHeaders.USER_AGENT, PAPERWHITE)
                        .cookie(new Cookie("eink-type", "l-serif")))
                .andExpect(content().string(containsString(EINK_HTML)))
                .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(reset.startsWith("eink-type=;") && reset.contains("Max-Age=0"), reset);
    }

    @Test
    void theTypeCookieIsReadAndBadValuesAreIgnored() throws Exception {
        mockMvc.perform(get("/login").header(HttpHeaders.USER_AGENT, PAPERWHITE)
                        .cookie(new Cookie("eink-type", "l-serif")))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(content().string(containsString("class=\"eink eink-size-l eink-face-serif\"")));
        mockMvc.perform(get("/login?text=huge&font=comic").header(HttpHeaders.USER_AGENT, PAPERWHITE)
                        .cookie(new Cookie("eink-type", "nonsense")))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(content().string(containsString(EINK_HTML)));
        mockMvc.perform(get("/login").header(HttpHeaders.USER_AGENT, PIXEL)
                        .cookie(new Cookie("eink-type", "l-serif")))
                .andExpect(content().string(containsString(PLAIN_HTML)));
    }

    @Test
    @WithMockUser
    void settingsOffersTheTypeChoicesAndAPostDoesNotSetThem() throws Exception {
        mockMvc.perform(get("/settings/reading").header(HttpHeaders.USER_AGENT, PAPERWHITE)
                        .cookie(new Cookie("eink-type", "l-serif")))
                .andExpect(content().string(containsString("?text=s")))
                .andExpect(content().string(containsString("?text=m")))
                .andExpect(content().string(containsString("?font=sans")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<a [^>]*href=\"[^\"]*\\?text=l\"[^>]*aria-current=\"true\".*")))
                .andExpect(content().string(matchesPattern(
                        "(?s).*<a [^>]*href=\"[^\"]*\\?font=serif\"[^>]*aria-current=\"true\".*")));
        mockMvc.perform(post("/items/mark-read?text=l").with(csrf()).header(HttpHeaders.USER_AGENT, PAPERWHITE))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @WithMockUser
    void theEinkToolbarHasShowAndHideImagesButAScrollingPageKeepsThemUnderMore() throws Exception {
        stubArticle();
        String eink = mockMvc.perform(get("/articles/7").header(HttpHeaders.USER_AGENT, PAPERWHITE))
                .andReturn().getResponse().getContentAsString();
        assertTrue(eink.indexOf("Show images") < eink.indexOf("data-action-menu"), "toolbar link comes before More");
        String scrolling = mockMvc.perform(get("/articles/7").header(HttpHeaders.USER_AGENT, PIXEL))
                .andReturn().getResponse().getContentAsString();
        assertTrue(scrolling.indexOf("Show images") > scrolling.indexOf("data-action-menu"), "phone keeps it under More");
    }

    @Test
    @WithMockUser
    void kindleSetupLinksPointAtAmazonCom() throws Exception {
        AppUser unconfigured = new AppUser(UID, "user@example.com", "hash", null,
                Instant.now(), null, Instant.now(), Instant.now());
        when(userService.findById(UID)).thenReturn(Optional.of(unconfigured));
        for (String path : List.of("/", "/settings/kindle")) {
            mockMvc.perform(get(path).header(HttpHeaders.USER_AGENT, PIXEL))
                    .andExpect(content().string(containsString(
                            "https://www.amazon.com/hz/mycd/preferences/myx#/home/settings/payment")))
                    .andExpect(content().string(not(containsString("amazon.de"))));
        }
    }
}
