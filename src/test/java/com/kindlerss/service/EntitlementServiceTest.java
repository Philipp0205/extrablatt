package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.BillingInterval;
import com.kindlerss.domain.Entitlement;
import com.kindlerss.domain.Plan;
import com.kindlerss.domain.Subscription;
import com.kindlerss.domain.SubscriptionStatus;
import com.kindlerss.domain.UserSendLimit;
import com.kindlerss.repository.SubscriptionRepository;
import com.kindlerss.repository.UserSendLimitRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EntitlementServiceTest {

    private static final long UID = 1L;

    private final SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
    private final UserSendLimitRepository sendLimits = mock(UserSendLimitRepository.class);

    private EntitlementService service(boolean billingEnabled) {
        AppProperties.Billing billing = new AppProperties.Billing(billingEnabled, "stripe", "whsec",
                "https://pay/monthly", "https://pay/yearly", null, null, null,
                null, null, null, null, null);
        AppProperties properties = new AppProperties("from@example.com", null, null, null, null,
                null, new AppProperties.Limits(50, 50), null, billing, null);
        return new EntitlementService(subscriptions, sendLimits, properties);
    }

    /**
     * The most important case in this class: a deployment nobody configured to charge
     * must behave exactly as it did before subscriptions existed, without consulting
     * the subscription table at all.
     */
    @Test
    void withBillingOffEveryAccountKeepsTheFullAllowances() {
        Entitlement entitlement = service(false).forUser(UID);

        assertEquals(Plan.SUPPORTER, entitlement.plan());
        assertEquals(50, entitlement.maxSendsPerDay());
        assertEquals(50, entitlement.maxFeeds());
        assertTrue(entitlement.newsletters());
        assertFalse(entitlement.hasMonthlyCap(), "nothing is metered by the month");
    }

    @Test
    void anAccountThatNeverOrderedGetsTheFreeMonthlyAllowance() {
        when(subscriptions.findByUserId(UID)).thenReturn(Optional.empty());

        Entitlement entitlement = service(true).forUser(UID);

        assertEquals(Plan.FREE, entitlement.plan());
        assertTrue(entitlement.hasMonthlyCap());
        assertEquals(5, entitlement.maxSendsPerMonth());
        // The daily cap matches the monthly one, so all five can go in one morning.
        assertEquals(5, entitlement.maxSendsPerDay());
        assertEquals(15, entitlement.maxFeeds());
        assertFalse(entitlement.newsletters());
    }

    /** Paying removes the monthly meter; the daily guardrail stays for everyone. */
    @Test
    void theSupporterPlanHasNoMonthlyCapButKeepsTheDailyGuardrail() {
        when(subscriptions.findByUserId(UID)).thenReturn(Optional.of(
                subscription(SubscriptionStatus.ACTIVE, Instant.now().plus(30, ChronoUnit.DAYS), false)));

        Entitlement entitlement = service(true).forUser(UID);

        assertFalse(entitlement.hasMonthlyCap());
        assertEquals(50, entitlement.maxSendsPerDay());
    }

    /**
     * The allowance is a calendar month, so it refills on the first rather than
     * drifting with whenever the account happened to be created.
     */
    @Test
    void theFreeAllowanceResetsOnTheFirstOfTheMonth() {
        EntitlementService service = service(true);

        LocalDate start = LocalDate.ofInstant(service.startOfCurrentMonth(),
                ZoneId.of("Europe/Berlin"));

        assertEquals(1, start.getDayOfMonth());
        assertEquals(1, service.nextResetDate().getDayOfMonth());
        assertTrue(service.nextResetDate().isAfter(start));
    }

    @Test
    void anActiveSubscriptionGetsTheFullAllowances() {
        when(subscriptions.findByUserId(UID)).thenReturn(Optional.of(
                subscription(SubscriptionStatus.ACTIVE, Instant.now().plus(30, ChronoUnit.DAYS), false)));

        assertEquals(Plan.SUPPORTER, service(true).forUser(UID).plan());
    }

    /**
     * Accounts from before charging began were promised a service with no
     * subscriptions. They keep everything, with no end date to go stale.
     */
    @Test
    void grandfatheredAccountsNeverLapse() {
        when(subscriptions.findByUserId(UID)).thenReturn(Optional.of(
                subscription(SubscriptionStatus.GRANDFATHERED, null, false)));

        assertEquals(Plan.SUPPORTER, service(true).forUser(UID).plan());
    }

    /** A year that was paid for is a year that was bought, cancellation or not. */
    @Test
    void aCancelledSubscriptionKeepsAccessUntilThePeriodItPaidForRunsOut() {
        EntitlementService service = service(true);
        Subscription cancelled = subscription(SubscriptionStatus.CANCELED,
                Instant.now().plus(10, ChronoUnit.DAYS), true);
        Subscription lapsed = subscription(SubscriptionStatus.CANCELED,
                Instant.now().minus(1, ChronoUnit.DAYS), true);

        assertTrue(service.hasPaidAccess(cancelled, Instant.now()));
        assertFalse(service.hasPaidAccess(lapsed, Instant.now()));
    }

    /**
     * A card that fails overnight should not cut a reader off in the middle of a
     * morning, and the same grace covers a renewal whose webhook never arrived.
     */
    @Test
    void aFailedRenewalKeepsAccessForTheGracePeriodAndNoLonger() {
        EntitlementService service = service(true);
        Subscription inGrace = subscription(SubscriptionStatus.PAST_DUE,
                Instant.now().minus(3, ChronoUnit.DAYS), false);
        Subscription pastGrace = subscription(SubscriptionStatus.PAST_DUE,
                Instant.now().minus(8, ChronoUnit.DAYS), false);

        assertTrue(service.hasPaidAccess(inGrace, Instant.now()));
        assertFalse(service.hasPaidAccess(pastGrace, Instant.now()));
    }

    /** An order that was started and never paid for grants nothing. */
    @Test
    void aPendingOrderGrantsNothing() {
        when(subscriptions.findByUserId(UID)).thenReturn(Optional.of(
                subscription(SubscriptionStatus.PENDING, null, false)));

        assertEquals(Plan.FREE, service(true).forUser(UID).plan());
    }

    /**
     * An administrator's custom limit is a decision about one account and outranks
     * whatever the plan would otherwise allow, in either direction.
     */
    @Test
    void anAdministratorsCustomLimitOverridesThePlan() {
        when(subscriptions.findByUserId(UID)).thenReturn(Optional.empty());
        when(sendLimits.findByUserId(UID))
                .thenReturn(Optional.of(new UserSendLimit(UID, 25, null)));

        Entitlement entitlement = service(true).forUser(UID);

        assertEquals(Plan.FREE, entitlement.plan());
        assertEquals(25, entitlement.maxSendsPerDay());
        // The override is about daily behaviour and deliberately leaves the month alone.
        assertEquals(5, entitlement.maxSendsPerMonth());
        // The feed cap is not something the override covers, so the plan still sets it.
        assertEquals(15, entitlement.maxFeeds());
    }

    private static Subscription subscription(SubscriptionStatus status, Instant periodEnd,
                                             boolean cancelAtPeriodEnd) {
        return new Subscription(UID, Plan.SUPPORTER, status, BillingInterval.YEARLY, "stripe",
                "cus_1", "sub_1", periodEnd, cancelAtPeriodEnd, Instant.now());
    }
}
