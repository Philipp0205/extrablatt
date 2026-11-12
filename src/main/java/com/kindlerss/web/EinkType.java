package com.kindlerss.web;

import java.util.Locale;

/**
 * The reading type a reader picked for the e-ink layout: a text size (small,
 * medium, large) and a face (sans or serif). Kept in the {@code eink-type} cookie as
 * {@code <size>-<face>}, for example {@code l-serif}. No cookie means medium sans,
 * which is what the layout looked like before there was a choice. The page
 * gets it as classes on {@code <html>} ({@code eink-size-s}, {@code eink-size-l},
 * {@code eink-face-serif}); eink.css styles them.
 *
 * <p>Set with {@code ?text=s|m|l} and {@code ?font=sans|serif} (see
 * {@link LayoutInterceptor}); each changes only its own half.
 */
public record EinkType(String size, String face) {

    public static final EinkType DEFAULT = new EinkType("m", "sans");

    /** The stored cookie value, or anything else, read leniently: unknown parts fall back to the default. */
    public static EinkType fromCookie(String value) {
        if (value == null) {
            return DEFAULT;
        }
        int dash = value.indexOf('-');
        String size = dash < 0 ? value : value.substring(0, dash);
        String face = dash < 0 ? "" : value.substring(dash + 1);
        return new EinkType(cleanSize(size), cleanFace(face));
    }

    /** This type with the size from {@code ?text=}; an unknown value changes nothing. */
    public EinkType withSize(String value) {
        return isSize(value) ? new EinkType(value.trim().toLowerCase(Locale.ROOT), face) : this;
    }

    /** This type with the face from {@code ?font=}; an unknown value changes nothing. */
    public EinkType withFace(String value) {
        return isFace(value) ? new EinkType(size, value.trim().toLowerCase(Locale.ROOT)) : this;
    }

    public boolean isDefault() {
        return DEFAULT.equals(this);
    }

    public String cookieValue() {
        return size + "-" + face;
    }

    /**
     * Extra classes for {@code <html class="eink ...">}, with a leading space, or empty
     * for the defaults, so the default page is exactly what it was.
     */
    public String htmlClasses() {
        StringBuilder classes = new StringBuilder();
        if (!"m".equals(size)) {
            classes.append(" eink-size-").append(size);
        }
        if (!"sans".equals(face)) {
            classes.append(" eink-face-").append(face);
        }
        return classes.toString();
    }

    private static boolean isSize(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return "s".equals(v) || "m".equals(v) || "l".equals(v);
    }

    private static boolean isFace(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return "sans".equals(v) || "serif".equals(v);
    }

    private static String cleanSize(String value) {
        return isSize(value) ? value.trim().toLowerCase(Locale.ROOT) : "m";
    }

    private static String cleanFace(String value) {
        return isFace(value) ? value.trim().toLowerCase(Locale.ROOT) : "sans";
    }
}
