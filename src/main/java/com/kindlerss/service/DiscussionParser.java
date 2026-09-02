package com.kindlerss.service;

import com.kindlerss.domain.Article;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import net.dankito.readability4j.Readability4J;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Builds reader-friendly pages for discussion sites whose useful content is split
 * between a post and a separate comment thread.
 */
@Component
public class DiscussionParser {

    private final SafeHttpClient httpClient;

    public DiscussionParser(SafeHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public boolean supports(Article article) {
        return isRedditPost(article.url()) || hackerNewsCommentsUrl(article).isPresent();
    }

    public boolean isParsedDiscussion(String html) {
        if (html == null || html.isBlank()) {
            return false;
        }
        return Jsoup.parseBodyFragment(html).select("h2").stream()
                .anyMatch(heading -> "Comments".equalsIgnoreCase(heading.text().trim()));
    }

    public Optional<String> parse(Article article) {
        if (isRedditPost(article.url())) {
            return parseReddit(article.url());
        }
        return hackerNewsCommentsUrl(article).flatMap(url -> parseHackerNews(article, url));
    }

    private Optional<String> parseReddit(String postUrl) {
        try {
            SafeHttpClient.FetchedContent fetched = httpClient.get(redditCommentsFeedUrl(postUrl));
            SyndFeed feed = readFeed(fetched.body());
            List<SyndEntry> entries = feed.getEntries();
            if (entries.isEmpty()) {
                return Optional.empty();
            }

            Document output = Document.createShell("");
            Element body = output.body();
            body.appendElement("h2").text("Post");
            appendFragment(body, redditBody(entries.getFirst()));
            body.appendElement("h2").text("Comments");
            if (entries.size() == 1) {
                body.appendElement("p").appendElement("em").text("No comments yet.");
            } else {
                for (SyndEntry comment : entries.subList(1, entries.size())) {
                    appendComment(body, redditAuthor(comment), redditBody(comment), null);
                }
            }
            return Optional.of(body.html());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<String> parseHackerNews(Article article, String commentsUrl) {
        String postHtml = null;
        if (!samePage(article.url(), commentsUrl)) {
            postHtml = extractReadable(article.url());
        }

        Document thread = null;
        try {
            SafeHttpClient.FetchedContent fetched = httpClient.get(commentsUrl);
            thread = Jsoup.parse(fetched.body(), fetched.finalUri().toString());
        } catch (Exception ignored) {
            // The linked article is still useful when the discussion page is temporarily unavailable.
        }

        if ((postHtml == null || postHtml.isBlank()) && thread != null) {
            Element topText = thread.selectFirst(".toptext");
            if (topText != null) {
                postHtml = topText.html();
            }
        }
        if ((postHtml == null || postHtml.isBlank()) && thread == null) {
            return Optional.empty();
        }

        Document output = Document.createShell("");
        Element body = output.body();
        body.appendElement("h2").text("Post");
        if (postHtml == null || postHtml.isBlank()) {
            body.appendElement("p").appendElement("em").text("The post has no text.");
        } else {
            appendFragment(body, postHtml);
        }
        body.appendElement("h2").text("Comments");

        List<Element> comments = thread == null ? List.of() : thread.select("tr.athing.comtr");
        if (comments.isEmpty()) {
            body.appendElement("p").appendElement("em").text("No comments yet.");
        } else {
            for (Element row : comments) {
                Element text = row.selectFirst(".commtext");
                if (text == null || text.text().isBlank()) {
                    continue;
                }
                Element author = row.selectFirst(".hnuser");
                Element age = row.selectFirst(".age");
                appendComment(body,
                        author == null ? "Anonymous" : author.text(),
                        text.html(),
                        age == null ? null : age.text());
            }
        }
        return Optional.of(body.html());
    }

    private String extractReadable(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            SafeHttpClient.FetchedContent fetched = httpClient.get(url);
            net.dankito.readability4j.Article parsed =
                    new Readability4J(fetched.finalUri().toString(), fetched.body()).parse();
            return parsed == null ? null : parsed.getContent();
        } catch (Exception ignored) {
            return null;
        }
    }

    static Optional<String> hackerNewsCommentsUrl(Article article) {
        String html = (article.feedContentHtml() == null ? "" : article.feedContentHtml())
                + (article.summaryHtml() == null ? "" : article.summaryHtml());
        for (Element link : Jsoup.parseBodyFragment(html).select("a[href]")) {
            String href = link.attr("href").trim();
            if (isHackerNewsItem(href)) {
                return Optional.of(href);
            }
        }
        if (isHackerNewsItem(article.url())) {
            return Optional.of(article.url().trim());
        }
        return Optional.empty();
    }

    static String redditCommentsFeedUrl(String postUrl) {
        URI uri = URI.create(postUrl.trim());
        String path = uri.getPath();
        path = path.endsWith("/") ? path + ".rss" : path + "/.rss";
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + path).toString();
    }

    private static boolean isRedditPost(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            return host != null
                    && (host.equalsIgnoreCase("reddit.com")
                    || host.toLowerCase(Locale.ROOT).endsWith(".reddit.com"))
                    && uri.getPath() != null
                    && uri.getPath().contains("/comments/");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean isHackerNewsItem(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            return "news.ycombinator.com".equalsIgnoreCase(uri.getHost())
                    && "/item".equals(uri.getPath())
                    && uri.getQuery() != null
                    && uri.getQuery().matches("(^|.*&)id=\\d+(&.*|$)");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean samePage(String first, String second) {
        return first != null && second != null && first.trim().equals(second.trim());
    }

    private static SyndFeed readFeed(String xml) throws Exception {
        SyndFeedInput input = new SyndFeedInput();
        input.setPreserveWireFeed(false);
        try (XmlReader reader = new XmlReader(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))) {
            return input.build(reader);
        }
    }

    private static String redditAuthor(SyndEntry entry) {
        String author = entry.getAuthor();
        return author == null || author.isBlank() ? "Anonymous" : author.trim();
    }

    private static String redditBody(SyndEntry entry) {
        String html = "";
        if (entry.getContents() != null && !entry.getContents().isEmpty()) {
            html = contentValue(entry.getContents().getFirst());
        }
        if (html.isBlank()) {
            html = contentValue(entry.getDescription());
        }

        Document fragment = Jsoup.parseBodyFragment(html);
        List<Element> markdown = fragment.select("div.md");
        if (!markdown.isEmpty()) {
            return markdown.stream().map(Element::outerHtml)
                    .reduce("", String::concat);
        }

        Document fallback = Document.createShell("");
        for (Element image : fragment.select("img")) {
            fallback.body().appendChild(image.clone());
        }
        Element linkedPost = fragment.select("a").stream()
                .filter(link -> "[link]".equalsIgnoreCase(link.text().trim()))
                .findFirst().orElse(null);
        if (linkedPost != null) {
            fallback.body().appendElement("p").appendElement("a")
                    .attr("href", linkedPost.attr("href")).text("Open linked post");
        }
        if (fallback.body().children().isEmpty()) {
            fallback.body().appendElement("p").appendElement("em").text("The post has no text.");
        }
        return fallback.body().html();
    }

    private static String contentValue(SyndContent content) {
        return content == null || content.getValue() == null ? "" : content.getValue();
    }

    private static void appendFragment(Element parent, String html) {
        Document fragment = Jsoup.parseBodyFragment(html == null ? "" : html);
        for (Element child : List.copyOf(fragment.body().children())) {
            parent.appendChild(child.clone());
        }
    }

    private static void appendComment(Element body, String author, String html, String age) {
        Element comment = body.appendElement("blockquote");
        Element byline = comment.appendElement("p");
        byline.appendElement("strong").text(author);
        if (age != null && !age.isBlank()) {
            byline.appendText(" · " + age);
        }
        appendFragment(comment, html);
    }
}
