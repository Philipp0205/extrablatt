package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.AppUser;
import com.kindlerss.domain.BillingInterval;
import com.kindlerss.domain.CancellationRequest;
import com.kindlerss.domain.Plan;
import com.kindlerss.domain.Subscription;
import com.kindlerss.domain.SubscriptionStatus;
import com.kindlerss.repository.CancellationRequestRepository;
import com.kindlerss.repository.SubscriptionRepository;
import com.kindlerss.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionServiceTest {

    private static final long UID = 9L;

    private final SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
    private final CancellationRequestRepository cancellations = mock(CancellationRequestRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final BillingMailService mail = mock(BillingMailService.class);

    private SubscriptionService service(boolean billingOn) {
        AppProperties.Billing billing = new AppProperties.Billing(billingOn, "stripe", "whsec",
                "https://pay/monthly", "https://pay/yearly", null, null, null,
                null, null, null, null, null, null);
        AppProperties properties = new AppProperties("from@example.com", null, null, null, null,
                null, null, null, billing, null);
        return new SubscriptionService(subscriptions, cancellations, users, mail, properties);
    }

    @Test
    void startTrialWritesAWeekOfSupporterAccess() {
        when(subscriptions.findByUserId(UID)).thenReturn(Optional.empty());

        service(true).startTrial(UID);

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptions).save(captor.capture());
        Subscription saved = captor.getValue();
        assertEquals(Plan.SUPPORTER, saved.plan());
        assertEquals(SubscriptionStatus.TRIALING, saved.status());
        assertTrue(saved.currentPeriodEnd().isAfter(Instant.now().plus(6, ChronoUnit.DAYS)));
        assertTrue(saved.cancelAtPeriodEnd());
    }

    @Test
    void startTrialDoesNothingWhenBillingIsOff() {
        service(false).startTrial(UID);
        verify(subscriptions, never()).save(org.mockito.ArgumentMatchers.any());
    }

    /**
     * The Settings page reads this, and has to say the same thing the send path
     * acts on: an account with nothing on file predates the charging and keeps
     * the Supporter plan for good.
     */
    @Test
    void anAccountWithNothingOnFileReadsAsGrandfathered() {
        when(subscriptions.findByUserId(UID)).thenReturn(Optional.empty());

        Subscription standing = service(true).forUser(UID);

        assertTrue(standing.grandfathered());
        assertEquals(Plan.SUPPORTER, standing.plan());
    }

    @Test
    void startTrialDoesNotReplaceAnExistingStanding() {
        when(subscriptions.findByUserId(UID)).thenReturn(Optional.of(
                new Subscription(UID, Plan.FREE, SubscriptionStatus.EXPIRED, null, null, null, null,
                        Instant.now(), false, null)));

        service(true).startTrial(UID);

        verify(subscriptions, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private static final String OWNER_EMAIL = "owner@example.com";
    private static final Instant PERIOD_END = Instant.parse("2027-03-01T00:00:00Z");

    private void anActiveSubscriber() {
        when(users.findByEmail(OWNER_EMAIL)).thenReturn(Optional.of(owner()));
        when(users.findById(UID)).thenReturn(Optional.of(owner()));
        when(subscriptions.findByUserId(UID)).thenReturn(Optional.of(new Subscription(UID,
                Plan.SUPPORTER, SubscriptionStatus.ACTIVE, BillingInterval.YEARLY, "stripe",
                "cus", "sub", PERIOD_END, false, null)));
        when(cancellations.insert(any(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> new CancellationRequest(77L, call.getArgument(0),
                        call.getArgument(1), call.getArgument(2), call.getArgument(3),
                        call.getArgument(4), call.getArgument(5), call.getArgument(6),
                        call.getArgument(7), Instant.now()));
    }

    private static AppUser owner() {
        return new AppUser(UID, OWNER_EMAIL, "hash", null, Instant.now(), null, Instant.now(),
                Instant.now(), "inbound", false, null);
    }

    private static SubscriptionService.Declaration declaration(CancellationRequest.Kind kind) {
        return new SubscriptionService.Declaration(OWNER_EMAIL, "Someone", "ref",
                kind, null, "a message for the owner");
    }

    /** The signed-in owner is who § 312k is for, and nothing stands in their way. */
    @Test
    void theSignedInOwnerCancelsStraightAway() {
        anActiveSubscriber();

        SubscriptionService.Outcome outcome = service(true)
                .cancel(declaration(CancellationRequest.Kind.ORDINARY), UID);

        assertTrue(outcome.applied());
        assertEquals(SubscriptionService.Route.OWNER, outcome.route());
        assertEquals(PERIOD_END, outcome.effectiveAt());
        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptions).save(saved.capture());
        assertEquals(SubscriptionStatus.CANCELED, saved.getValue().status());
        verify(mail).sendCancellationConfirmation(OWNER_EMAIL, outcome);
        verify(mail, never()).sendCancellationLink(anyString(), any(), anyString());
    }

    /**
     * Knowing an address is not enough to end somebody's subscription. Ordinary
     * notice included: it is recorded, and the owner gets a link instead.
     */
    @Test
    void someoneWhoIsNotSignedInOnlyTriggersALinkToTheOwner() {
        anActiveSubscriber();

        SubscriptionService.Outcome outcome = service(true)
                .cancel(declaration(CancellationRequest.Kind.ORDINARY), null);

        assertFalse(outcome.applied());
        assertTrue(outcome.awaitingOwner());
        assertEquals(SubscriptionService.Route.LINK_SENT, outcome.route());
        verify(subscriptions, never()).save(any());
        verify(cancellations).insert(eq(UID), eq(OWNER_EMAIL), any(), any(),
                eq(CancellationRequest.Kind.ORDINARY), any(), any(), isNull());
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(cancellations).insertToken(hash.capture(), eq(77L), eq(UID), any());
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(mail).sendCancellationLink(eq(OWNER_EMAIL), any(), token.capture());
        // Only the hash is stored, so a copy of the database confirms nothing.
        assertNotEquals(token.getValue(), hash.getValue());
        assertEquals(SubscriptionService.hashToken(token.getValue()), hash.getValue());
        verify(mail, never()).sendCancellationConfirmation(anyString(), any());
        verify(mail).notifyOperatorOfCancellation(outcome);
    }

    /** Signed in as somebody else is no better than not signed in. */
    @Test
    void anotherSignedInAccountIsTreatedLikeAStranger() {
        anActiveSubscriber();

        SubscriptionService.Outcome outcome = service(true)
                .cancel(declaration(CancellationRequest.Kind.IMMEDIATE), UID + 1);

        assertEquals(SubscriptionService.Route.LINK_SENT, outcome.route());
        verify(subscriptions, never()).save(any());
    }

    /**
     * An address with no account behind it is still recorded, but nobody is mailed:
     * the form must not be a way to send mail to arbitrary addresses.
     */
    @Test
    void anUnknownAddressIsRecordedAndNobodyIsMailed() {
        when(users.findByEmail("stranger@example.com")).thenReturn(Optional.empty());
        when(cancellations.insert(any(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new CancellationRequest(78L, null, "stranger@example.com", null, null,
                        CancellationRequest.Kind.ORDINARY, null, null, null, Instant.now()));

        SubscriptionService.Outcome outcome = service(true).cancel(
                new SubscriptionService.Declaration("stranger@example.com", null, null,
                        CancellationRequest.Kind.ORDINARY, null, "hello"), null);

        assertEquals(SubscriptionService.Route.RECORDED, outcome.route());
        verify(mail, never()).sendCancellationLink(anyString(), any(), anyString());
        verify(mail, never()).sendCancellationConfirmation(anyString(), any());
        verify(mail).notifyOperatorOfCancellation(outcome);
    }

    /** The public form cannot be used to flood an owner's inbox with links. */
    @Test
    void linksToOneAccountAreLimitedPerDay() {
        anActiveSubscriber();
        when(cancellations.countTokensSince(eq(UID), any()))
                .thenReturn(SubscriptionService.MAX_LINKS_PER_WINDOW);

        SubscriptionService.Outcome outcome = service(true)
                .cancel(declaration(CancellationRequest.Kind.ORDINARY), null);

        assertEquals(SubscriptionService.Route.RECORDED, outcome.route());
        verify(cancellations, never()).insertToken(anyString(), anyLong(), anyLong(), any());
        verify(mail, never()).sendCancellationLink(anyString(), any(), anyString());
        verify(cancellations).insert(any(), anyString(), any(), any(), any(), any(), any(), any());
    }

    /** Following the link is as good as being signed in, immediate notice included. */
    @Test
    void theOwnersLinkAppliesTheCancellation() {
        anActiveSubscriber();
        String token = "raw-token";
        when(cancellations.consumeToken(SubscriptionService.hashToken(token)))
                .thenReturn(Optional.of(new CancellationRequestRepository.PendingToken(77L, UID)));
        when(cancellations.findById(77L)).thenReturn(new CancellationRequest(77L, UID, OWNER_EMAIL,
                "Someone", null, CancellationRequest.Kind.IMMEDIATE, null, "free text", null,
                Instant.now()));

        SubscriptionService.Outcome outcome = service(true).confirmByLink(token).orElseThrow();

        assertTrue(outcome.applied());
        assertEquals(SubscriptionService.Route.CONFIRMED_BY_LINK, outcome.route());
        assertFalse(outcome.echoDeclaration(), "the owner did not necessarily write the free text");
        ArgumentCaptor<Subscription> saved = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptions).save(saved.capture());
        assertEquals(SubscriptionStatus.EXPIRED, saved.getValue().status());
        verify(cancellations).recordEffect(eq(77L), any(), eq(true));
        verify(mail).sendCancellationConfirmation(OWNER_EMAIL, outcome);
        verify(mail).notifyOperatorOfCancellation(outcome);
    }

    @Test
    void aSpentOrUnknownLinkChangesNothing() {
        when(cancellations.consumeToken(anyString())).thenReturn(Optional.empty());

        assertTrue(service(true).confirmByLink("whatever").isEmpty());
        assertTrue(service(true).confirmByLink("").isEmpty());
        assertTrue(service(true).confirmByLink(null).isEmpty());

        verify(subscriptions, never()).save(any());
        verify(mail, never()).sendCancellationConfirmation(anyString(), any());
    }

    /** Looking at the link must not spend it; mail scanners open links. */
    @Test
    void peekingAtALinkDoesNotSpendIt() {
        when(cancellations.findUsableToken(SubscriptionService.hashToken("t")))
                .thenReturn(Optional.of(new CancellationRequestRepository.PendingToken(77L, UID)));
        when(cancellations.findById(77L)).thenReturn(new CancellationRequest(77L, UID, OWNER_EMAIL,
                null, null, CancellationRequest.Kind.ORDINARY, null, null, null, Instant.now()));

        assertTrue(service(true).peekLink("t").isPresent());

        verify(cancellations, never()).consumeToken(anyString());
        verify(subscriptions, never()).save(any());
    }
}
