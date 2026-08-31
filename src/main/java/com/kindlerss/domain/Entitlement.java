package com.kindlerss.domain;

/**
 * The allowances actually in force for one account, after the plan, any
 * administrator override and the billing feature flag have all had their say.
 * Callers ask for this instead of reading limits from configuration, so there is
 * one answer to "how many sends does this account get" rather than several.
 *
 * <p>Two windows, because they answer different questions. {@code maxSendsPerMonth}
 * is the free plan: ten articles a month is the offer, and running out of them is
 * what a subscription is for. {@code maxSendsPerDay} is an abuse guardrail that
 * applies to everyone including subscribers, so that one account cannot empty the
 * e-mail budget in an afternoon.
 */
public record Entitlement(
        Plan plan,
        int maxSendsPerDay,
        int maxSendsPerMonth,
        int maxFeeds,
        boolean newsletters
) {

    public boolean paid() {
        return plan == Plan.SUPPORTER;
    }

    /** Zero or less means no monthly cap, which is what the paid plan has. */
    public boolean hasMonthlyCap() {
        return maxSendsPerMonth > 0;
    }
}
