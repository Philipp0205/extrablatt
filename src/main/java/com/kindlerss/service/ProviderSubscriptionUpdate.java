package com.kindlerss.service;

import com.kindlerss.domain.BillingInterval;
import com.kindlerss.domain.SubscriptionStatus;

import java.time.Instant;

/**
 * One provider callback, reduced to the handful of facts the app actually stores.
 * Stripe and Paddle describe a subscription very differently; each parser flattens
 * its own vocabulary into this, so nothing provider-shaped reaches the database.
 *
 * <p>{@code userId} is present when the provider echoed back the reference sent at
 * checkout. When it is not, the subscription is found by provider id instead.
 */
public record ProviderSubscriptionUpdate(
        String eventId,
        String type,
        Long userId,
        String providerCustomerId,
        String providerSubscriptionId,
        SubscriptionStatus status,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        BillingInterval interval
) {
}
