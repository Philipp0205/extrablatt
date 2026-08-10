package com.kindlerss.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

@Component
public class HtmlSanitizer {

    private static final Safelist ARTICLE = Safelist.relaxed()
            .addTags("figure", "figcaption", "picture", "source")
            .addAttributes("img", "alt", "title", "width", "height")
            .addAttributes("a", "title")
            .addAttributes("source", "srcset", "type", "media")
            .addProtocols("img", "src", "http", "https")
            .addProtocols("a", "href", "http", "https", "mailto")
            .preserveRelativeLinks(false);

    private static final Safelist ARTICLE_NO_IMAGES = Safelist.relaxed()
            .removeTags("img")
            .addTags("figure", "figcaption")
            .addAttributes("a", "title")
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

    public String textOnly(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return Jsoup.parse(html).text();
    }
}
