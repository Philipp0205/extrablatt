package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.SubscriptionStatus;
import com.kindlerss.repository.BillingEventRepository;
import com.kindlerss.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingWebhookServiceTest {

    private static final String SECRET = "whsec_test";

    private final BillingEventRepository events = mock(BillingEventRepository.class);
    private final SubscriptionService subscriptions = mock(SubscriptionService.class);
    private final UserRepository users = mock(UserRepository.class);

    private BillingWebhookService service(String provider) {
        AppProperties.Billing billing = new AppProperties.Billing(true, provider, SECRET,
                "https://pay/monthly", "https://pay/yearly", null, null, null,
                null, null, null, null, null);
        AppProperties properties = new AppProperties("from@example.com", null, null, null, null,
                null, null, null, billing, null);
        return new BillingWebhookService(events, subscriptions, users, properties);
    }

    @Test
    void aStripeCheckoutTiesTheProviderIdsToTheAccountThatOrdered() {
        when(events.claim(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(true);
        String body = """
                {"id":"evt_1","type":"checkout.session.completed","data":{"object":{
                  "id":"cs_1","client_reference_id":"42","customer":"cus_9","subscription":"sub_7"}}}
                """;

        assertEquals(BillingWebhookService.Result.ACCEPTED, handle("stripe", body));

        ProviderSubscriptionUpdate update = captureUpdate();
        assertEquals(42L, update.userId());
        assertEquals("cus_9", update.providerCustomerId());
        assertEquals("sub_7", update.providerSubscriptionId());
        assertEquals(SubscriptionStatus.ACTIVE, update.status());
    }

    @Test
    void aStripeSubscriptionUpdateCarriesTheStatusAndPeriodEnd() {
        when(events.claim(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(true);
        String body = """
                {"id":"evt_2","type":"customer.subscription.updated","data":{"object":{
                  "id":"sub_7","customer":"cus_9","status":"past_due",
                  "current_period_end":1799999999,"cancel_at_period_end":true,
                  "items":{"data":[{"price":{"recurring":{"interval":"year"}}}]}}}}
                """;

        assertEquals(BillingWebhookService.Result.ACCEPTED, handle("stripe", body));

        ProviderSubscriptionUpdate update = captureUpdate();
        assertEquals(SubscriptionStatus.PAST_DUE, update.status());
        assertEquals(Instant.ofEpochSecond(1799999999L), update.currentPeriodEnd());
        assertEquals(true, update.cancelAtPeriodEnd());
        assertEquals("YEARLY", update.interval().name());
    }

    @Test
    void aPaddleSubscriptionUpdateIsUnderstoodToo() {
        when(events.claim(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(true);
        String body = """
                {"event_id":"evt_p1","event_type":"subscription.updated","data":{
                  "id":"sub_abc","status":"active","customer_id":"ctm_1",
                  "custom_data":{"user_id":"7"},
                  "current_billing_period":{"ends_at":"2027-03-01T00:00:00Z"},
                  "scheduled_change":{"action":"cancel"},
                  "items":[{"price":{"billing_cycle":{"interval":"month"}}}]}}
                """;

        assertEquals(BillingWebhookService.Result.ACCEPTED, handle("paddle", body));

        ProviderSubscriptionUpdate update = captureUpdate();
        assertEquals(7L, update.userId());
        assertEquals("sub_abc", update.providerSubscriptionId());
        assertEquals(SubscriptionStatus.ACTIVE, update.status());
        assertEquals(true, update.cancelAtPeriodEnd());
        assertEquals("MONTHLY", update.interval().name());
    }

    /**
     * Both providers retry, and both occasionally deliver twice. A second delivery
     * must not buy a second period.
     */
    @Test
    void aReplayedEventIsAcceptedWithoutBeingAppliedAgain() {
        when(events.claim(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(false);
        String body = """
                {"id":"evt_1","type":"customer.subscription.updated","data":{"object":{
                  "id":"sub_7","status":"active","current_period_end":1799999999}}}
                """;

        assertEquals(BillingWebhookService.Result.ACCEPTED, handle("stripe", body));

        verify(subscriptions, never()).applyProviderUpdate(any());
    }

    @Test
    void aForgedSignatureChangesNothing() {
        String body = """
                {"id":"evt_1","type":"customer.subscription.updated","data":{"object":{
                  "id":"sub_7","status":"active"}}}
                """;

        BillingWebhookService.Result result = service("stripe")
                .handle("t=" + Instant.now().getEpochSecond() + ",v1=deadbeef",
                        body.getBytes(StandardCharsets.UTF_8));

        assertEquals(BillingWebhookService.Result.REJECTED, result);
        verify(events, never()).claim(anyString(), anyString(), anyString(), anyString(), any());
        verify(subscriptions, never()).applyProviderUpdate(any());
    }

    /** Authentic, but about something the app does not track. */
    @Test
    void anEventTypeWeDoNotCareAboutIsIgnoredWithoutBeingRecorded() {
        String body = """
                {"id":"evt_9","type":"payment_intent.created","data":{"object":{"id":"pi_1"}}}
                """;

        assertEquals(BillingWebhookService.Result.IGNORED, handle("stripe", body));

        verify(events, never()).claim(anyString(), anyString(), anyString(), anyString(), any());
    }

    /**
     * A failure is recorded with its reason rather than thrown: a 500 would make the
     * provider retry something that will fail the same way again.
     */
    @Test
    void anEventThatCannotBeAppliedIsRecordedWithItsReason() {
        when(events.claim(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(true);
        doThrowOnApply();
        String body = """
                {"id":"evt_3","type":"customer.subscription.updated","data":{"object":{
                  "id":"sub_unknown","status":"active"}}}
                """;

        assertEquals(BillingWebhookService.Result.FAILED, handle("stripe", body));

        verify(events).markFailed(eq("evt_3"), anyString());
    }

    private void doThrowOnApply() {
        org.mockito.Mockito.doThrow(new IllegalStateException("No account matches subscription"))
                .when(subscriptions).applyProviderUpdate(any());
    }

    private BillingWebhookService.Result handle(String provider, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        long seconds = Instant.now().getEpochSecond();
        String header = "stripe".equals(provider)
                ? "t=" + seconds + ",v1=" + hmacHex(seconds + "." + body)
                : "ts=" + seconds + ";h1=" + hmacHex(seconds + ":" + body);
        return service(provider).handle(header, bytes);
    }

    /**
     * Signs the way the provider would. Computed here rather than borrowed from
     * production code, so that a mistake in the signing helper shows up as a failing
     * test instead of cancelling itself out.
     */
    private static String hmacHex(String signedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ProviderSubscriptionUpdate captureUpdate() {
        ArgumentCaptor<ProviderSubscriptionUpdate> captor =
                ArgumentCaptor.forClass(ProviderSubscriptionUpdate.class);
        verify(subscriptions).applyProviderUpdate(captor.capture());
        return captor.getValue();
    }
}
