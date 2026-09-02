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

    /**
     * Where an account with no row of its own stands.
     *
     * <p>Once a deployment charges, registration writes a trial row for every new
     * account, so no row means the account is older than the charging is. Those keep
     * the full allowances for good, on the same terms as the accounts grandfathered
     * when subscriptions were first introduced: they signed up for something
     * advertised as having no subscription. Reading the absence as a spent free plan
     * instead refuses them Kindle delivery over a week they were never given — which
     * is what happens on staging, whose database is a copy of production's and so
     * carries the accounts of a deployment that does not charge.
     *
     * <p>Where nothing is charged the plan comes from the flag alone and this is only
     * a placeholder.
     */
    public static Subscription notOnFile(long userId, boolean charging) {
        if (!charging) {
            return free(userId);
        }
        return new Subscription(userId, Plan.SUPPORTER, SubscriptionStatus.GRANDFATHERED,
                null, null, null, null, null, false, null);
    }

    /** Never charged, and never to be charged. */
    public boolean grandfathered() {
        return status == SubscriptionStatus.GRANDFATHERED;
    }

    /** The complimentary first week, with no payment on file. */
    public boolean trialing() {
        return status == SubscriptionStatus.TRIALING;
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
