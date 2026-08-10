package com.kindlerss.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlSanitizerTest {

    private final HtmlSanitizer sanitizer = new HtmlSanitizer();

    @Test
    void stripsScriptsAndEventHandlers() {
        String dirty = "<p onclick=\"alert(1)\">Hi</p><script>alert(1)</script><img src=x onerror=alert(1)>";
        String clean = sanitizer.sanitizeWithImages(dirty);
        assertFalse(clean.toLowerCase().contains("script"));
        assertFalse(clean.toLowerCase().contains("onclick"));
        assertFalse(clean.toLowerCase().contains("onerror"));
        assertTrue(clean.contains("Hi"));
    }

    @Test
    void canStripImages() {
        String html = "<p>Text</p><img src=\"https://example.com/a.png\" alt=\"a\"/>";
        String with = sanitizer.sanitizeWithImages(html);
        String without = sanitizer.sanitizeWithoutImages(html);
        assertTrue(with.contains("<img"));
        assertFalse(without.contains("<img"));
        assertTrue(without.contains("Text"));
    }
}
