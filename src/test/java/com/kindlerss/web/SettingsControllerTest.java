package com.kindlerss.web;

import com.kindlerss.domain.AppUser;
import com.kindlerss.repository.TelemetryRepository;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.security.RateLimiter;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.service.AdminTelemetryService;
import com.kindlerss.service.ArticleService;
import com.kindlerss.service.DataExportService;
import com.kindlerss.service.EntitlementService;
import com.kindlerss.service.RetentionService;
import com.kindlerss.service.SubscriptionService;
import com.kindlerss.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Settings covers account options, optional newsletters, and admin-only telemetry. */
@WebMvcTest(controllers = SettingsController.class)
@Import({com.kindlerss.config.SecurityConfig.class, GlobalExceptionHandler.class,
        RateLimiter.class, RateLimitingFilter.class})
@TestPropertySource(properties = {
        "app.mail-from=from@example.com",
        "app.remember-me-key=test-remember-key",
        "app.limits.max-sends-per-day=50"
})
class SettingsControllerTest {

    private static final long UID = 1L;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DataExportService dataExportService;

    @MockitoBean
    RetentionService retentionService;

    @MockitoBean
    EntitlementService entitlementService;

    @MockitoBean
    SubscriptionService subscriptionService;

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
        return new AppUser(UID, "user@example.com", "hash", "reader@kindle.com",
                Instant.now(), null, Instant.now(), Instant.now());
    }

    @BeforeEach
    void signInAsUserOne() {
        AppUser user = account();
        when(currentUser.requireId()).thenReturn(UID);
        when(currentUser.details()).thenReturn(Optional.of(new AppUserDetails(user)));
        when(userService.findById(UID)).thenReturn(Optional.of(user));
    }

    /**
     * Art. 15 and Art. 20 GDPR: a copy of their own data, in a machine-readable form,
     * as a file rather than as a request somebody has to remember to answer.
     */
    @Test
    @WithMockUser
    void theDataExportIsDownloadableByTheAccountItBelongsTo() throws Exception {
        when(dataExportService.exportJson(UID)).thenReturn("{\"account\":{}}".getBytes());
        when(dataExportService.filename()).thenReturn("extrablatt-data-2026-08-31.json");

        mockMvc.perform(get("/account/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        containsString("extrablatt-data-2026-08-31.json")))
                .andExpect(content().contentTypeCompatibleWith("application/json"));
        verify(dataExportService).exportJson(UID);
    }

    @Test
    void theDataExportNeedsAnAccount() throws Exception {
        mockMvc.perform(get("/account/export"))
                .andExpect(status().is3xxRedirection());
        verify(dataExportService, never()).exportJson(anyLong());
    }

    @Test
    @WithMockUser
    void theDataViewExplainsWhatIsHeldAndForHowLong() throws Exception {
        mockMvc.perform(get("/settings").param("view", "data"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Download my data")))
                .andExpect(content().string(containsString("bcrypt")))
                .andExpect(content().string(containsString("How long we keep things")));
    }

    /**
     * Payment payloads carry no cascade, so erasure has to reach them explicitly.
     * If this stops being called, an account's payment data outlives the account.
     */
    @Test
    @WithMockUser
    void deletingAnAccountAlsoErasesWhatCascadesCannotReach() throws Exception {
        mockMvc.perform(post("/account/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?deleted"));

        InOrder order = inOrder(retentionService, userService);
        // Before the delete, while there is still a user id to find them by.
        order.verify(retentionService).eraseForUser(UID);
        order.verify(userService).deleteAccount(UID);
    }

    @Test
    @WithMockUser
    void newslettersSectionIsHiddenWhenNotConfigured() throws Exception {
        mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("New address"))))
                .andExpect(content().string(not(containsString("action=\"/refresh\""))))
                .andExpect(content().string(not(containsString(">Refresh</button>"))));
        verify(userService, never()).ensureNewsletterInboundToken(UID);
    }

    @Test
    @WithMockUser
    void updatingKindleEmailDelegatesToUserService() throws Exception {
        mockMvc.perform(post("/settings/kindle-email").with(csrf())
                        .param("kindleEmail", "me@kindle.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings#kindle"))
                .andExpect(flash().attribute("message", "Kindle e-mail updated"));
        verify(userService).updateKindleEmail(UID, "me@kindle.com");
    }

    @Test
    @WithMockUser
    void regeneratingTheNewsletterAddressWithoutConfigurationFailsGracefully() throws Exception {
        mockMvc.perform(post("/settings/newsletter-address/regenerate").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings#kindle"))
                .andExpect(flash().attribute("error", containsString("not configured")));
        verify(userService, never()).regenerateNewsletterInboundToken(UID);
    }

    @Test
    @WithMockUser
    void deletingTheAccountLogsOutAndRedirectsToLogin() throws Exception {
        mockMvc.perform(post("/account/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?deleted"));
        verify(userService).deleteAccount(UID);
    }

    @Test
    @WithMockUser(roles = "USER")
    void plainUsersDoNotSeeTelemetry() throws Exception {
        mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Telemetry"))));
    }

    @Test
    @WithMockUser(roles = {"USER", "ADMIN"})
    void administratorsSeeTelemetryOnTheSettingsPage() throws Exception {
        when(currentUser.details()).thenReturn(Optional.of(new AppUserDetails(account(), true)));
        when(telemetryService.summary())
                .thenReturn(new TelemetryRepository.Summary(2, 3, 10, 4, 1, 3));
        when(telemetryService.users()).thenReturn(List.of());

        mockMvc.perform(get("/settings").param("view", "telemetry"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Telemetry")))
                .andExpect(content().string(containsString("User usage and send limits")));
    }

    @Test
    @WithMockUser
    void settingsRendersAllSectionsWithoutATabStrip() throws Exception {
        mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Send-to-Kindle")))
                .andExpect(content().string(containsString("Signed in as")))
                .andExpect(content().string(containsString("Delete my account")))
                .andExpect(content().string(not(containsString("aria-label=\"Settings views\""))));
    }

    @Test
    @WithMockUser
    void readingSettingsCanTurnMarkOnNextPageOff() throws Exception {
        mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Mark articles as read when I go to the next page")))
                .andExpect(content().string(containsString("action=\"/settings/reading\"")))
                .andExpect(content().string(containsString("id=\"reading\"")));

        mockMvc.perform(post("/settings/reading").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings#reading"))
                .andExpect(flash().attribute("message", "Reading preference saved"));
        verify(userService).updateMarkReadOnNextPage(UID, false);
    }

    @Test
    @WithMockUser
    void readingSettingsCanTurnMarkOnNextPageOn() throws Exception {
        mockMvc.perform(post("/settings/reading").with(csrf())
                        .param("markReadOnNextPage", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings#reading"));
        verify(userService).updateMarkReadOnNextPage(UID, true);
    }

    @Test
    @WithMockUser
    void settingsLinksToTheChangelogPageAndShowsWhatsNew() throws Exception {
        mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"changelog\"")))
                .andExpect(content().string(containsString("href=\"/settings/changelog\"")))
                .andExpect(content().string(containsString("Changelog")))
                .andExpect(content().string(not(containsString("class=\"changelog-release\""))))
                .andExpect(content().string(containsString("What's new")))
                .andExpect(content().string(containsString("id=\"whats-new-dialog\"")));
    }

    @Test
    @WithMockUser
    void changelogPageListsThePackagedReleases() throws Exception {
        mockMvc.perform(get("/settings/changelog"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Changelog")))
                .andExpect(content().string(containsString("href=\"/settings\"")))
                .andExpect(content().string(containsString("class=\"changelog-release\"")))
                .andExpect(content().string(containsString(
                        ChangelogCatalog.instance().latest().orElseThrow().title())));
    }

    @Test
    @WithMockUser
    void alreadySeenReleaseDoesNotOpenTheWhatsNewNotice() throws Exception {
        String latest = ChangelogCatalog.instance().latestId().orElseThrow();
        AppUser seen = new AppUser(UID, "user@example.com", "hash", "reader@kindle.com",
                Instant.now(), null, Instant.now(), Instant.now(), null, true, latest);
        when(userService.findById(UID)).thenReturn(Optional.of(seen));

        mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"changelog\"")))
                .andExpect(content().string(not(containsString("id=\"whats-new-dialog\""))));
    }

    @Test
    @WithMockUser
    void acknowledgingTheChangelogStoresTheLatestReleaseAndReturnsToThePage() throws Exception {
        mockMvc.perform(post("/settings/changelog/ack").with(csrf())
                        .param("redirect", "/items?unread=true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/items?unread=true"));
        verify(userService).acknowledgeChangelog(UID, ChangelogCatalog.instance().latestId().orElseThrow());
    }
}
