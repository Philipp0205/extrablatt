package com.kindlerss.service;

import com.kindlerss.domain.Article;
import com.kindlerss.repository.ArticleRepository;
import net.dankito.readability4j.Readability4J;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.Instant;

/** Loads, extracts, and updates article read/sent state. */
@Service
public class ArticleService {

    private static final Logger log = LoggerFactory.getLogger(ArticleService.class);

    private final ArticleRepository articleRepository;
    private final SafeHttpClient httpClient;
    private final HtmlSanitizer sanitizer;
    private final HackerNewsReader hackerNews;

    public ArticleService(ArticleRepository articleRepository,
                          SafeHttpClient httpClient,
                          HtmlSanitizer sanitizer,
                          HackerNewsReader hackerNews) {
        this.articleRepository = articleRepository;
        this.httpClient = httpClient;
        this.sanitizer = sanitizer;
        this.hackerNews = hackerNews;
    }

    public Optional<Article> findById(long userId, long id) {
        return articleRepository.findById(userId, id);
    }

    public List<Article> findPage(long userId, Long feedId, Boolean unreadOnly, int page, int pageSize) {
        return findPage(userId, feedId, null, unreadOnly, null, page, pageSize);
    }

    public List<Article> findPage(long userId, Long feedId, String category, Boolean unreadOnly,
                                  Instant unreadSnapshot, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        int offset = (safePage - 1) * safeSize;
        return articleRepository.findPage(userId, feedId, category, unreadOnly, unreadSnapshot, safeSize, offset);
    }

    public long count(long userId, Long feedId, Boolean unreadOnly) {
        return count(userId, feedId, null, unreadOnly, null);
    }

    public long count(long userId, Long feedId, String category, Boolean unreadOnly, Instant unreadSnapshot) {
        return articleRepository.count(userId, feedId, category, unreadOnly, unreadSnapshot);
    }

    @Transactional
    public Article markRead(long userId, long id, boolean read) {
        Article article = articleRepository.findById(userId, id)
                .orElseThrow(() -> new NotFoundException("Article not found"));
        articleRepository.markRead(userId, id, read);
        return articleRepository.findById(userId, id).orElse(article);
    }

    /** Returns how many of the given articles actually changed state. */
    @Transactional
    public int markRead(long userId, Collection<Long> ids, boolean read) {
        return articleRepository.markRead(userId, ids, read);
    }

    /**
     * Returns sanitized HTML for display/EPUB. Images are stripped by default.
     * Caches extracted content when Readability succeeds.
     */
    @Transactional
    public String getContentHtml(Article article, boolean includeImages) {
        String raw = resolveRawContent(article);
        return sanitizer.sanitize(raw, includeImages);
    }

    /**
     * Feed metadata sometimes carries a discussion link that does not exist on the
     * linked article itself (notably Hacker News). Keep that route available even
     * when Readability replaces the feed summary with the full source article.
     */
    public Optional<String> findCommentsUrl(Article article) {
        String html = (article.feedContentHtml() == null ? "" : article.feedContentHtml())
                + (article.summaryHtml() == null ? "" : article.summaryHtml());
        for (Element link : Jsoup.parseBodyFragment(html).select("a[href]")) {
            String href = link.attr("href").trim();
            String text = link.text().toLowerCase();
            if ((text.contains("comment") || href.matches("https?://news\\.ycombinator\\.com/item\\?id=\\d+.*"))
                    && (href.startsWith("https://") || href.startsWith("http://"))) {
                return Optional.of(href);
            }
        }
        return Optional.empty();
    }

    private String resolveRawContent(Article article) {
        if (article.extractedContentHtml() != null && !article.extractedContentHtml().isBlank()) {
            return article.extractedContentHtml();
        }

        // An entry that links to a Hacker News item is a text submission: the item
        // page is the article, and reading it as a web page yields the site
        // furniture instead of the text.
        Optional<String> submission = HackerNewsReader.itemUrl(article.url());
        if (submission.isPresent()) {
            return hackerNews.read(submission.get())
                    .map(html -> cache(article, html))
                    .orElseGet(() -> fromFeed(article, "the Hacker News item page could not be read"));
        }

        Extraction extraction = extractFromSource(article);
        if (extraction.succeeded()) {
            return cache(article, extraction.html());
        }
        log.info("Could not read article {} at {}: {}", article.id(), article.url(), extraction.failure());

        // The linked page is out of reach, so fall back to the discussion the feed
        // pointed at. That is not cached: the page may well be readable later, and
        // the discussion keeps growing in the meantime.
        Optional<String> discussion = findCommentsUrl(article)
                .flatMap(HackerNewsReader::itemUrl)
                .flatMap(hackerNews::read);
        if (discussion.isPresent()) {
            return note("The linked page could not be fetched (" + extraction.failure()
                    + "). The Hacker News discussion is shown instead.")
                    + sanitizer.sanitizeWithImages(discussion.get());
        }
        return fromFeed(article, extraction.failure());
    }

    private String cache(Article article, String html) {
        String sanitized = sanitizer.sanitizeWithImages(html);
        articleRepository.updateExtractedContent(article.id(), sanitized);
        return sanitized;
    }

    /**
     * What the feed itself carried, which for some feeds is a summary and for
     * others little more than a link. The reason the full text is missing is said
     * out loud, so a short entry is not mistaken for a short article.
     */
    private String fromFeed(Article article, String failure) {
        String prefix = failure == null ? "" : note("The full article could not be fetched (" + failure + ").");
        if (article.feedContentHtml() != null && !article.feedContentHtml().isBlank()) {
            return prefix + article.feedContentHtml();
        }
        if (article.summaryHtml() != null && !article.summaryHtml().isBlank()) {
            return prefix + article.summaryHtml();
        }
        return prefix.isEmpty() ? "<p>No content available.</p>" : prefix;
    }

    private static String note(String text) {
        return "<p><em>" + text + "</em></p>";
    }

    private Extraction extractFromSource(Article article) {
        if (article.url() == null || article.url().isBlank()) {
            return Extraction.failed("the entry carries no link");
        }
        try {
            SafeHttpClient.FetchedContent fetched = httpClient.getPage(article.url());
            if (!fetched.isMarkup()) {
                return Extraction.failed("the link is not a web page but " + fetched.contentType());
            }
            Readability4J readability = new Readability4J(fetched.finalUri().toString(), fetched.body());
            net.dankito.readability4j.Article parsed = readability.parse();
            String content = parsed == null ? null : parsed.getContent();
            if (content == null || content.isBlank()) {
                return Extraction.failed("no article text was found on the page");
            }
            return new Extraction(content, null);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return Extraction.failed(reason);
        }
    }

    /** Either the extracted article HTML, or why there is none. */
    private record Extraction(String html, String failure) {
        static Extraction failed(String reason) {
            return new Extraction(null, reason);
        }

        boolean succeeded() {
            return html != null && !html.isBlank();
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
