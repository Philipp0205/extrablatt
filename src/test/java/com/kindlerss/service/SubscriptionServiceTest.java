package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
}
