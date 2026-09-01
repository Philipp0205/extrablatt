package com.kindlerss.web;

import com.kindlerss.repository.TelemetryRepository;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.security.RateLimiter;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.service.AdminTelemetryService;
import com.kindlerss.service.EntitlementService;
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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AdminController.class)
@Import({com.kindlerss.config.SecurityConfig.class, RateLimiter.class, RateLimitingFilter.class})
@TestPropertySource(properties = "app.remember-me-key=admin-test-key")
class AdminControllerSecurityTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    EntitlementService entitlementService;

    @MockitoBean
    AdminTelemetryService telemetryService;

    @MockitoBean
    UserDetailsService userDetailsService;

    @MockitoBean
    CurrentUser currentUser;

    @MockitoBean
    UserService userService;

    @Test
    @WithMockUser(roles = "USER")
    void normalUsersCannotViewTelemetry() throws Exception {
        mockMvc.perform(get("/admin")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void administratorsCanViewTelemetry() throws Exception {
        when(telemetryService.summary())
                .thenReturn(new TelemetryRepository.Summary(2, 3, 10, 4, 1, 3, 4));
        Instant lastLogin = Instant.parse("2026-08-20T12:00:00Z");
        when(telemetryService.users()).thenReturn(List.of(
                new TelemetryRepository.UserUsage(
                        1L, "reader@example.com", true, Instant.parse("2026-01-01T00:00:00Z"),
                        lastLogin, 2, 8, 5, 1, 3, null, null)));

        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin"))
                .andExpect(content().string(containsString("Sent in 30 days")))
                .andExpect(content().string(containsString("sent in 30d")))
                .andExpect(content().string(containsString("Last login")))
                .andExpect(content().string(containsString("2026-08-20")));
    }
}
