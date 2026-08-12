package com.kindlerss.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a Hacker News item page.
 *
 * <p>A Hacker News feed entry carries no text of its own: the feed summary is the
 * two links and a score, and the entry either points at a story on another site or,
 * for an Ask HN or text submission, back at the item page itself. This turns that
 * page into something readable — the submitted text, then the discussion — so an
 * entry whose linked page cannot be fetched still has something to read.
 */
@Component
public class HackerNewsReader {

    private static final Logger log = LoggerFactory.getLogger(HackerNewsReader.class);

    private static final Pattern ITEM_URL = Pattern.compile(
            "^https?://(?:www\\.)?news\\.ycombinator\\.com/item\\?id=(\\d+).*$", Pattern.CASE_INSENSITIVE);

    /** How many comments are worth carrying onto a small screen. */
    static final int MAX_COMMENTS = 150;

    /** Replies deeper than this are indented as if they sat at this depth. */
    private static final int MAX_DEPTH = 4;

    private final SafeHttpClient httpClient;

    public HackerNewsReader(SafeHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /** The canonical item URL when the given URL points at a Hacker News item. */
    public static Optional<String> itemUrl(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = ITEM_URL.matcher(url.trim());
        return matcher.matches()
                ? Optional.of("https://news.ycombinator.com/item?id=" + matcher.group(1))
                : Optional.empty();
    }

    /** Either an item page rendered as article HTML, or why there is nothing to read. */
    public record Item(String html, String failure) {
        static Item failed(String reason) {
            return new Item(null, reason);
        }

        public boolean found() {
            return html != null && !html.isBlank();
        }
    }

    /** Fetches an item page and renders it. */
    public Item read(String itemUrl) {
        try {
            SafeHttpClient.FetchedContent fetched = httpClient.getPage(itemUrl);
            if (!fetched.isMarkup()) {
                return Item.failed("the item page is not a web page but " + fetched.contentType());
            }
            String html = render(fetched.body(), fetched.finalUri().toString());
            if (html.isBlank()) {
                return Item.failed("the item has no text and no comments yet");
            }
            return new Item(html, null);
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.info("Could not read Hacker News item {}: {}", itemUrl, reason);
            return Item.failed(reason);
        }
    }

    /**
     * Renders the submitted text and the comment tree as plain article HTML.
     * Depth is expressed as nested block quotes, which the reader and the EPUB
     * both indent, because Hacker News carries it in an attribute no allow-list
     * would let through.
     */
    static String render(String html, String baseUri) {
        Document doc = Jsoup.parse(html, baseUri);
        StringBuilder out = new StringBuilder();

        Element submitted = doc.selectFirst("table.fatitem div.toptext");
        if (submitted != null && !submitted.text().isBlank()) {
            out.append(submitted.html());
        }

        var rows = doc.select("tr.comtr");
        int written = 0;
        for (Element row : rows) {
            if (written >= MAX_COMMENTS) {
                out.append("<p>Only the first ").append(MAX_COMMENTS)
                        .append(" comments are shown. The rest are on Hacker News.</p>");
                break;
            }
            String comment = renderComment(row);
            if (comment == null) {
                continue;
            }
            if (written == 0) {
                out.append("<h2>Hacker News discussion</h2>");
            }
            out.append(comment);
            written++;
        }
        return out.toString();
    }

    private static String renderComment(Element row) {
        Element text = row.selectFirst("div.commtext");
        if (text == null || text.text().isBlank()) {
            // Flagged, dead, or collapsed comments have no text to show.
            return null;
        }
        Element body = text.clone();
        // The reply link is part of the page furniture, not of what was written.
        body.select("div.reply").remove();

        Element author = row.selectFirst("a.hnuser");
        Element age = row.selectFirst("span.age");
        StringBuilder byline = new StringBuilder();
        if (author != null) {
            byline.append("<strong>").append(escape(author.text())).append("</strong>");
        }
        if (age != null && !age.text().isBlank()) {
            byline.append(byline.isEmpty() ? "" : " · ").append(escape(age.text()));
        }

        int depth = Math.min(indentOf(row), MAX_DEPTH);
        return "<blockquote>".repeat(depth)
                + (byline.isEmpty() ? "" : "<p>" + byline + "</p>")
                + body.html()
                + "</blockquote>".repeat(depth);
    }

    private static int indentOf(Element row) {
        Element indent = row.selectFirst("td.ind");
        if (indent == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(indent.attr("indent").trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
