package com.kindlerss.service;

import io.documentnode.epub4j.domain.Author;
import io.documentnode.epub4j.domain.Book;
import io.documentnode.epub4j.domain.Identifier;
import io.documentnode.epub4j.domain.MediaTypes;
import io.documentnode.epub4j.domain.Resource;
import io.documentnode.epub4j.epub.EpubWriter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Builds the single-article EPUB that is mailed to a Kindle.
 *
 * epub4j (a maintained fork of epublib) owns the container format: the package
 * document, the navigation document, and the ZIP layout down to the uncompressed
 * "mimetype" entry the specification wants written first. What is left here is
 * turning an article's sanitized HTML into one well-formed XHTML chapter.
 */
@Service
public class EpubService {

    private static final String ARTICLE_HREF = "article.xhtml";
    private static final String STYLESHEET_HREF = "style.css";

    /* Kindle ignores most of a document's styling, so this only covers what it does
       honour: a readable measure, images that cannot run past the screen, and a
       source line small enough to stay out of the way of the article. */
    private static final String STYLESHEET = """
            body { font-family: serif; line-height: 1.5; margin: 1em; }
            img { max-width: 100%; height: auto; }
            h1 { font-size: 1.4em; }
            .source { font-size: 0.8em; word-wrap: break-word; }
            """;

    public byte[] createEpub(String title, String author, String sourceUrl, String htmlBody) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            writeEpub(baos, title, author, sourceUrl, htmlBody);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create EPUB", e);
        }
        return baos.toByteArray();
    }

    public void writeEpub(OutputStream out, String title, String author, String sourceUrl, String htmlBody)
            throws IOException {
        String safeTitle = blankToDefault(title, "Article");
        String safeAuthor = blankToDefault(author, "Unknown");

        Book book = new Book();
        book.getMetadata().addTitle(safeTitle);
        book.getMetadata().addAuthor(toAuthor(safeAuthor));
        book.getMetadata().setLanguage("en");
        book.getMetadata().addIdentifier(
                new Identifier(Identifier.Scheme.UUID, UUID.randomUUID().toString()));

        book.addResource(resource(STYLESHEET_HREF, STYLESHEET, MediaTypes.CSS));
        // One section: the spine and the table of contents both end up with a single
        // entry pointing at it.
        book.addSection(safeTitle,
                resource(ARTICLE_HREF, wrapArticle(safeTitle, sourceUrl, htmlBody), MediaTypes.XHTML));

        new EpubWriter().write(book, out);
    }

    private static Resource resource(String href, String content, io.documentnode.epub4j.domain.MediaType mediaType) {
        Resource resource = new Resource(content.getBytes(StandardCharsets.UTF_8), mediaType);
        resource.setHref(href);
        return resource;
    }

    /**
     * Splits a byline so that epub4j, which writes a creator as "firstname lastname",
     * reproduces it unchanged. Feeds give whole names ("Jane Doe") and publication
     * names alike ("Reuters"), and neither has a surname to speak of.
     */
    private static Author toAuthor(String name) {
        int lastSpace = name.lastIndexOf(' ');
        return lastSpace < 0
                ? new Author(name)
                : new Author(name.substring(0, lastSpace), name.substring(lastSpace + 1));
    }

    private static String wrapArticle(String title, String sourceUrl, String bodyHtml) {
        String body = toXhtml(bodyHtml);
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en" lang="en">
                <head>
                  <meta charset="utf-8"/>
                  <title>%s</title>
                  <link rel="stylesheet" type="text/css" href="%s"/>
                </head>
                <body>
                  <h1>%s</h1>%s
                  %s
                </body>
                </html>
                """.formatted(escapeXml(title), STYLESHEET_HREF, escapeXml(title), sourceLine(sourceUrl), body);
    }

    /**
     * The address the article came from, shown under the heading. Readers who want
     * the page itself — for a paywalled remainder, a comment thread, or to share it
     * — otherwise have nothing to go on once the article is off the web.
     */
    private static String sourceLine(String sourceUrl) {
        String url = httpUrl(sourceUrl);
        if (url == null) {
            return "";
        }
        String escaped = escapeXml(url);
        return "\n  <p class=\"source\"><a href=\"%s\">%s</a></p>".formatted(escaped, escaped);
    }

    /** Only web addresses are worth printing, and only they are safe to link. */
    private static String httpUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") ? trimmed : null;
    }

    private static String toXhtml(String bodyHtml) {
        Document document = Jsoup.parseBodyFragment(bodyHtml == null ? "" : bodyHtml);
        // Browser readers can fold reply trees to keep the first page concise.
        // Kindle documents are read linearly, so remove only that interaction
        // wrapper while retaining every nested comment in document order.
        for (org.jsoup.nodes.Element replies : document.select("details[data-comment-replies]")) {
            org.jsoup.nodes.Element summary = replies.children().stream()
                    .filter(child -> child.tagName().equals("summary"))
                    .findFirst()
                    .orElse(null);
            if (summary != null) {
                summary.remove();
            }
            replies.unwrap();
        }
        document.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .charset(StandardCharsets.UTF_8)
                .prettyPrint(false);
        return document.body().html();
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
