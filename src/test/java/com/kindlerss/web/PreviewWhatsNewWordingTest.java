package com.kindlerss.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The preview's "What's new" sheet must say what the website's dialog says, so swapping the
 * page for /app changes nothing a reader sees. The words are read from the server template.
 */
class PreviewWhatsNewWordingTest {

    private static String text(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String firstGroup(String source, String regex, String what) {
        Matcher matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(source);
        assertTrue(matcher.find(), "the dialog template has " + what);
        return matcher.group(1).trim();
    }

    @Test
    void theSheetUsesTheDialogsHeadingAndButtonLabels() throws IOException {
        String dialog = text("templates/fragments/whats-new-dialog.html");
        String script = text("static/js/preview/whatsnew.js");

        String heading = firstGroup(dialog, "<h2>(.*?)</h2>", "a heading");
        String allChanges = firstGroup(dialog, "<a class=\"btn\"[^>]*>(.*?)</a>", "an All changes link");
        String gotIt = firstGroup(dialog, "<button class=\"btn primary\"[^>]*>(.*?)</button>", "a Got it button");

        for (String label : new String[] {heading, allChanges, gotIt}) {
            assertTrue(!label.isEmpty(), "the dialog label is not empty");
            String escaped = label.replace("'", "\\'");
            assertTrue(script.contains("'" + escaped + "'") || script.contains("\"" + label + "\""),
                    "whatsnew.js contains \"" + label + "\" as a quoted string");
        }
    }
}
