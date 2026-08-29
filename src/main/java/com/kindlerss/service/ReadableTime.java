package com.kindlerss.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Dates as a sentence rather than as digits: "3 hours ago", "yesterday",
 * "14 March 2026".
 *
 * <p>A timestamp like {@code 2026-03-14 09:12} is four separate things to parse
 * visually and is read out by a screen reader as a string of numbers. What a
 * reader wants from an article list is how fresh the item is, which a phrase
 * answers immediately.
 */
@Component
public class ReadableTime {

    private static final DateTimeFormatter ABSOLUTE =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final ZoneId zone;

    public ReadableTime() {
        this(ZoneId.systemDefault());
    }

    ReadableTime(ZoneId zone) {
        this.zone = zone;
    }

    public String describe(Instant moment) {
        return describe(moment, Instant.now());
    }

    String describe(Instant moment, Instant now) {
        if (moment == null) {
            return "";
        }
        Duration age = Duration.between(moment, now);
        if (age.isNegative()) {
            return "just now";
        }
        long minutes = age.toMinutes();
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes == 1 ? "1 minute ago" : minutes + " minutes ago";
        }
        long hours = age.toHours();
        if (hours < 24) {
            return hours == 1 ? "1 hour ago" : hours + " hours ago";
        }
        long days = age.toDays();
        if (days == 1) {
            return "yesterday";
        }
        if (days < 7) {
            return days + " days ago";
        }
        return ABSOLUTE.format(moment.atZone(zone));
    }
}
