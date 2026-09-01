package com.kindlerss.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A cancellation declaration as the reader submitted it. § 312k Abs. 3 BGB
 * requires them to be able to keep it, and Abs. 4 requires it to be confirmed
 * with the date and time it arrived, so the declaration is stored rather than
 * merely acted upon.
 */
public record CancellationRequest(
        Long id,
        Long userId,
        String email,
        String name,
        String contractRef,
        Kind kind,
        LocalDate requestedEnd,
        String reason,
        Instant effectiveAt,
        Instant receivedAt
) {

    public enum Kind {

        /** Ordinary notice: ends when the paid period does. */
        ORDINARY,

        /** Extraordinary notice: the reader asks for it to end at once. */
        IMMEDIATE;

        public static Kind parse(String value) {
            return value != null && value.trim().equalsIgnoreCase("immediate") ? IMMEDIATE : ORDINARY;
        }
    }
}
