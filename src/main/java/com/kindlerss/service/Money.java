package com.kindlerss.service;

import java.util.Locale;

/**
 * Formatting for the handful of prices this app quotes. Amounts are held as euro
 * cents so that a price is exact, and rendered here so that every page, e-mail
 * and legal notice shows the same string.
 */
public final class Money {

    private Money() {
    }

    /** {@code 250} becomes {@code "2.50"}. */
    public static String euros(int cents) {
        return String.format(Locale.ROOT, "%d.%02d", cents / 100, Math.abs(cents % 100));
    }

    /** {@code 250} becomes {@code "€2.50"}. */
    public static String priceTag(int cents) {
        return "\u20ac" + euros(cents);
    }
}
