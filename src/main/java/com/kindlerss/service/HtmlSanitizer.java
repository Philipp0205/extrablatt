package com.kindlerss.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/** Strips unsafe HTML from feed/article content before storage or EPUB export. */
@Component
public class HtmlSanitizer {

    /** Long enough for a real caption, short enough not to become a paragraph of its own. */
    private static final int MAX_CAPTION_LENGTH = 120;

    private static final Safelist ARTICLE = Safelist.relaxed()
            .addTags("figure", "figcaption", "picture", "source", "details", "summary")
            .addAttributes("img", "alt", "title", "width", "height")
            .addAttributes("a", "title")
            .addAttributes("blockquote", "data-discussion-comment")
            .addAttributes("details", "data-comment-replies")
            .addAttributes("div", "data-discussion-comments", "data-comment-reply-list")
            .addAttributes("source", "srcset", "type", "media")
            .addProtocols("img", "src", "http", "https")
            .addProtocols("a", "href", "http", "https", "mailto")
            .preserveRelativeLinks(false);

    private static final Safelist ARTICLE_NO_IMAGES = Safelist.relaxed()
            .removeTags("img")
            .addTags("figure", "figcaption", "details", "summary")
            .addAttributes("a", "title")
            .addAttributes("blockquote", "data-discussion-comment")
            .addAttributes("details", "data-comment-replies")
            .addAttributes("div", "data-discussion-comments", "data-comment-reply-list")
            .addProtocols("a", "href", "http", "https", "mailto")
            .preserveRelativeLinks(false);

    public String sanitize(String html, boolean allowImages) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document.OutputSettings settings = new Document.OutputSettings().prettyPrint(false);
        return Jsoup.clean(html, "", allowImages ? ARTICLE : ARTICLE_NO_IMAGES, settings);
    }

    public String sanitizeWithImages(String html) {
        return sanitize(html, true);
    }

    public String sanitizeWithoutImages(String html) {
        return sanitize(html, false);
    }

    /**
     * Drops the pictures but leaves a short marker where each one stood, so a
     * reader can tell an illustration belongs there and is worth loading rather
     * than reading around a hole in the text. Images that would not load anyway
     * (no address the safelist accepts) leave nothing behind.
     */
    public String sanitizeWithImagePlaceholders(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document.OutputSettings settings = new Document.OutputSettings().prettyPrint(false);
        Document doc = Jsoup.parseBodyFragment(sanitizeWithImages(html));
        doc.outputSettings(settings);
        doc.select("source").remove();
        for (Element image : doc.select("img")) {
            String src = image.attr("src").trim();
            if (src.isEmpty()) {
                image.remove();
                continue;
            }
            image.replaceWith(placeholderFor(image));
        }
        return doc.body().html();
    }

    private Element placeholderFor(Element image) {
        Element placeholder = new Element("span").addClass("image-placeholder");
        placeholder.text("[Image" + describe(image) + "]");
        return placeholder;
    }

    /** The picture's own words, when it has any, so the marker says which image is missing. */
    private String describe(Element image) {
        String caption = image.attr("alt").trim();
        if (caption.isEmpty()) {
            caption = image.attr("title").trim();
        }
        caption = caption.replaceAll("\\s+", " ");
        if (caption.isEmpty()) {
            return "";
        }
        if (caption.length() > MAX_CAPTION_LENGTH) {
            caption = caption.substring(0, MAX_CAPTION_LENGTH).trim() + "…";
        }
        return ": " + caption;
    }

    public String textOnly(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return Jsoup.parse(html).text();
    }
}
