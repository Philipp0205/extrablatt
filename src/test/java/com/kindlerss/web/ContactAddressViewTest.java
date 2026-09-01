package com.kindlerss.web;

import com.kindlerss.security.CurrentUser;
import com.kindlerss.security.RateLimiter;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.service.EntitlementService;
import com.kindlerss.service.SubscriptionService;
import com.kindlerss.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The address on the legal pages has to be one that receives mail, so it is settable
 * per deployment rather than compiled in: § 5 Abs. 1 Nr. 2 DDG wants a mailbox that is
 * really read, and a pretty alias whose forwarding is not yet arranged bounces.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({com.kindlerss.config.SecurityConfig.class, GlobalExceptionHandler.class,
        RateLimiter.class, RateLimitingFilter.class})
@TestPropertySource(properties = {
        "app.mail-from=from@example.com",
        "app.remember-me-key=test-remember-key",
        "app.billing.enabled=true",
        "app.contact-email=post@example.test"
})
class ContactAddressViewTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SubscriptionService subscriptionService;

    @MockitoBean
    EntitlementService entitlementService;

    @MockitoBean
    CurrentUser currentUser;

    @MockitoBean
    UserDetailsService userDetailsService;

    @Test
    void theImprintPublishesTheConfiguredAddressAsAContactAndForDataProtection() throws Exception {
        mockMvc.perform(get("/imprint"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("mailto:post@example.test")))
                .andExpect(content().string(not(containsString("hello@extrablatt.app"))));
    }

    @Test
    void thePrivacyNoticeAndWithdrawalPageUseTheSameAddress() throws Exception {
        mockMvc.perform(get("/privacy"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("mailto:post@example.test")));

        mockMvc.perform(get("/withdrawal"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("mailto:post@example.test")));
    }

    /**
     * Cloudflare rewrites addresses into a JavaScript-decoded placeholder unless the
     * markers are there, and this app has to be readable without JavaScript.
     */
    @Test
    void everyPublishedAddressIsExemptFromCloudflareEmailObfuscation() throws Exception {
        for (String page : new String[]{"/imprint", "/privacy", "/withdrawal"}) {
            String html = mockMvc.perform(get(page))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            int addresses = countOf(html, "mailto:post@example.test");
            assertEquals(addresses, countOf(html, "<!--email_off-->"),
                    page + " publishes " + addresses + " address(es); each needs an email_off marker");
            assertEquals(addresses, countOf(html, "<!--/email_off-->"),
                    page + " leaves an email_off marker unclosed");
        }
    }

    @Test
    void anUnsetAddressFallsBackToTheOneTheLegalPagesWereWrittenAround() {
        assertEquals(ContactAdvice.DEFAULT_CONTACT_EMAIL, new ContactAdvice(null).contactEmail());
        assertEquals(ContactAdvice.DEFAULT_CONTACT_EMAIL, new ContactAdvice("  ").contactEmail());
        assertEquals("post@example.test", new ContactAdvice(" post@example.test ").contactEmail());
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }
}
