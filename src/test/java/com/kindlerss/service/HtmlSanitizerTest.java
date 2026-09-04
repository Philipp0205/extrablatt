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

    @Test
    void preservesOnlyTheDiscussionAttributesNeededForCollapsibleReplies() {
        String html = """
                <div class="offscreen" data-discussion-comments>
                  <blockquote data-discussion-comment>
                    Root
                    <details class="action-menu" data-comment-replies>
                      <summary>Show 1 reply</summary>
                      <div data-comment-reply-list><blockquote data-discussion-comment>Reply</blockquote></div>
                    </details>
                  </blockquote>
                </div>
                """;

        String clean = sanitizer.sanitizeWithoutImages(html);

        assertTrue(clean.contains("data-discussion-comments"));
        assertTrue(clean.contains("data-discussion-comment"));
        assertTrue(clean.contains("data-comment-replies"));
        assertTrue(clean.contains("data-comment-reply-list"));
        assertFalse(clean.contains("offscreen"));
        assertFalse(clean.contains("action-menu"));
    }
}
