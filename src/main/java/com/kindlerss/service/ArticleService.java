package com.kindlerss.service;

import com.kindlerss.domain.Article;
import com.kindlerss.domain.Feed;
import com.kindlerss.repository.ArticleRepository;
import com.kindlerss.repository.FeedRepository;
import net.dankito.readability4j.Readability4J;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Loads, extracts, and updates article read/sent state. */
@Service
public class ArticleService {

    private static final Logger log = LoggerFactory.getLogger(ArticleService.class);

    private final ArticleRepository articleRepository;
    private final FeedRepository feedRepository;
    private final SafeHttpClient httpClient;
    private final HtmlSanitizer sanitizer;

    public ArticleService(ArticleRepository articleRepository,
                          FeedRepository feedRepository,
                          SafeHttpClient httpClient,
                          HtmlSanitizer sanitizer) {
        this.articleRepository = articleRepository;
        this.feedRepository = feedRepository;
        this.httpClient = httpClient;
        this.sanitizer = sanitizer;
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

    /** How many articles an account has sent to Kindle in total, lifetime. */
    public long countSentTotal(long userId) {
        return articleRepository.countSentTotal(userId);
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

    /** Bookmarks an article, or takes the bookmark off again. */
    @Transactional
    public Article setSaved(long userId, long id, boolean saved) {
        if (!articleRepository.setSaved(userId, id, saved)) {
            throw new NotFoundException("Article not found");
        }
        return articleRepository.findById(userId, id)
                .orElseThrow(() -> new NotFoundException("Article not found"));
    }

    public List<Article> findSavedPage(long userId, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        return articleRepository.findSavedPage(userId, safeSize, (safePage - 1) * safeSize);
    }

    public long countSaved(long userId) {
        return articleRepository.countSaved(userId);
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

        String extracted = extractFromSource(article);
        if (extracted != null && !extracted.isBlank()) {
            String sanitized = sanitizer.sanitizeWithImages(extracted);
            articleRepository.updateExtractedContent(article.id(), sanitized);
            return sanitized;
        }

        if (article.feedContentHtml() != null && !article.feedContentHtml().isBlank()) {
            return article.feedContentHtml();
        }
        if (article.summaryHtml() != null && !article.summaryHtml().isBlank()) {
            return article.summaryHtml();
        }
        return "<p>No content available.</p>";
    }

    /**
     * Fetches a web page, extracts the readable article, and stores it on the
     * account's pasted-URL feed. Pasting the same address again refreshes that
     * row rather than creating a duplicate.
     */
    @Transactional
    public Article importFromUrl(long userId, String rawUrl) {
        String url = normalizeHttpUrl(rawUrl);
        ExtractedPage page = extractPage(url);
        Feed feed = feedRepository.findOrCreateClippingFeed(userId);
        String guid = page.url();
        Optional<Article> existing = articleRepository.findByFeedIdAndGuid(userId, feed.id(), guid);
        if (existing.isPresent()) {
            articleRepository.updateImportedContent(existing.get().id(), page.title(), page.author(),
                    page.contentHtml());
            return articleRepository.findById(userId, existing.get().id()).orElseThrow();
        }
        long id = articleRepository.insert(feed.id(), guid, page.title(), page.url(), page.author(),
                Instant.now(), null, null);
        if (id < 0) {
            Article raced = articleRepository.findByFeedIdAndGuid(userId, feed.id(), guid)
                    .orElseThrow(() -> new IllegalStateException("Could not store the article"));
            articleRepository.updateImportedContent(raced.id(), page.title(), page.author(),
                    page.contentHtml());
            return articleRepository.findById(userId, raced.id()).orElse(raced);
        }
        articleRepository.updateExtractedContent(id, page.contentHtml());
        return articleRepository.findById(userId, id)
                .orElseThrow(() -> new IllegalStateException("Could not store the article"));
    }

    static String normalizeHttpUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Paste an article URL first");
        }
        value = value.replaceAll("^[<(\\[\"']+", "").replaceAll("[>)\\]\"']+$", "").trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            value = "https://" + value;
        }
        return value;
    }

    private ExtractedPage extractPage(String url) {
        SafeHttpClient.FetchedContent fetched;
        try {
            fetched = httpClient.get(url);
        } catch (SafeHttpClient.FetchException e) {
            throw new IllegalArgumentException(e.getMessage() == null
                    ? "That page could not be fetched" : e.getMessage(), e);
        }
        String finalUrl = fetched.finalUri().toString();
        if (looksLikeFeed(fetched)) {
            throw new IllegalArgumentException(
                    "That looks like an RSS or Atom feed. Add it under Add feed instead.");
        }
        try {
            Readability4J readability = new Readability4J(finalUrl, fetched.body());
            net.dankito.readability4j.Article parsed = readability.parse();
            String content = parsed == null ? null : parsed.getContent();
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("Could not extract an article from that page");
            }
            String sanitized = sanitizer.sanitizeWithImages(content);
            if (sanitized.isBlank()) {
                throw new IllegalArgumentException("Could not extract an article from that page");
            }
            String title = firstNonBlank(
                    parsed.getTitle(),
                    pageTitle(fetched.body()),
                    fallbackTitle(finalUrl));
            String author = parsed.getByline();
            if (author != null) {
                author = author.isBlank() ? null : author.trim();
            }
            return new ExtractedPage(finalUrl, title, author, sanitized);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Extraction failed for {}: {}", finalUrl, e.getMessage());
            throw new IllegalArgumentException("Could not extract an article from that page");
        }
    }

    private static boolean looksLikeFeed(SafeHttpClient.FetchedContent fetched) {
        String type = fetched.contentType() == null ? "" : fetched.contentType().toLowerCase(Locale.ROOT);
        if (type.contains("html")) {
            return false;
        }
        if (type.contains("rss") || type.contains("atom") || type.contains("xml")) {
            return true;
        }
        String body = fetched.body() == null ? "" : fetched.body().stripLeading();
        String head = body.substring(0, Math.min(body.length(), 400)).toLowerCase(Locale.ROOT);
        return head.contains("<rss")
                || (head.contains("<feed") && head.contains("xmlns"))
                || head.contains("<rdf:rdf");
    }

    private static String pageTitle(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        Document document = Jsoup.parse(html);
        String title = document.title();
        return title == null || title.isBlank() ? null : title.trim();
    }

    private static String fallbackTitle(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            if (host != null && !host.isBlank()) {
                return host;
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to a generic title.
        }
        return "Untitled article";
    }

    private static String firstNonBlank(String first, String second, String third) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        if (third != null && !third.isBlank()) {
            return third.trim();
        }
        return "Untitled article";
    }

    private record ExtractedPage(String url, String title, String author, String contentHtml) {}

    private String extractFromSource(Article article) {
        if (article.url() == null || article.url().isBlank()) {
            return null;
        }
        try {
            SafeHttpClient.FetchedContent fetched = httpClient.get(article.url());
            Readability4J readability = new Readability4J(fetched.finalUri().toString(), fetched.body());
            net.dankito.readability4j.Article parsed = readability.parse();
            if (parsed == null) {
                return null;
            }
            String content = parsed.getContent();
            if (content == null || content.isBlank()) {
                return null;
            }
            return content;
        } catch (Exception e) {
            log.debug("Extraction failed for article {}: {}", article.id(), e.getMessage());
            return null;
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
