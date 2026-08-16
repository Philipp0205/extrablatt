package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.AppUser;
import com.kindlerss.repository.TelemetryRepository;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.security.RateLimiter;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.service.AdminTelemetryService;
import com.kindlerss.service.ArticleService;
import com.kindlerss.service.UserService;
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
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Telemetry is folded into Settings for administrators only. */
@WebMvcTest(SettingsController.class)
@Import({com.kindlerss.config.SecurityConfig.class, RateLimiter.class, RateLimitingFilter.class})
@TestPropertySource(properties = {
        "app.remember-me-key=settings-test-key",
        "app.limits.max-sends-per-day=50"
})
class SettingsControllerTest {

    private static final long UID = 1L;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    UserService userService;

    @MockitoBean
    ArticleService articleService;

    @MockitoBean
    AdminTelemetryService telemetryService;

    @MockitoBean
    CurrentUser currentUser;

    @MockitoBean
    UserDetailsService userDetailsService;

    private AppUser account() {
        return new AppUser(UID, "user@example.com", "hash", null,
                Instant.now(), null, Instant.now(), Instant.now());
    }

    @Test
    @WithMockUser(roles = "USER")
    void plainUsersDoNotSeeTelemetry() throws Exception {
        when(currentUser.requireId()).thenReturn(UID);
        when(currentUser.details()).thenReturn(Optional.of(new AppUserDetails(account())));
        when(userService.findById(UID)).thenReturn(Optional.of(account()));

        mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Telemetry"))));
    }

    @Test
    @WithMockUser(roles = {"USER", "ADMIN"})
    void administratorsSeeTelemetryOnTheSettingsPage() throws Exception {
        when(currentUser.requireId()).thenReturn(UID);
        when(currentUser.details()).thenReturn(Optional.of(new AppUserDetails(account(), true)));
        when(userService.findById(UID)).thenReturn(Optional.of(account()));
        when(telemetryService.summary())
                .thenReturn(new TelemetryRepository.Summary(2, 3, 10, 4, 1, 3));
        when(telemetryService.users()).thenReturn(List.of());

        mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Telemetry")))
                .andExpect(content().string(containsString("User usage and send limits")));
    }
}
