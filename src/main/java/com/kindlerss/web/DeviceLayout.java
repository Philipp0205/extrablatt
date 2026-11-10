package com.kindlerss.web;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides from a User-Agent whether a page should get the e-ink, page-turning
 * layout.
 *
 * <p>Scrolling on an e-ink panel is slow: every scroll step is a full, laggy
 * redraw. Kindle e-readers therefore get pages that are turned one at a time.
 * Phones, tablets and desktops keep the scrolling layout.
 *
 * <p>The rule: a User-Agent is e-ink when it carries the case-sensitive word
 * {@code Kindle} and is not a Fire tablet. A Fire tablet is anything that says
 * {@code Silk} (Amazon's browser on Fire tablets and Fire TV, never on an e-ink
 * Kindle), carries a Fire model code ({@code KFTT}, {@code KFAPWI}, {@code KFMUWI}
 * and the like) or says {@code Kindle Fire}. Those devices have LCD screens and
 * scroll fine.
 *
 * <p>E-ink Kindles (the "experimental browser"):
 * <ul>
 *   <li>Kindle 2, NetFront:
 *       {@code Mozilla/4.0 (compatible; Linux 2.6.22) NetFront/3.4 Kindle/2.5 (screen 824x1200;rotate)}</li>
 *   <li>Kindle 3 (Keyboard) and Kindle Touch, old WebKit:
 *       {@code Mozilla/5.0 (Linux; U; en-US) AppleWebKit/528.5+ (KHTML, like Gecko, Safari/528.5+) Version/4.0 Kindle/3.0 (screen 600x800; rotate)}</li>
 *   <li>Paperwhite, Voyage, Oasis (firmware 5.x, WebKit):
 *       {@code Mozilla/5.0 (X11; U; Linux armv7l like Android; en-us) AppleWebKit/531.2+ (KHTML, like Gecko) Version/5.0 Safari/533.2+ Kindle/3.0+}</li>
 *   <li>Scribe and newer Paperwhite (firmware 5.16.4 and later, Chromium):
 *       {@code Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Version/5.0 Safari/537.36 Kindle/3.0+}.
 *       It says {@code Android}, so {@code Android} alone must not rule a device out.</li>
 * </ul>
 *
 * <p>Fire tablets (not e-ink):
 * <ul>
 *   <li>{@code Mozilla/5.0 (Linux; Android 9; KFMUWI) AppleWebKit/537.36 (KHTML, like Gecko) Silk/126.4.1 like Chrome/126.0.6478.71 Safari/537.36}</li>
 *   <li>{@code Mozilla/5.0 (Linux; U; en-us; KFAPWI Build/JDQ39) AppleWebKit/535.19 (KHTML, like Gecko) Silk/3.6 Safari/535.19 Silk-Accelerated=true}</li>
 *   <li>{@code Mozilla/5.0 (Linux; U; Android 2.3.4; en-us; Kindle Fire Build/GINGERBREAD) AppleWebKit/533.1 (KHTML, like Gecko) Version/4.0 Mobile Safari/533.1}</li>
 * </ul>
 *
 * <p>A reader can override the guess either way with {@code ?layout=}; see
 * {@link LayoutInterceptor}. A device this rule misses can still be caught in the
 * browser by {@code app.js}, which asks {@code matchMedia('(update: slow)')}.
 */
public final class DeviceLayout {

    private static final Pattern KINDLE = Pattern.compile("\\bKindle\\b");
    private static final Pattern FIRE_MODEL = Pattern.compile("\\bKF[A-Z]{2,6}\\b");

    private DeviceLayout() {
    }

    /** True when the User-Agent belongs to an e-ink Kindle. Null or blank is not one. */
    public static boolean isEink(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return false;
        }
        if (!KINDLE.matcher(userAgent).find()) {
            return false;
        }
        return !isFireTablet(userAgent);
    }

    static boolean isFireTablet(String userAgent) {
        return userAgent.contains("Silk")
                || userAgent.contains("Kindle Fire")
                || FIRE_MODEL.matcher(userAgent).find();
    }

    /** What a reader asked for, through {@code ?layout=} or the {@code layout} cookie. */
    public enum Preference {
        /** Page-turning layout, whatever the device. */
        EINK,
        /** Scrolling layout, whatever the device. */
        SCROLL,
        /** Decide from the device. */
        AUTO;

        /**
         * The value of {@code ?layout=}: {@code kindle} or {@code eink}, {@code scroll},
         * {@code auto}. Anything else is null, so it changes nothing.
         */
        public static Preference fromParameter(String value) {
            if (value == null) {
                return null;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "kindle", "eink", "e-ink" -> EINK;
                case "scroll" -> SCROLL;
                case "auto" -> AUTO;
                default -> null;
            };
        }

        /** The value of the cookie: {@code eink} or {@code scroll}. Anything else is automatic. */
        public static Preference fromCookie(String value) {
            if ("eink".equals(value)) {
                return EINK;
            }
            if ("scroll".equals(value)) {
                return SCROLL;
            }
            return AUTO;
        }

        /** The cookie value that keeps this preference, or null for automatic (no cookie). */
        public String cookieValue() {
            return switch (this) {
                case EINK -> "eink";
                case SCROLL -> "scroll";
                case AUTO -> null;
            };
        }

        public boolean einkFor(String userAgent) {
            return switch (this) {
                case EINK -> true;
                case SCROLL -> false;
                case AUTO -> isEink(userAgent);
            };
        }
    }
}
