package com.kindlerss.web;

import com.kindlerss.security.CurrentUser;
import com.kindlerss.security.RateLimiter;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.service.FeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The inbound newsletter webhook is not a login-protected page: providers
 * authenticate with a shared secret instead, so these checks exercise it
 * unauthenticated, the way a real mail provider would call it.
 */
@WebMvcTest(controllers = NewsletterInboundController.class)
@Import({com.kindlerss.config.SecurityConfig.class, GlobalExceptionHandler.class,
        RateLimiter.class, RateLimitingFilter.class})
@TestPropertySource(properties = {
        "app.mail-from=from@example.com",
        "app.remember-me-key=test-remember-key",
        "app.newsletters.inbound-domain=news.example.com",
        "app.newsletters.inbound-secret=correct-secret"
})
class NewsletterInboundControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    FeedService feedService;

    // Not used by this controller, but AccountAdvice (a global @ControllerAdvice
    // Spring wires into every @WebMvcTest) requires one.
    @MockitoBean
    CurrentUser currentUser;

    private static final String PAYLOAD = """
            {
              "From": "editor@newsletter.example",
              "FromName": "The Editor",
              "To": "abc123@news.example.com",
              "Subject": "Issue #1",
              "MessageID": "msg-1",
              "Date": "Mon, 10 Aug 2026 09:00:00 +0000",
              "HtmlBody": "<p>Hello subscriber</p>",
              "TextBody": "Hello subscriber"
            }
            """;

    @Test
    void wrongSecretIsRejected() throws Exception {
        when(feedService.findNewsletterFeedIdByToken(anyString())).thenReturn(Optional.of(2L));

        mockMvc.perform(post("/inbound/newsletters").param("secret", "wrong")
                        .contentType("application/json").content(PAYLOAD))
                .andExpect(status().isUnauthorized());

        verify(feedService, never()).receiveNewsletterIssue(anyLong(), anyString(), any(), any(), any(), anyString());
    }

    @Test
    void missingSecretIsRejected() throws Exception {
        mockMvc.perform(post("/inbound/newsletters")
                        .contentType("application/json").content(PAYLOAD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unmatchedRecipientIsReportedAsNotFound() throws Exception {
        when(feedService.findNewsletterFeedIdByToken(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(post("/inbound/newsletters").param("secret", "correct-secret")
                        .contentType("application/json").content(PAYLOAD))
                .andExpect(status().isNotFound());

        verify(feedService, never()).receiveNewsletterIssue(anyLong(), anyString(), any(), any(), any(), anyString());
    }

    @Test
    void aMatchingIssueIsStoredUnderItsNewsletterFeed() throws Exception {
        when(feedService.findNewsletterFeedIdByToken("abc123")).thenReturn(Optional.of(2L));
        when(feedService.receiveNewsletterIssue(eq(2L), eq("msg-1"), eq("Issue #1"), eq("The Editor"),
                any(), eq("<p>Hello subscriber</p>"))).thenReturn(42L);

        mockMvc.perform(post("/inbound/newsletters").param("secret", "correct-secret")
                        .contentType("application/json").content(PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("stored"))
                .andExpect(jsonPath("$.articleId").value(42));

        verify(feedService, times(1)).receiveNewsletterIssue(eq(2L), eq("msg-1"), eq("Issue #1"),
                eq("The Editor"), any(), eq("<p>Hello subscriber</p>"));
    }

    @Test
    void aRepeatedMessageIdIsReportedAsADuplicateWithoutErroring() throws Exception {
        when(feedService.findNewsletterFeedIdByToken("abc123")).thenReturn(Optional.of(2L));
        when(feedService.receiveNewsletterIssue(eq(2L), eq("msg-1"), any(), any(), any(), any()))
                .thenReturn(-1L);

        mockMvc.perform(post("/inbound/newsletters").param("secret", "correct-secret")
                        .contentType("application/json").content(PAYLOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("duplicate"));
    }
}
