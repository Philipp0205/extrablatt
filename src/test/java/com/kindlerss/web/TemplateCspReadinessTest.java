package com.kindlerss.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every template, rendered or not, stays within a strict Content-Security-Policy
 * ({@code style-src 'self'; script-src 'self'}): no inline styles, no style
 * elements, no inline event handlers and no script without a {@code src}.
 *
 * <p>{@link PageStylingTest} checks the pages it renders; this reads the files
 * themselves, so a fragment or a branch no test happens to render is held to the
 * same rule.
 */
class TemplateCspReadinessTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    private static final Pattern INLINE_STYLE = Pattern.compile("\\s(?:th:)?style\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_ELEMENT = Pattern.compile("<style[\\s>]", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVENT_HANDLER = Pattern.compile("\\s(?:th:)?on[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_TAG = Pattern.compile("<script\\b([^>]*)>", Pattern.CASE_INSENSITIVE);

    @Test
    void noTemplateCarriesInlineStylesHandlersOrScripts() throws IOException {
        List<Path> templates;
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            templates = files.filter(p -> p.toString().endsWith(".html")).toList();
        }
        assertFalse(templates.isEmpty(), "the templates were found");

        List<String> problems = new ArrayList<>();
        for (Path template : templates) {
            String markup = withoutComments(Files.readString(template));
            String name = TEMPLATES.relativize(template).toString();
            if (INLINE_STYLE.matcher(markup).find()) {
                problems.add(name + ": style attribute");
            }
            if (STYLE_ELEMENT.matcher(markup).find()) {
                problems.add(name + ": <style> element");
            }
            Matcher handler = EVENT_HANDLER.matcher(markup);
            if (handler.find()) {
                problems.add(name + ": inline handler " + handler.group().trim());
            }
            Matcher script = SCRIPT_TAG.matcher(markup);
            while (script.find()) {
                if (!script.group(1).matches("(?s).*\\b(?:th:)?src\\s*=.*")) {
                    problems.add(name + ": inline <script>");
                }
            }
        }
        assertTrue(problems.isEmpty(), "templates outside a strict CSP: " + problems);
    }

    private static String withoutComments(String markup) {
        return markup.replaceAll("(?s)<!--.*?-->", "");
    }
}
