package com.kindlerss.web;

import com.kindlerss.repository.FeedRepository;
import com.kindlerss.repository.UserRepository;
import com.kindlerss.service.KindleMailService;
import com.kindlerss.service.SafeHttpClient;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the e-ink (Kindle) layout in headless Chrome at real Kindle screen sizes and
 * checks that it fits: no horizontal overflow, a readable root font size, tap targets of
 * at least 44 CSS px, a tab bar that does not eat the screen, and enough list rows, the
 * article heading and first paragraph and the pager inside the first screen. The login
 * page, the feeds page, the article list, an article and Settings are visited at each size.
 * Pages are what a Kindle asks for: Kindle user agents, {@code isMobile} off (the old
 * browser ignores the meta viewport), touch on.
 *
 * <p>The sizes are not all known. The Paperwhite 2 and 3 sizes were measured by others
 * (decadecity.net, Thor Galle). The Basic is its panel size at 167 ppi. No first-hand
 * report exists of what the Paperwhite 11 and 12 browsers report, so their two CSS-pixel
 * sizes (the panel at 2x) are assumptions, and both panel-pixel readings are checked as
 * the alternative. The 930x1240 size comes from a comment in eink.css and is unsourced.
 *
 * <p>Headless Chrome cannot reproduce the old WebKit browser's missing features or its
 * slow panel. This catches layouts that are too big, not what that browser cannot do.
 *
 * <p>The test drives the system Google Chrome (installed on GitHub's ubuntu runners; no
 * browser is downloaded). Set {@code EXTRABLATT_CHROME_PATH} to use another Chrome or
 * Chromium. Signing in happens once: the login form is rate limited, so every size reuses
 * the stored session.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KindleViewportTest {

    private static final String UA_CHROMIUM = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Version/5.0 Safari/537.36 Kindle/3.0+";
    private static final String UA_WEBKIT = "Mozilla/5.0 (X11; U; Linux armv7l like Android; en-us)"
            + " AppleWebKit/531.2+ (KHTML, like Gecko) Version/5.0 Safari/533.2+ Kindle/3.0+";
    private static final String EMAIL = "viewport@example.com";
    private static final String PASSWORD = "viewport-password-1";

    private static EmbeddedPostgres postgres;
    private static Playwright playwright;
    private static Browser browser;
    private static String storageState;
    private static long firstArticleId;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        registry.add("spring.datasource.url",
                () -> "jdbc:postgresql://localhost:" + postgres.getPort() + "/postgres");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("app.mail-from", () -> "noreply@example.com");
        registry.add("app.remember-me-key", () -> "kindle-viewport-test-remember-key");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired FeedRepository feeds;
    @Autowired PasswordEncoder passwordEncoder;

    @MockitoBean SafeHttpClient httpClient;
    @MockitoBean KindleMailService kindleMailService;

    record Viewport(String name, int width, int height, double dpr, String userAgent,
                    int minListRows, int maxTabbarPx) {
        @Override
        public String toString() {
            return name;
        }
    }

    enum Kind { LOGIN, FEEDS, ITEMS, ARTICLE, SETTINGS }

    static Stream<Viewport> viewports() {
        return Stream.of(
                // ASSUMED, not measured.
                new Viewport("Paperwhite 11 (6.8in 1236x1648 @300ppi, assumed 618x824 @2)",
                        618, 824, 2, UA_CHROMIUM, 5, 60),
                // ASSUMED, not measured.
                new Viewport("Paperwhite 12 / Colorsoft (7in 1264x1680, assumed 632x840 @2)",
                        632, 840, 2, UA_CHROMIUM, 6, 60),
                new Viewport("Kindle Basic (600x800 @167ppi)",
                        600, 800, 1, UA_WEBKIT, 5, 60),
                new Viewport("Paperwhite 2 (measured by decadecity.net: 758x897 @1.27)",
                        758, 897, 1.27, UA_WEBKIT, 4, 80),
                new Viewport("Paperwhite 3 / 10th gen (measured by Thor Galle: 1072x1268 @1)",
                        1072, 1268, 1, UA_WEBKIT, 7, 80),
                new Viewport("Scribe / Oasis (930x1240 @2, from the comment in eink.css, unsourced)",
                        930, 1240, 2, UA_CHROMIUM, 7, 80),
                new Viewport("Paperwhite 11 if the browser reports panel pixels (1236x1648 @1, alternative)",
                        1236, 1648, 1, UA_WEBKIT, 9, 80),
                new Viewport("Paperwhite 12 if the browser reports panel pixels (1264x1680 @1, alternative)",
                        1264, 1680, 1, UA_WEBKIT, 9, 80));
    }

    @BeforeEach
    void seedAndSignInOnce() {
        if (storageState != null) {
            return;
        }
        long userId = users.insert(EMAIL, passwordEncoder.encode(PASSWORD)).id();
        users.markEmailVerified(userId);
        Instant now = Instant.now();
        for (int f = 1; f <= 3; f++) {
            long feedId = feeds.insert(userId, "Feed " + f, "https://feed" + f + ".example.com/rss",
                    "https://feed" + f + ".example.com", null).id();
            // Newest first: article 1 is the newest. Feed f takes articles f, f+3, f+6 ...
            for (int n = f; n <= 25; n += 3) {
                long id = insertArticle(feedId, n, now.minus(n, ChronoUnit.HOURS));
                if (n == 1) {
                    firstArticleId = id;
                }
            }
        }
        browser = launchBrowser();
        BrowserContext context = browser.newContext(options(new Viewport("login", 758, 897, 1, UA_WEBKIT, 0, 0)));
        Page page = context.newPage();
        page.navigate(base() + "/login?layout=kindle");
        page.locator("#username").fill(EMAIL);
        page.locator("#password").fill(PASSWORD);
        page.locator("button[type=submit]").first().click();
        page.waitForURL(url -> !url.contains("/login"));
        page.navigate(base() + "/items");
        if (page.locator("button:has-text('Got it')").count() > 0) {
            page.locator("button:has-text('Got it')").first().click();
            page.waitForLoadState();
        }
        storageState = context.storageState();
        context.close();
    }

    private long insertArticle(long feedId, int n, Instant publishedAt) {
        String title = "Headline number " + n + ": something worth reading slowly";
        StringBuilder body = new StringBuilder();
        int paragraphs = n == 1 ? 30 : 2;
        for (int p = 1; p <= paragraphs; p++) {
            body.append("<p>Paragraph ").append(p).append(" of article ").append(n)
                    .append(". The quick brown fox jumps over the lazy dog while the reader turns the page,")
                    .append(" and keeps turning it, because this text is long enough to span several screens")
                    .append(" on a small e-ink panel.</p>");
        }
        return jdbc.queryForObject("""
                INSERT INTO articles (feed_id, guid, title, url, author, published_at, summary_html,
                                      feed_content_html, extracted_content_html, read, read_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, NULL) RETURNING id
                """, Long.class, feedId, "guid-" + n, title, "https://example.com/a/" + n, "Author " + n,
                Timestamp.from(publishedAt), "<p>Summary " + n + "</p>", body.toString(), body.toString());
    }

    private static Browser launchBrowser() {
        playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        BrowserType.LaunchOptions launch = new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setArgs(List.of("--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage"));
        String chromePath = System.getenv("EXTRABLATT_CHROME_PATH");
        if (chromePath != null && !chromePath.isBlank()) {
            launch.setExecutablePath(Path.of(chromePath));
        } else {
            launch.setChannel("chrome");
        }
        return playwright.chromium().launch(launch);
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private static Browser.NewContextOptions options(Viewport v) {
        return new Browser.NewContextOptions()
                .setViewportSize(v.width(), v.height())
                .setDeviceScaleFactor(v.dpr())
                .setIsMobile(false)
                .setHasTouch(true)
                .setUserAgent(v.userAgent());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("viewports")
    void theEinkLayoutFitsTheScreen(Viewport v) {
        List<String> failures = new ArrayList<>();
        try (BrowserContext anonymous = browser.newContext(options(v));
             BrowserContext signedIn = browser.newContext(options(v).setStorageState(storageState))) {
            Page login = anonymous.newPage();
            failures.addAll(check(v, "/login?layout=kindle", Kind.LOGIN, login));
            Page page = signedIn.newPage();
            failures.addAll(check(v, "/?layout=kindle", Kind.FEEDS, page));
            failures.addAll(check(v, "/items", Kind.ITEMS, page));
            failures.addAll(check(v, "/articles/" + firstArticleId + "?images=true", Kind.ARTICLE, page));
            failures.addAll(check(v, "/settings", Kind.SETTINGS, page));
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private static final String MEASURE = """
            () => {
              const de = document.documentElement;
              const vis = (el) => {
                const r = el.getBoundingClientRect();
                const cs = getComputedStyle(el);
                return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none';
              };
              const rect = (el) => {
                const r = el.getBoundingClientRect();
                return [r.left, r.top, r.right, r.bottom];
              };
              const visible = (sel) => Array.from(document.querySelectorAll(sel)).filter(vis);
              const first = (sel) => { const e = visible(sel)[0]; return e ? rect(e) : null; };
              const boxes = [];
              for (const sel of ['main', '.pager', '.tabbar', '.appbar']) {
                visible(sel).forEach((e) => boxes.push([sel].concat(rect(e))));
              }
              const small = visible('button, .btn, .tabbar a, .send-btn, select, input[type=text],'
                  + ' input[type=email], input[type=password], input[type=url]')
                .map((e) => [(e.tagName.toLowerCase() + (e.id ? '#' + e.id : '')
                    + (e.className && e.className.baseVal === undefined ? '.' + String(e.className).trim().split(/\\s+/).join('.') : '')
                    + ' "' + (e.innerText || e.value || '').trim().slice(0, 20) + '"'),
                    e.getBoundingClientRect().height]);
              const tabbar = first('.tabbar');
              return {
                eink: de.classList.contains('eink'),
                paged: de.classList.contains('reader-paged'),
                appKit: !!document.querySelector('script[src*="app-kit"], link[href*="app-kit"]'),
                innerWidth: window.innerWidth,
                innerHeight: window.innerHeight,
                scrollWidth: de.scrollWidth,
                fontSize: parseFloat(getComputedStyle(de).fontSize),
                boxes: boxes,
                targets: small,
                tabbarHeight: tabbar ? tabbar[3] - tabbar[1] : null,
                rows: visible('.item-list > li').map(rect),
                pager: first('.pager'),
                h1: first('main h1'),
                firstParagraph: first('.article-body p')
              };
            }
            """;

    /** Loads one page, measures it and returns every way it does not fit, each naming the size and path. */
    private List<String> check(Viewport v, String path, Kind kind, Page page) {
        page.navigate(base() + path);
        page.waitForFunction("() => !document.documentElement.classList.contains('reader-loading')", null,
                new Page.WaitForFunctionOptions().setTimeout(8000));
        page.waitForTimeout(400);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) page.evaluate(MEASURE);

        List<String> problems = new ArrayList<>();
        double innerWidth = num(m.get("innerWidth"));
        double innerHeight = num(m.get("innerHeight"));
        // a. the e-ink layout, without the app kit
        if (!Boolean.TRUE.equals(m.get("eink"))) {
            problems.add("html does not have class eink");
        }
        if (Boolean.TRUE.equals(m.get("appKit"))) {
            problems.add("page loads app-kit script or stylesheet");
        }
        // b. no horizontal overflow
        if (innerWidth != v.width()) {
            problems.add("innerWidth " + innerWidth + " differs from the viewport width " + v.width());
        }
        if (Boolean.TRUE.equals(m.get("paged"))) {
            for (Object o : list(m.get("boxes"))) {
                List<?> b = (List<?>) o;
                if (num(b.get(1)) < 0 || num(b.get(3)) > innerWidth + 1) {
                    problems.add(b.get(0) + " sticks out horizontally: left " + num(b.get(1))
                            + ", right " + num(b.get(3)) + ", innerWidth " + innerWidth);
                }
            }
        } else if (num(m.get("scrollWidth")) > innerWidth + 1) {
            problems.add("horizontal overflow: scrollWidth " + num(m.get("scrollWidth"))
                    + " > innerWidth " + innerWidth);
        }
        // c. root font size
        double font = num(m.get("fontSize"));
        if (font < 16 || font > 21) {
            problems.add("root font size " + font + "px is outside 16-21px");
        }
        // d. tap targets
        List<String> small = new ArrayList<>();
        for (Object o : list(m.get("targets"))) {
            List<?> t = (List<?>) o;
            if (num(t.get(1)) < 43.9) {
                small.add(t.get(0) + " is " + num(t.get(1)) + "px");
            }
        }
        if (!small.isEmpty()) {
            problems.add("tap targets under 44px: " + small);
        }
        // e. tab bar
        if (m.get("tabbarHeight") != null && num(m.get("tabbarHeight")) > v.maxTabbarPx()) {
            problems.add("tab bar is " + num(m.get("tabbarHeight")) + "px tall, more than " + v.maxTabbarPx());
        }
        // what must be on the first screen
        if (kind == Kind.ITEMS) {
            long rows = list(m.get("rows")).stream()
                    .filter(r -> inside((List<?>) r, innerWidth, innerHeight)).count();
            if (rows < v.minListRows()) {
                problems.add("only " + rows + " list rows fit on the first screen, need " + v.minListRows()
                        + " (" + list(m.get("rows")).size() + " rows in the page)");
            }
            requirePagerInside(m, innerHeight, problems);
        } else if (kind == Kind.ARTICLE) {
            requireInside(m, "h1", innerWidth, innerHeight, problems);
            requireInside(m, "firstParagraph", innerWidth, innerHeight, problems);
            requireInside(m, "pager", innerWidth, innerHeight, problems);
        } else if (kind == Kind.SETTINGS) {
            requireInside(m, "h1", innerWidth, innerHeight, problems);
        }
        return problems.stream().map(p -> v.name() + " " + path + ": " + p).toList();
    }

    private static void requirePagerInside(Map<String, Object> m, double innerHeight, List<String> problems) {
        List<?> pager = (List<?>) m.get("pager");
        if (pager != null && num(pager.get(3)) > innerHeight) {
            problems.add("pager bottom " + num(pager.get(3)) + " is below the viewport height " + innerHeight);
        }
    }

    private static void requireInside(Map<String, Object> m, String key, double w, double h, List<String> problems) {
        List<?> r = (List<?>) m.get(key);
        if (r == null) {
            problems.add(key + " is missing or not visible");
        } else if (!inside(r, w, h)) {
            problems.add(key + " is not inside the " + w + "x" + h + " viewport: " + r);
        }
    }

    private static boolean inside(List<?> r, double w, double h) {
        return num(r.get(0)) >= 0 && num(r.get(2)) <= w + 1 && num(r.get(1)) >= 0 && num(r.get(3)) <= h;
    }

    private static double num(Object o) {
        return ((Number) o).doubleValue();
    }

    private static List<?> list(Object o) {
        return (List<?>) o;
    }
}
