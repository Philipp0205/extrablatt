package com.kindlerss.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Publishes the site's stylesheet to every view, so that each page can carry the
 * rules inside the document instead of pointing at a file the browser has to go
 * and fetch.
 *
 * <p>A browser will not paint a page while a stylesheet named in its {@code <head>}
 * is still on its way, so a page is styled no sooner than that request finishes.
 * On a phone or a Kindle that request costs a round trip, and it was paid on every
 * single navigation: static files went through the security filter chain, which
 * stamps them {@code no-store}, so the stylesheet was never allowed into the
 * browser's cache and was fetched again for every page. That is the window in
 * which a page can be seen with no styling at all.
 *
 * <p>Sending the rules with the page closes the window rather than narrowing it —
 * there is no request left to be slow, on any browser, on any connection, on the
 * first visit as much as the hundredth. What it costs is four kilobytes of
 * compressed markup per page, well under the ten the stylesheet cost when it was
 * refetched every time anyway, and the scripts now cache properly on top of that
 * (see {@link com.kindlerss.config.WebConfig}).
 */
@ControllerAdvice
public class StylesheetAdvice {

    /**
     * Deliberately not under {@code static/}: the rules reach the browser inside the
     * page and nowhere else, so there is no second, separately cached copy of them
     * that can fall out of step with the markup it styles.
     */
    private static final String PATH = "css/app.css";

    private final String css;

    public StylesheetAdvice() {
        this.css = read(PATH);
    }

    @ModelAttribute("stylesheet")
    public String stylesheet() {
        return css;
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            String text = withoutComments(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            // The rules go into the page unescaped, because HTML escaping would
            // corrupt them. Inside a <style> element the only sequence that can end
            // the element early — and so spill the rest of the stylesheet onto the
            // page as text — is this one. Fail at start-up rather than serve that.
            if (text.toLowerCase().contains("</style")) {
                throw new IllegalStateException(
                        path + " contains \"</style\", which cannot be written into a page");
            }
            return text;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }

    /**
     * Drops the comments, which are nearly half of the file.
     *
     * <p>The stylesheet explains itself at length, and those explanations are for
     * whoever edits it next, not for a reader's phone. Leaving them in the source and
     * out of the page is what makes carrying the rules with every page cheap: it is
     * the difference between ten kilobytes of compressed markup a page and four.
     *
     * <p>Quoted values are copied through untouched, so a {@code content} or
     * {@code url()} string that happens to contain the opening of a comment is not
     * mistaken for one.
     */
    static String withoutComments(String css) {
        StringBuilder out = new StringBuilder(css.length());
        char quote = 0;
        int i = 0;
        while (i < css.length()) {
            char c = css.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == '\\' && i + 1 < css.length()) {
                    out.append(css.charAt(i + 1));
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                out.append(c);
            } else if (c == '/' && i + 1 < css.length() && css.charAt(i + 1) == '*') {
                int end = css.indexOf("*/", i + 2);
                i = end < 0 ? css.length() : end + 1;
            } else {
                out.append(c);
            }
            i++;
        }
        // Every removed comment leaves the line it sat on behind, empty.
        return BLANK_LINES.matcher(out.toString()).replaceAll("\n").strip();
    }

    private static final Pattern BLANK_LINES = Pattern.compile("[ \\t]*\\n(?:[ \\t]*\\n)+[ \\t]*");
}
