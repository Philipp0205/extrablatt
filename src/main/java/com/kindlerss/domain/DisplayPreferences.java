package com.kindlerss.domain;

import java.util.Locale;

/**
 * How one reader wants the accessible edition rendered: colours, type size, line
 * spacing and whether an article opens with its key points.
 *
 * <p>These are not cosmetic settings. For a reader with usable but failing sight
 * they decide whether the page can be read at all, so they travel in a cookie
 * (available before anyone is logged in, on the login form itself) and are
 * mirrored into the database so a second device inherits them.
 */
public record DisplayPreferences(
        Theme theme,
        int textSize,
        int lineSpacing,
        Font font,
        boolean wideLetterSpacing,
        boolean keyPointsFirst
) {

    public static final int MIN_TEXT_SIZE = 1;
    public static final int MAX_TEXT_SIZE = 5;
    public static final int MIN_LINE_SPACING = 1;
    public static final int MAX_LINE_SPACING = 3;

    /**
     * Black background, large type, roomy lines and key points first — the format
     * this edition was asked for, rather than the one a sighted developer would
     * have picked as a starting point.
     */
    public static final DisplayPreferences DEFAULTS =
            new DisplayPreferences(Theme.BLACK_BRIGHT, 3, 2, Font.SANS, false, true);

    public DisplayPreferences {
        if (theme == null) {
            theme = Theme.BLACK_BRIGHT;
        }
        if (font == null) {
            font = Font.SANS;
        }
        textSize = clamp(textSize, MIN_TEXT_SIZE, MAX_TEXT_SIZE);
        lineSpacing = clamp(lineSpacing, MIN_LINE_SPACING, MAX_LINE_SPACING);
    }

    /** Colour schemes, each one a full foreground/background pair rather than a tint. */
    public enum Theme {
        BLACK_BRIGHT("black-bright", "Black background, bright colours"),
        BLACK_YELLOW("black-yellow", "Black background, yellow text"),
        BLACK_WHITE("black-white", "Black background, white text only"),
        LIGHT_CONTRAST("light-contrast", "White background, black text");

        private final String value;
        private final String label;

        Theme(String value, String label) {
            this.value = value;
            this.label = label;
        }

        public String value() {
            return value;
        }

        public String label() {
            return label;
        }

        public static Theme parse(String raw, Theme fallback) {
            if (raw != null) {
                String value = raw.trim().toLowerCase(Locale.ROOT);
                for (Theme theme : values()) {
                    if (theme.value.equals(value)) {
                        return theme;
                    }
                }
            }
            return fallback;
        }
    }

    public enum Font {
        SANS("sans", "Plain letters (sans-serif)"),
        SERIF("serif", "Book letters (serif)");

        private final String value;
        private final String label;

        Font(String value, String label) {
            this.value = value;
            this.label = label;
        }

        public String value() {
            return value;
        }

        public String label() {
            return label;
        }

        public static Font parse(String raw, Font fallback) {
            if (raw != null) {
                String value = raw.trim().toLowerCase(Locale.ROOT);
                for (Font font : values()) {
                    if (font.value.equals(value)) {
                        return font;
                    }
                }
            }
            return fallback;
        }
    }

    /** The class list the page body carries; every rule in the stylesheet hangs off these. */
    public String bodyClasses() {
        return "theme-" + theme.value()
                + " size-" + textSize
                + " lines-" + lineSpacing
                + " font-" + font.value()
                + (wideLetterSpacing ? " letters-wide" : "");
    }

    public DisplayPreferences withTextSize(int size) {
        return new DisplayPreferences(theme, size, lineSpacing, font, wideLetterSpacing, keyPointsFirst);
    }

    public boolean canGrow() {
        return textSize < MAX_TEXT_SIZE;
    }

    public boolean canShrink() {
        return textSize > MIN_TEXT_SIZE;
    }

    /**
     * A compact, cookie-safe encoding: theme, size, spacing, font, wide letters,
     * key points. Unknown or damaged parts fall back to the default rather than
     * failing, because an unreadable page is worse than an unexpected one.
     */
    public String encode() {
        return String.join(".",
                theme.value(),
                String.valueOf(textSize),
                String.valueOf(lineSpacing),
                font.value(),
                wideLetterSpacing ? "1" : "0",
                keyPointsFirst ? "1" : "0");
    }

    public static DisplayPreferences decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULTS;
        }
        String[] parts = raw.trim().split("\\.");
        return new DisplayPreferences(
                Theme.parse(part(parts, 0), DEFAULTS.theme()),
                parseInt(part(parts, 1), DEFAULTS.textSize()),
                parseInt(part(parts, 2), DEFAULTS.lineSpacing()),
                Font.parse(part(parts, 3), DEFAULTS.font()),
                parseFlag(part(parts, 4), DEFAULTS.wideLetterSpacing()),
                parseFlag(part(parts, 5), DEFAULTS.keyPointsFirst()));
    }

    private static String part(String[] parts, int index) {
        return index < parts.length ? parts[index] : null;
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return raw == null ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean parseFlag(String raw, boolean fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "1", "true", "on", "yes" -> true;
            case "0", "false", "off", "no" -> false;
            default -> fallback;
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }
}
