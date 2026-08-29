package com.kindlerss.web;

/**
 * Which face of the application a request is being served by. Both run in the
 * same process against the same accounts and the same data; they differ only in
 * the view layer.
 */
public enum Edition {

    /** The Kindle-first reader on the main host: paged columns, Send-to-Kindle. */
    STANDARD("standard"),

    /**
     * The accessibility-first reader (accessibility.extrablatt.app): topics
     * instead of URLs, high-contrast large type, key points before full text,
     * read-aloud, and no paged columns — a screen reader has to be able to walk
     * the document in order.
     */
    ACCESSIBLE("accessible");

    private final String value;

    Edition(String value) {
        this.value = value;
    }

    /** The value used in the {@code display} parameter and the edition cookie. */
    public String value() {
        return value;
    }

    public boolean isAccessible() {
        return this == ACCESSIBLE;
    }

    /** Parses a parameter or cookie value; anything unrecognised means "no opinion". */
    public static Edition parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase();
        for (Edition edition : values()) {
            if (edition.value.equals(value)) {
                return edition;
            }
        }
        return null;
    }
}
