package com.kindlerss.domain;

import java.time.Instant;

/**
 * An account's standing with the payment provider. A missing row means the free
 * plan, so {@link #free(long)} stands in rather than an empty Optional leaking
 * into every caller.
 */
public record Subscription(
        long userId,
        Plan plan,
        SubscriptionStatus status,
        BillingInterval interval,
        String provider,
        String providerCustomerId,
        String providerSubscriptionId,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        Instant withdrawalConsentAt
) {

    public static Subscription free(long userId) {
        return new Subscription(userId, Plan.FREE, SubscriptionStatus.FREE,
                null, null, null, null, null, false, null);
    }

    /** Never charged, and never to be charged. */
    public boolean grandfathered() {
        return status == SubscriptionStatus.GRANDFATHERED;
    }

    /**
     * Whether the account is inside the period it paid for. Grandfathered accounts
     * have no end date and are always inside it.
     */
    public boolean withinPaidPeriod(Instant now) {
        if (grandfathered()) {
            return true;
        }
        return currentPeriodEnd != null && currentPeriodEnd.isAfter(now);
    }

    /** Whether anything about this subscription is worth showing in Settings. */
    public boolean everOrdered() {
        return status != SubscriptionStatus.FREE;
    }
}
