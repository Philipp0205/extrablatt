package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.AppUser;
import com.kindlerss.domain.Entitlement;
import com.kindlerss.domain.Plan;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.CurrentUser;
import com.kindlerss.service.EntitlementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The entitlement (2 SQL queries) is looked up once per request, however many model attributes need it. */
class AccountAdviceEntitlementCachingTest {

    private static final long UID = 1L;

    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final EntitlementService entitlementService = mock(EntitlementService.class);
    private final AppProperties properties = mock(AppProperties.class);
    private final AppProperties.Billing billing = mock(AppProperties.Billing.class);

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private AccountAdvice signedInAdvice() {
        AppUser user = new AppUser(UID, "user@example.com", "hash", "reader@kindle.com",
                Instant.now(), null, Instant.now(), Instant.now());
        when(properties.billing()).thenReturn(billing);
        when(billing.enabled()).thenReturn(true);
        when(currentUser.details()).thenReturn(Optional.of(new AppUserDetails(user)));
        when(entitlementService.forUser(anyLong()))
                .thenReturn(new Entitlement(Plan.SUPPORTER, 50, 0, 50, true));
        return new AccountAdvice(currentUser, entitlementService, properties);
    }

    private static void startRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    private static void renderAllAttributes(AccountAdvice advice) {
        advice.supporter();
        advice.onTrial();
        advice.trialEndsOn();
        advice.trialEndedPrompt();
    }

    @Test
    void oneRequestLooksTheEntitlementUpOnceAndASecondRequestLooksItUpAgain() {
        AccountAdvice advice = signedInAdvice();

        startRequest();
        assertTrue(advice.supporter());
        assertFalse(advice.onTrial());
        renderAllAttributes(advice);
        verify(entitlementService, times(1)).forUser(UID);

        startRequest();
        renderAllAttributes(advice);
        verify(entitlementService, times(2)).forUser(UID);
    }

    @Test
    void nobodySignedInIsAlsoLookedUpOncePerRequest() {
        when(properties.billing()).thenReturn(billing);
        when(billing.enabled()).thenReturn(true);
        when(currentUser.details()).thenReturn(Optional.empty());
        AccountAdvice advice = new AccountAdvice(currentUser, entitlementService, properties);

        startRequest();
        renderAllAttributes(advice);
        renderAllAttributes(advice);

        assertFalse(advice.supporter());
        verify(currentUser, times(1)).details();
    }
}
