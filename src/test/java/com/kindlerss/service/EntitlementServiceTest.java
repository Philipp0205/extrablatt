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
                null, null, null, null, null, null);
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
    void anAccountThatNeverOrderedHasNoKindleSendsAfterTheTrialModel() {
        when(subscriptions.findByUserId(UID)).thenReturn(Optional.of(
                new Subscription(UID, Plan.FREE, SubscriptionStatus.FREE, null, null,
                        null, null, null, false, null)));

        Entitlement entitlement = service(true).forUser(UID);

        assertEquals(Plan.FREE, entitlement.plan());
        assertFalse(entitlement.hasMonthlyCap());
        assertEquals(0, entitlement.maxSendsPerMonth());
        assertEquals(0, entitlement.maxSendsPerDay());
        assertEquals(0, entitlement.maxFeeds());
        assertFalse(entitlement.newsletters());
        assertFalse(entitlement.onTrial());
        assertFalse(entitlement.trialExpired());
    }

    /**
     * The case staging is made of: its database is a copy of production's, which
     * charges nothing and so writes no subscription row for any account it
     * registers. Read as a spent free plan, every one of those readers is refused
     * Kindle delivery over a free week they were never given.
     */
    @Test
    void anAccountFromBeforeChargingBeganKeepsTheFullAllowances() {
        when(subscriptions.findByUserId(UID)).thenReturn(Optional.empty());

        Entitlement entitlement = service(true).forUser(UID);

        assertEquals(Plan.SUPPORTER, entitlement.plan());
        assertTrue(entitlement.paid());
        assertFalse(entitlement.onTrial(), "it is permanent, not a week that runs out");
        assertEquals(50, entitlement.maxSendsPerDay());
        assertEquals(50, entitlement.maxFeeds());
        assertTrue(entitlement.newsletters());
    }

    @Test
    void aLiveTrialGetsTheFullAllowancesUntilItEnds() {
        Instant ends = Instant.now().plus(3, ChronoUnit.DAYS);
        when(subscriptions.findByUserId(UID)).thenReturn(Optional.of(
                new Subscription(UID, Plan.SUPPORTER, SubscriptionStatus.TRIALING, null, null,
                        null, null, ends, true, null)));

        Entitlement entitlement = service(true).forUser(UID);

        assertEquals(Plan.SUPPORTER, entitlement.plan());
        assertTrue(entitlement.onTrial());
        assertEquals(ends, entitlement.trialEndsAt());
        assertFalse(entitlement.trialExpired());
        assertFalse(entitlement.hasMonthlyCap());
        assertEquals(50, entitlement.maxSendsPerDay());
        assertEquals(50, entitlement.maxFeeds());
        assertTrue(entitlement.newsletters());
    }

    @Test
    void anEndedTrialGrantsNothing() {
        when(subscriptions.findByUserId(UID)).thenReturn(Optional.of(
                new Subscription(UID, Plan.SUPPORTER, SubscriptionStatus.TRIALING, null, null,
                        null, null, Instant.now().minus(1, ChronoUnit.HOURS), true, null)));

        Entitlement entitlement = service(true).forUser(UID);

        assertEquals(Plan.FREE, entitlement.plan());
        assertFalse(entitlement.onTrial());
        assertTrue(entitlement.trialExpired());
    }

    @Test
    void aSweptEndedTrialIsStillAnEndedTrial() {
        when(subscriptions.findByUserId(UID)).thenReturn(Optional.of(
                new Subscription(UID, Plan.FREE, SubscriptionStatus.EXPIRED, null, null,
                        null, null, Instant.now().minus(1, ChronoUnit.DAYS), true, null)));

        assertTrue(service(true).forUser(UID).trialExpired());
    }

    @Test
    void aLapsedPaidSubscriptionIsNotAnEndedTrial() {
        when(subscriptions.findByUserId(UID)).thenReturn(Optional.of(
                subscription(SubscriptionStatus.EXPIRED, Instant.now().minus(1, ChronoUnit.DAYS), true)));

        assertFalse(service(true).forUser(UID).trialExpired());
    }

    @Test
    void startingCheckoutDoesNotCutARunningTrialShort() {
        Instant ends = Instant.now().plus(2, ChronoUnit.DAYS);
        Subscription pendingDuringTrial = new Subscription(UID, Plan.SUPPORTER,
                SubscriptionStatus.PENDING, BillingInterval.YEARLY, "stripe", null, null,
                ends, false, Instant.now());

        assertTrue(service(true).hasPaidAccess(pendingDuringTrial, Instant.now()));
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
        when(subscriptions.findByUserId(UID)).thenReturn(Optional.of(
                new Subscription(UID, Plan.FREE, SubscriptionStatus.EXPIRED, null, null, null,
                        null, Instant.now().minus(1, ChronoUnit.DAYS), true, null)));
        when(sendLimits.findByUserId(UID))
                .thenReturn(Optional.of(new UserSendLimit(UID, 25, null)));

        Entitlement entitlement = service(true).forUser(UID);

        assertEquals(Plan.FREE, entitlement.plan());
        assertEquals(25, entitlement.maxSendsPerDay());
        assertEquals(0, entitlement.maxSendsPerMonth());
        assertEquals(0, entitlement.maxFeeds());
    }

    private static Subscription subscription(SubscriptionStatus status, Instant periodEnd,
                                             boolean cancelAtPeriodEnd) {
        return new Subscription(UID, Plan.SUPPORTER, status, BillingInterval.YEARLY, "stripe",
                "cus_1", "sub_1", periodEnd, cancelAtPeriodEnd, Instant.now());
    }
}
