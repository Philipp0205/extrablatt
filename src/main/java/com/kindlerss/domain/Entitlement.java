package com.kindlerss.domain;

/**
 * The allowances actually in force for one account, after the plan, any
 * administrator override and the billing feature flag have all had their say.
 * Callers ask for this instead of reading limits from configuration, so there is
 * one answer to "how many sends does this account get" rather than several.
 */
public record Entitlement(
        Plan plan,
        int maxSendsPerDay,
        int maxFeeds,
        boolean newsletters
) {

    public boolean paid() {
        return plan == Plan.SUPPORTER;
    }
}
