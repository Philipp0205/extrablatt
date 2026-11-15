package com.kindlerss.web;

import java.util.Optional;

/**
 * The one Settings > Reading choice for marking articles read. It is stored as two
 * flags on the account: "keep track of unread articles" and "mark read on scroll".
 * Track off with scroll on is a legal stored state that behaves like NONE.
 */
public enum ReadTracking {
    OPEN("open", true, false),
    SCROLL("scroll", true, true),
    NONE("none", false, false);

    private final String parameter;
    private final boolean track;
    private final boolean scroll;

    ReadTracking(String parameter, boolean track, boolean scroll) {
        this.parameter = parameter;
        this.track = track;
        this.scroll = scroll;
    }

    public static ReadTracking fromFlags(boolean track, boolean scroll) {
        if (!track) {
            return NONE;
        }
        return scroll ? SCROLL : OPEN;
    }

    public static Optional<ReadTracking> fromParameter(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (ReadTracking mode : values()) {
            if (mode.parameter.equals(value)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    public String parameter() {
        return parameter;
    }

    public boolean track() {
        return track;
    }

    public boolean scroll() {
        return scroll;
    }
}
