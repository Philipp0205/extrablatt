package com.kindlerss.config;

import com.kindlerss.security.CurrentUser;
import com.kindlerss.security.RateLimiter;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.service.EntitlementService;
import com.kindlerss.service.SubscriptionService;
import com.kindlerss.service.UserService;
import com.kindlerss.web.AuthController;
import com.kindlerss.web.GlobalExceptionHandler;
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
     * yesterday's reader: after a change the address is a different one.
     */
    @Test
    void aScriptIsCacheableForAYearAndNotHeldBackBySecurity() throws Exception {
        mockMvc.perform(get("/js/reader.js"))
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
        mockMvc.perform(get("/js/reader.js"))
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
     * The stylesheet is not served as a file at all any more — it reaches the browser
     * inside the page. A second, separately cached copy is exactly what could fall
     * out of step with the markup it styles.
     */
    @Test
    void theStylesheetIsNotServedAsAFileOfItsOwn() throws Exception {
        mockMvc.perform(get("/css/app.css"))
                .andExpect(status().is3xxRedirection());
    }
}
