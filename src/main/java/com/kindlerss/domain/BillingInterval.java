package com.kindlerss.domain;

/**
 * How often a subscription is charged. Yearly is the plan to steer people to: a
 * payment processor's fixed per-transaction fee does not shrink with the price,
 * so twelve small charges cost twelve fixed fees where one costs one.
 */
public enum BillingInterval {

    MONTHLY,
    YEARLY;

    /** Parses a form value, defaulting to yearly rather than rejecting the order. */
    public static BillingInterval parse(String value) {
        if (value == null) {
            return YEARLY;
        }
        return switch (value.trim().toLowerCase()) {
            case "monthly", "month" -> MONTHLY;
            default -> YEARLY;
        };
    }

    public boolean isMonthly() {
        return this == MONTHLY;
    }
}
