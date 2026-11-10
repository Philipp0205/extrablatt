package com.kindlerss.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceLayoutTest {

    static final String PAPERWHITE =
            "Mozilla/5.0 (X11; U; Linux armv7l like Android; en-us) AppleWebKit/531.2+ (KHTML, like Gecko) "
                    + "Version/5.0 Safari/533.2+ Kindle/3.0+";
    static final String SCRIBE_CHROMIUM =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Version/5.0 "
                    + "Safari/537.36 Kindle/3.0+";
    static final String OASIS =
            "Mozilla/5.0 (X11; U; Linux armv7l like Android; en-us) AppleWebKit/531.2+ (KHTML, like Gecko) "
                    + "Version/5.0 Safari/533.2+ Kindle/3.2+";
    static final String KINDLE_3 =
            "Mozilla/5.0 (Linux; U; en-US) AppleWebKit/528.5+ (KHTML, like Gecko, Safari/528.5+) Version/4.0 "
                    + "Kindle/3.0 (screen 600x800; rotate)";
    static final String KINDLE_2 =
            "Mozilla/4.0 (compatible; Linux 2.6.22) NetFront/3.4 Kindle/2.5 (screen 824x1200;rotate)";

    static final String FIRE_HD_SILK =
            "Mozilla/5.0 (Linux; Android 9; KFMUWI) AppleWebKit/537.36 (KHTML, like Gecko) Silk/126.4.1 "
                    + "like Chrome/126.0.6478.71 Safari/537.36";
    static final String FIRE_HDX_OLD =
            "Mozilla/5.0 (Linux; U; en-us; KFAPWI Build/JDQ39) AppleWebKit/535.19 (KHTML, like Gecko) Silk/3.6 "
                    + "Safari/535.19 Silk-Accelerated=true";
    static final String KINDLE_FIRE_FIRST =
            "Mozilla/5.0 (Linux; U; Android 2.3.4; en-us; Kindle Fire Build/GINGERBREAD) AppleWebKit/533.1 "
                    + "(KHTML, like Gecko) Version/4.0 Mobile Safari/533.1";
    /** Synthetic: a Fire tablet that also says "Kindle" still scrolls. */
    static final String FIRE_WITH_KINDLE_WORD =
            "Mozilla/5.0 (Linux; Android 11; KFTRWI) AppleWebKit/537.36 (KHTML, like Gecko) Silk/120.3.1 "
                    + "like Chrome/120.0 Safari/537.36 Kindle/3.0+";
    static final String PIXEL_CHROME =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/130.0.0.0 Mobile Safari/537.36";
    static final String IPHONE_SAFARI =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Version/18.1 Mobile/15E148 Safari/604.1";
    static final String DESKTOP_CHROME =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/130.0.0.0 Safari/537.36";
    static final String DESKTOP_FIREFOX =
            "Mozilla/5.0 (X11; Linux x86_64; rv:132.0) Gecko/20100101 Firefox/132.0";
    static final String KINDLE_APP_LOWERCASE =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36 kindle-reader";

    @ParameterizedTest
    @ValueSource(strings = {PAPERWHITE, SCRIBE_CHROMIUM, OASIS, KINDLE_3, KINDLE_2})
    void eInkKindlesGetThePageTurningLayout(String userAgent) {
        assertTrue(DeviceLayout.isEink(userAgent), userAgent);
    }

    @ParameterizedTest
    @ValueSource(strings = {FIRE_HD_SILK, FIRE_HDX_OLD, KINDLE_FIRE_FIRST, FIRE_WITH_KINDLE_WORD})
    void fireTabletsKeepScrolling(String userAgent) {
        assertFalse(DeviceLayout.isEink(userAgent), userAgent);
    }

    @ParameterizedTest
    @ValueSource(strings = {PIXEL_CHROME, IPHONE_SAFARI, DESKTOP_CHROME, DESKTOP_FIREFOX, KINDLE_APP_LOWERCASE})
    void phonesAndDesktopsKeepScrolling(String userAgent) {
        assertFalse(DeviceLayout.isEink(userAgent), userAgent);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void noUserAgentKeepsScrolling(String userAgent) {
        assertFalse(DeviceLayout.isEink(userAgent));
    }

    @Test
    void onlyWhitelistedParameterValuesAreUnderstood() {
        assertEquals(DeviceLayout.Preference.EINK, DeviceLayout.Preference.fromParameter("kindle"));
        assertEquals(DeviceLayout.Preference.EINK, DeviceLayout.Preference.fromParameter("eink"));
        assertEquals(DeviceLayout.Preference.EINK, DeviceLayout.Preference.fromParameter(" EInk "));
        assertEquals(DeviceLayout.Preference.SCROLL, DeviceLayout.Preference.fromParameter("scroll"));
        assertEquals(DeviceLayout.Preference.AUTO, DeviceLayout.Preference.fromParameter("auto"));
        assertNull(DeviceLayout.Preference.fromParameter("<script>"));
        assertNull(DeviceLayout.Preference.fromParameter(""));
        assertNull(DeviceLayout.Preference.fromParameter(null));
    }

    @Test
    void anUnknownCookieValueMeansAutomatic() {
        assertEquals(DeviceLayout.Preference.EINK, DeviceLayout.Preference.fromCookie("eink"));
        assertEquals(DeviceLayout.Preference.SCROLL, DeviceLayout.Preference.fromCookie("scroll"));
        assertEquals(DeviceLayout.Preference.DETECTED, DeviceLayout.Preference.fromCookie("eink-auto"));
        assertEquals(DeviceLayout.Preference.AUTO, DeviceLayout.Preference.fromCookie("kindle"));
        assertEquals(DeviceLayout.Preference.AUTO, DeviceLayout.Preference.fromCookie(null));
    }

    @Test
    void aPreferenceWinsOverTheDevice() {
        assertTrue(DeviceLayout.Preference.EINK.einkFor(DESKTOP_CHROME));
        assertFalse(DeviceLayout.Preference.SCROLL.einkFor(PAPERWHITE));
        assertTrue(DeviceLayout.Preference.AUTO.einkFor(PAPERWHITE));
        assertFalse(DeviceLayout.Preference.AUTO.einkFor(IPHONE_SAFARI));
        assertTrue(DeviceLayout.Preference.DETECTED.einkFor(DESKTOP_CHROME));
        assertEquals("auto", DeviceLayout.Preference.DETECTED.label());
    }
}
