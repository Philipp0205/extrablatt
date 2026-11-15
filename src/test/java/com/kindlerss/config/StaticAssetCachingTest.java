package com.kindlerss.config;

import com.kindlerss.security.CurrentUser;
import com.kindlerss.security.RateLimiter;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.service.EntitlementService;
import com.kindlerss.service.SubscriptionService;
import com.kindlerss.service.UserService;
import com.kindlerss.web.AuthController;
import com.kindlerss.web.GlobalExceptionHandler;
import com.kindlerss.service.ApiTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reading is a sequence of page loads, and every one of them used to refetch every
 * script: the scripts went through the security filter chain, which stamps
 * {@code no-store} on what it lets past, so a browser was never allowed to keep
 * them. The same header on the stylesheet was what made a page appear unstyled for
 * a moment on a phone, since a browser will not paint until a linked stylesheet
 * arrives.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, RateLimiter.class, RateLimitingFilter.class})
@TestPropertySource(properties = {
        "app.mail-from=from@example.com",
        "app.remember-me-key=test-remember-key",
        "spring.web.resources.chain.enabled=true"
})
class StaticAssetCachingTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ApiTokenService apiTokenService;

    @MockitoBean
    UserDetailsService userDetailsService;

    @MockitoBean
    CurrentUser currentUser;

    @MockitoBean
    UserService userService;

    @MockitoBean
    EntitlementService entitlementService;

    @MockitoBean
    SubscriptionService subscriptionService;

    /**
     * The name carries a hash of the file, so keeping it for a year cannot serve
     * yesterday's script: after a change the address is a different one.
     */
    @Test
    void aScriptIsCacheableForAYearAndNotHeldBackBySecurity() throws Exception {
        mockMvc.perform(get("/js/app.js"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=31536000")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("immutable")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, not(containsString("no-store"))))
                .andExpect(header().doesNotExist(HttpHeaders.PRAGMA));
    }

    /**
     * Fetching a script must not hand out a session either. It is the same file for
     * everyone, and a request for it says nothing about who asked.
     */
    @Test
    void fetchingAScriptStartsNoSession() throws Exception {
        mockMvc.perform(get("/js/app.js"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    /**
     * A page still needs its own headers: it holds somebody's reading list, and a
     * shared cache must not keep that.
     */
    @Test
    void aPageIsStillNotAllowedToBeKept() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    /**
     * The stylesheet is a file of its own, linked under a hashed name, so it is
     * kept like the scripts and every page after the first paints at once.
     */
    @Test
    void theStylesheetIsCacheableForAYearAndStartsNoSession() throws Exception {
        mockMvc.perform(get("/css/app.css"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=31536000")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("immutable")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, not(containsString("no-store"))))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    /**
     * The phone layout's serif is a self-hosted font. Its file name carries the
     * version, so it is kept for a year like the scripts, and it is public: a font
     * request says nothing about who asked and must not start a session.
     */
    @Test
    void aFontIsPublicCacheableForAYearAndStartsNoSession() throws Exception {
        mockMvc.perform(get("/fonts/newsreader-5.3.0-latin-wght-normal.woff2"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=31536000")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("immutable")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, not(containsString("no-store"))))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    /**
     * The vendored app-kit is served like /css and /js: the name carries a hash
     * of the file, so a year-long immutable cache is safe, and it starts no session.
     */
    @Test
    void anAppKitStylesheetIsCacheableForAYearAndStartsNoSession() throws Exception {
        mockMvc.perform(get("/app-kit/ak-tokens.css"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=31536000")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("immutable")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, not(containsString("no-store"))))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void anAppKitScriptIsCacheableForAYearAndStartsNoSession() throws Exception {
        mockMvc.perform(get("/app-kit/theme-color.js"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=31536000")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("immutable")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, not(containsString("no-store"))))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    /**
     * The scrolling layouts link the kit under hashed names, ahead of app.css, and
     * its two scripts ahead of app.js, which stays the last shared script.
     */
    @Test
    void theLoginPageLinksTheHashedAppKitFilesBeforeTheAppFiles() throws Exception {
        String html = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String[] inOrder = {
                "href=\"/app-kit/ak-tokens-", "href=\"/app-kit/ak-components-",
                "href=\"/app-kit/app-kit-", "href=\"/app-kit/install-prompt-",
                "src=\"/app-kit/theme-color-", "src=\"/app-kit/install-prompt-",
                "href=\"/css/app-", "src=\"/js/app-"};
        int from = 0;
        for (String part : inOrder) {
            int at = html.indexOf(part, from);
            org.junit.jupiter.api.Assertions.assertTrue(at >= 0, part + " missing or out of order in " + html);
            from = at;
        }
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("app-kit/theme-color-"), "theme-color.js");
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("/app-kit/ak-dom"), "ak-dom.js is not loaded yet");
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("/app-kit/ak-sheet"), "ak-sheet.js is not loaded yet");
    }

    /**
     * The manifest is revalidated on every use (a week-long copy kept the old light
     * colours on installed apps), and must be served with the manifest media type,
     * not octet-stream.
     */
    @Test
    void theManifestIsPublicShortCachedAndHasTheManifestType() throws Exception {
        mockMvc.perform(get("/manifest.webmanifest"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("application/manifest+json")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=0")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("must-revalidate")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, not(containsString("max-age=604800"))))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, not(containsString("no-store"))))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    /** Icons are public and cacheable, with stable names, and start no session. */
    @Test
    void anIconIsPublicShortCachedAndStartsNoSession() throws Exception {
        mockMvc.perform(get("/icons/icon-192.png"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("image/png")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, not(containsString("no-store"))))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void theLoginPageLinksTheManifestAndIcons() throws Exception {
        String html = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // Browsers that ignore media (Firefox's installed app) take the first meta,
        // so it must be the dark phone colour.
        java.util.regex.Matcher firstMeta = java.util.regex.Pattern
                .compile("<meta name=\"theme-color\"[^>]*>").matcher(html);
        org.junit.jupiter.api.Assertions.assertTrue(firstMeta.find(), "no theme-color meta");
        org.junit.jupiter.api.Assertions.assertTrue(
                firstMeta.group().contains("content=\"#17140f\" media=\"(prefers-color-scheme: dark) and (max-width: 719px)\""),
                "first theme-color meta was " + firstMeta.group());

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/manifest.webmanifest?v=3\"")))
                .andExpect(content().string(containsString("href=\"/favicon.svg\"")))
                .andExpect(content().string(containsString("href=\"/icons/favicon-32.png\"")))
                .andExpect(content().string(containsString("href=\"/apple-touch-icon.png?v=2\"")))
                .andExpect(content().string(containsString("name=\"apple-mobile-web-app-capable\"")))
                .andExpect(content().string(containsString("name=\"mobile-web-app-capable\"")))
                .andExpect(content().string(containsString("name=\"apple-mobile-web-app-status-bar-style\" content=\"black-translucent\"")))
                .andExpect(content().string(containsString("content=\"#f4eee1\" media=\"(prefers-color-scheme: light) and (max-width: 719px)\"")))
                .andExpect(content().string(containsString("content=\"#17140f\" media=\"(prefers-color-scheme: dark) and (max-width: 719px)\"")));
    }

    /**
     * Home-screen icons are cached hard by iOS and Android, so the manifest points at
     * versioned icon addresses. The manifest also carries the dark theme_color and
     * background_color, the fallback an installed Android app keeps until the page's
     * own colour is applied.
     */
    @Test
    void theManifestVersionsItsIconsAndSetsTheDarkFallbackColours() throws Exception {
        mockMvc.perform(get("/manifest.webmanifest"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"/icons/icon-192.png?v=2\"")))
                .andExpect(content().string(containsString("\"/icons/icon-512.png?v=2\"")))
                .andExpect(content().string(containsString("\"/icons/maskable-512.png?v=2\"")))
                .andExpect(content().string(containsString("\"theme_color\": \"#17140f\"")))
                .andExpect(content().string(containsString("\"background_color\": \"#17140f\"")));
    }

    @Test
    void theSvgFaviconIsPublicShortCachedAndStartsNoSession() throws Exception {
        mockMvc.perform(get("/favicon.svg"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("image/svg+xml")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, not(containsString("no-store"))))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }
}
