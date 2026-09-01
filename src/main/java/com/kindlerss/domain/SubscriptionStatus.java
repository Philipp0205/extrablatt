package com.kindlerss.domain;

/**
 * Where a subscription stands with the payment provider. Only
 * {@link com.kindlerss.service.EntitlementService} decides what a status means
 * for access, because that also depends on {@code current_period_end} and the
 * configured grace period.
 */
public enum SubscriptionStatus {

    /** No paid subscription. The state of every account that never ordered one. */
    FREE,

    /**
     * The first week of a new account, with the full paid allowances and no
     * card required. Access ends at {@code current_period_end}; it does not renew.
     */
    TRIALING,

    /**
     * An order was placed and the reader was sent to the payment provider, but no
     * confirmation has arrived. Grants nothing on its own: the webhook is what
     * activates. A still-running trial keeps its period end through this state so
     * starting checkout does not cut the week short.
     */
    PENDING,

    /** Paid and current. */
    ACTIVE,

    /**
     * A renewal payment failed. Access continues for the configured grace period
     * so a declined card does not cut off a reader in the middle of a morning.
     */
    PAST_DUE,

    /**
     * Cancelled, but the period that was paid for has not run out yet. Access
     * continues until {@code current_period_end}.
     */
    CANCELED,

    /** Cancelled or lapsed, and the paid period is over. */
    EXPIRED,

    /**
     * Registered before charging began, and therefore never charged. Extrablatt
     * was advertised as having no subscriptions; these accounts keep the full
     * allowances permanently.
     */
    GRANDFATHERED
}
