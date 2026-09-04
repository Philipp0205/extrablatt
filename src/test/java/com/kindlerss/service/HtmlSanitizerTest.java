package com.kindlerss.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void aHiddenImageLeavesAMarkerBehind() {
        String html = "<p>Text</p><img src=\"https://example.com/a.png\" alt=\"A harbour at dawn\"/>";

        String marked = sanitizer.sanitizeWithImagePlaceholders(html);

        assertFalse(marked.contains("<img"));
        assertFalse(marked.contains("example.com/a.png"));
        assertTrue(marked.contains("<span class=\"image-placeholder\">[Image: A harbour at dawn]</span>"));
        assertTrue(marked.contains("Text"));
    }

    @Test
    void aMarkerWithoutACaptionJustSaysImage() {
        String marked = sanitizer.sanitizeWithImagePlaceholders("<img src=\"https://example.com/a.png\"/>");

        assertTrue(marked.contains(">[Image]</span>"));
    }

    @Test
    void aLongCaptionIsCutShort() {
        String caption = "word ".repeat(60).trim();

        String marked = sanitizer.sanitizeWithImagePlaceholders(
                "<img src=\"https://example.com/a.png\" alt=\"" + caption + "\"/>");

        assertTrue(marked.contains("…]</span>"));
        assertTrue(marked.length() < caption.length());
    }

    @Test
    void captionsAreEscapedRatherThanRendered() {
        String marked = sanitizer.sanitizeWithImagePlaceholders(
                "<img src=\"https://example.com/a.png\" alt=\"&lt;script&gt;alert(1)&lt;/script&gt;\"/>");

        assertFalse(marked.contains("<script"));
        assertTrue(marked.contains("&lt;script&gt;"));
    }

    /** An address the safelist drops would not load anyway, so promising one would be a lie. */
    @Test
    void anImageThatCouldNotBeLoadedLeavesNoMarker() {
        String marked = sanitizer.sanitizeWithImagePlaceholders(
                "<p>Text</p><img src=\"/local/a.png\" alt=\"Local\"/>");

        assertFalse(marked.contains("image-placeholder"));
        assertTrue(marked.contains("Text"));
    }

    @Test
    void aPictureLeavesOneMarkerRatherThanItsAlternatives() {
        String html = """
                <figure>
                  <picture>
                    <source srcset="https://example.com/a.webp" type="image/webp"/>
                    <img src="https://example.com/a.png" alt="Chart"/>
                  </picture>
                  <figcaption>Sales over time</figcaption>
                </figure>
                """;

        String marked = sanitizer.sanitizeWithImagePlaceholders(html);

        assertFalse(marked.contains("<source"));
        assertFalse(marked.contains("a.webp"));
        assertEquals(1, marked.split("image-placeholder", -1).length - 1);
        assertTrue(marked.contains("Sales over time"));
    }
}
