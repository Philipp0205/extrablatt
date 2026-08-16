package com.kindlerss.web;

import com.kindlerss.domain.AppUser;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.security.RateLimiter;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.service.ArticleService;
import com.kindlerss.service.UserService;
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
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A separate context from {@link SettingsControllerTest} since the newsletters-enabled properties only apply class-wide. */
@WebMvcTest(controllers = SettingsController.class)
@Import({com.kindlerss.config.SecurityConfig.class, GlobalExceptionHandler.class,
        RateLimiter.class, RateLimitingFilter.class})
@TestPropertySource(properties = {
        "app.mail-from=from@example.com",
        "app.remember-me-key=test-remember-key",
        "app.newsletters.inbound-domain=news.example.com",
        "app.newsletters.inbound-secret=shh"
})
class SettingsControllerNewslettersEnabledTest {

    private static final long UID = 1L;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    UserService userService;

    @MockitoBean
    ArticleService articleService;

    @MockitoBean
    CurrentUser currentUser;

    @MockitoBean
    UserDetailsService userDetailsService;

    @BeforeEach
    void signInAsUserOne() {
        AppUser user = new AppUser(UID, "user@example.com", "hash", "reader@kindle.com",
                Instant.now(), null, Instant.now(), Instant.now());
        when(currentUser.requireId()).thenReturn(UID);
        when(currentUser.details()).thenReturn(Optional.of(new AppUserDetails(user)));
        when(userService.findById(UID)).thenReturn(Optional.of(user));
    }

    @Test
    @WithMockUser
    void settingsShowsTheAccountsGeneratedNewsletterAddress() throws Exception {
        when(userService.ensureNewsletterInboundToken(UID)).thenReturn("abc123");

        mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("abc123@news.example.com")));
    }

    @Test
    @WithMockUser
    void regeneratingTheAddressReportsTheNewOne() throws Exception {
        when(userService.regenerateNewsletterInboundToken(UID)).thenReturn("freshtoken");

        mockMvc.perform(post("/settings/newsletter-address/regenerate").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings"))
                .andExpect(flash().attribute("message", containsString("freshtoken@news.example.com")));
        verify(userService).regenerateNewsletterInboundToken(UID);
    }

    @Test
    @WithMockUser
    void regeneratingFromFeedsReturnsToTheNewslettersSection() throws Exception {
        when(userService.regenerateNewsletterInboundToken(UID)).thenReturn("freshtoken");

        mockMvc.perform(post("/settings/newsletter-address/regenerate").with(csrf())
                        .param("redirect", "/?section=newsletters"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/?section=newsletters"));
        verify(userService).regenerateNewsletterInboundToken(UID);
    }
}
