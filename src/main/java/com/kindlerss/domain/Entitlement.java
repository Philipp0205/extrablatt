package com.kindlerss.domain;

import java.time.Instant;

/**
 * The allowances actually in force for one account, after the plan, any
 * administrator override and the billing feature flag have all had their say.
 * Callers ask for this instead of reading limits from configuration, so there is
 * one answer to "how many sends does this account get" rather than several.
 *
 * <p>{@code maxSendsPerDay} is an abuse guardrail that applies to everyone
 * including subscribers, so that one account cannot empty the e-mail budget in
 * an afternoon. {@code maxSendsPerMonth} is leftover from the old metered free
 * plan and is unused when it is zero — the unpaid state after a trial is "no
 * Kindle sends", not a monthly ration.
 */
public record Entitlement(
        Plan plan,
        int maxSendsPerDay,
        int maxSendsPerMonth,
        int maxFeeds,
        boolean newsletters,
        Instant trialEndsAt
) {

    public Entitlement(Plan plan, int maxSendsPerDay, int maxSendsPerMonth, int maxFeeds,
                       boolean newsletters) {
        this(plan, maxSendsPerDay, maxSendsPerMonth, maxFeeds, newsletters, null);
    }

    public boolean paid() {
        return plan == Plan.SUPPORTER;
    }

    /** A live complimentary week of the paid plan. */
    public boolean onTrial() {
        return trialEndsAt != null && paid();
    }

    /** Zero or less means no monthly ration — paid has none, an expired trial has none. */
    public boolean hasMonthlyCap() {
        return maxSendsPerMonth > 0;
    }
}
