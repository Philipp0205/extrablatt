package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import com.kindlerss.repository.RetentionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The retention sweep decides how long personal data lives, so the two things worth
 * pinning down are that it uses the configured period rather than a hardcoded one,
 * and that switching a sweep off really switches it off.
 */
class RetentionServiceTest {

    private final RetentionRepository retention = mock(RetentionRepository.class);

    private RetentionService service(AppProperties.Retention policy) {
        AppProperties properties = new AppProperties("from@example.com", null, null, null, null,
                null, null, null, null, policy);
        return new RetentionService(retention, properties);
    }

    @Test
    void eachSweepIsRunAgainstItsOwnConfiguredCutoff() {
        when(retention.deleteSendEventsBefore(any())).thenReturn(3);
        when(retention.redactBillingPayloadsBefore(any())).thenReturn(2);
        when(retention.deleteSpentTokensBefore(any())).thenReturn(1);
        when(retention.clearStaleArticleCacheBefore(any())).thenReturn(4);
        Instant before = Instant.now();

        RetentionService.Result result = service(new AppProperties.Retention(730, 90, 30, 365)).sweep();

        assertEquals(new RetentionService.Result(3, 2, 1, 4), result);
        assertCutoffAbout(730, before, captureSendEventCutoff());
        assertCutoffAbout(90, before, captureBillingCutoff());
    }

    /** An operator who has a reason to keep something can, and then nothing is deleted. */
    @Test
    void zeroDaysSwitchesASweepOffEntirely() {
        RetentionService.Result result = service(new AppProperties.Retention(0, 0, 0, 0)).sweep();

        assertEquals(new RetentionService.Result(0, 0, 0, 0), result);
        verify(retention, never()).deleteSendEventsBefore(any());
        verify(retention, never()).redactBillingPayloadsBefore(any());
        verify(retention, never()).deleteSpentTokensBefore(any());
        verify(retention, never()).clearStaleArticleCacheBefore(any());
    }

    /**
     * Erasure has to reach payment payloads explicitly, because their event ids
     * deliberately have no cascade to follow.
     */
    @Test
    void erasingAnAccountRedactsItsPaymentPayloads() {
        when(retention.redactBillingPayloadsForUser(42L)).thenReturn(1);

        service(new AppProperties.Retention(null, null, null, null)).eraseForUser(42L);

        verify(retention).redactBillingPayloadsForUser(42L);
    }

    private Instant captureSendEventCutoff() {
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(retention).deleteSendEventsBefore(captor.capture());
        return captor.getValue();
    }

    private Instant captureBillingCutoff() {
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(retention).redactBillingPayloadsBefore(captor.capture());
        return captor.getValue();
    }

    private static void assertCutoffAbout(int days, Instant runAt, Instant cutoff) {
        Duration age = Duration.between(cutoff, runAt);
        assertTrue(Math.abs(age.toDays() - days) <= 1,
                "expected a cutoff about " + days + " days back, got " + age.toDays());
    }
}
