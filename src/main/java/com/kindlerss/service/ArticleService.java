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
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Loads, extracts, and updates article read/sent state. */
@Service
public class ArticleService {

    private static final Logger log = LoggerFactory.getLogger(ArticleService.class);

    private final ArticleRepository articleRepository;
    private final FeedRepository feedRepository;
    private final SafeHttpClient httpClient;
    private final HtmlSanitizer sanitizer;
    private final DiscussionParser discussionParser;
    private final TransactionOperations transactions;

    /** How many articles of a list are warmed at most per request. */
    private static final int WARM_MAX_IDS = 5;
    private static final int WARM_QUEUE_CAPACITY = 20;
    private static final int WARM_THREADS = 2;
    private static final Duration WARM_FAILURE_TTL = Duration.ofMinutes(15);
    private static final int WARM_FAILURE_PRUNE_AT = 1000;

    private final Executor warmExecutor;
    /** Set only when this service created the executor, so only then does it shut it down. */
    private final ExecutorService ownedWarmExecutor;
    private final Set<Long> warmInFlight = ConcurrentHashMap.newKeySet();
    /** Article id to the moment its failed extraction may be tried by the warm path again. */
    private final ConcurrentHashMap<Long, Instant> warmFailedUntil = new ConcurrentHashMap<>();

    @Autowired
    public ArticleService(ArticleRepository articleRepository,
                          FeedRepository feedRepository,
                          SafeHttpClient httpClient,
                          HtmlSanitizer sanitizer,
                          DiscussionParser discussionParser,
                          TransactionOperations transactions) {
        this(articleRepository, feedRepository, httpClient, sanitizer, discussionParser, transactions,
                newWarmExecutor(), true);
    }

    /** For tests: runs background warming on the given executor, which the test controls. */
    ArticleService(ArticleRepository articleRepository,
                   FeedRepository feedRepository,
                   SafeHttpClient httpClient,
                   HtmlSanitizer sanitizer,
                   DiscussionParser discussionParser,
                   TransactionOperations transactions,
                   Executor warmExecutor) {
        this(articleRepository, feedRepository, httpClient, sanitizer, discussionParser, transactions,
                warmExecutor, false);
    }

    private ArticleService(ArticleRepository articleRepository,
                           FeedRepository feedRepository,
                           SafeHttpClient httpClient,
                           HtmlSanitizer sanitizer,
                           DiscussionParser discussionParser,
                           TransactionOperations transactions,
                           Executor warmExecutor,
                           boolean owned) {
        this.articleRepository = articleRepository;
        this.feedRepository = feedRepository;
        this.httpClient = httpClient;
        this.sanitizer = sanitizer;
        this.discussionParser = discussionParser;
        this.transactions = transactions;
        this.warmExecutor = warmExecutor;
        this.ownedWarmExecutor = owned ? (ExecutorService) warmExecutor : null;
    }

    private static ExecutorService newWarmExecutor() {
        AtomicInteger counter = new AtomicInteger();
        // Two daemon threads and a short queue. When it is full, execute() rejects the
        // task and warmExtractedContent drops it (and its in-flight marker): warming is
        // only a head start, and opening the article extracts it anyway.
        return new ThreadPoolExecutor(WARM_THREADS, WARM_THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(WARM_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "article-warm-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdownWarmExecutor() {
        if (ownedWarmExecutor != null) {
            ownedWarmExecutor.shutdownNow();
        }
    }

    /**
     * Starts extracting the text of the first few of these articles in the background,
     * so that opening one from the list finds it already stored. Returns at once and
     * never throws. Ids already being worked on, or that failed within the last
     * fifteen minutes, are skipped; so are ids whose content is already stored.
     * Only this path consults the list of failures: opening an article always tries.
     */
    public void warmExtractedContent(long userId, Collection<Long> articleIds) {
        List<Long> claimed = new ArrayList<>();
        try {
            if (articleIds == null || articleIds.isEmpty()) {
                return;
            }
            Instant now = Instant.now();
            for (Long id : articleIds.stream().filter(Objects::nonNull).distinct().limit(WARM_MAX_IDS).toList()) {
                Instant failedUntil = warmFailedUntil.get(id);
                if (failedUntil != null && failedUntil.isAfter(now)) {
                    continue;
                }
                if (warmInFlight.add(id)) {
                    claimed.add(id);
                }
            }
            if (claimed.isEmpty()) {
                return;
            }
            Set<Long> missing = new HashSet<>(articleRepository.findIdsWithoutExtractedContent(claimed));
            for (Long id : List.copyOf(claimed)) {
                if (!missing.contains(id)) {
                    warmInFlight.remove(id);
                    claimed.remove(id);
                }
            }
            for (Long id : List.copyOf(claimed)) {
                claimed.remove(id);
                try {
                    warmExecutor.execute(() -> warmOne(userId, id));
                } catch (RuntimeException e) {
                    warmInFlight.remove(id);
                    log.debug("Could not queue warming of article {}: {}", id, e.getMessage());
                }
            }
        } catch (Exception e) {
            claimed.forEach(warmInFlight::remove);
            log.warn("Warming article content failed: {}", e.getMessage());
        }
    }

    private void warmOne(long userId, long id) {
        try {
            Optional<Article> article = articleRepository.findById(userId, id);
            if (article.isEmpty()) {
                return;
            }
            // Not in a transaction: this fetches from the publisher.
            resolveRawContent(article.get());
            boolean stored = articleRepository.findById(userId, id)
                    .map(Article::extractedContentHtml)
                    .filter(html -> !html.isBlank())
                    .isPresent();
            if (!stored) {
                rememberWarmFailure(id);
            }
        } catch (Exception e) {
            log.debug("Warming article {} failed: {}", id, e.getMessage());
        } finally {
            warmInFlight.remove(id);
        }
    }

    private void rememberWarmFailure(long id) {
        Instant now = Instant.now();
        if (warmFailedUntil.size() > WARM_FAILURE_PRUNE_AT) {
            warmFailedUntil.values().removeIf(until -> !until.isAfter(now));
            if (warmFailedUntil.size() > WARM_FAILURE_PRUNE_AT) {
                warmFailedUntil.clear();
            }
        }
        warmFailedUntil.put(id, now.plus(WARM_FAILURE_TTL));
    }

    public Optional<Article> findById(long userId, long id) {
        return articleRepository.findById(userId, id);
    }

    public List<Article> findPage(long userId, Long feedId, Boolean unreadOnly, int page, int pageSize) {
        return findPage(userId, feedId, null, unreadOnly, null, page, pageSize);
    }

    /**
     * One page of articles for list views. The returned articles carry no body text
     * ({@code summaryHtml}, {@code feedContentHtml} and {@code extractedContentHtml} are null);
     * callers that need content must use {@link #findById}.
     */
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

    /** Deliveries since a moment, for showing how much of an allowance is left. */
    public long countSentSince(long userId, Instant since) {
        return articleRepository.countSentSince(userId, since);
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
     * Marks every unread article in a feed as read. Throws when the feed is not
     * on this account, so the caller can say so instead of reporting zero.
     */
    @Transactional
    public int markFeedRead(long userId, long feedId) {
        if (feedRepository.findById(userId, feedId).isEmpty()) {
            throw new NotFoundException("Feed not found");
        }
        return articleRepository.markFeedRead(userId, feedId);
    }

    /**
     * Returns sanitized HTML for display/EPUB. Images are stripped by default.
     * Caches extracted content when Readability succeeds.
     *
     * <p>Deliberately not transactional: it may fetch the article's page, and the
     * only write is one UPDATE caching the result, which needs no transaction of
     * its own. Holding one across the fetch would pin a pooled connection to a
     * slow publisher.
     */
    public String getContentHtml(Article article, boolean includeImages) {
        String raw = resolveRawContent(article);
        return sanitizer.sanitize(raw, includeImages);
    }

    /**
     * The same content for the browser, where hidden images leave a marker behind:
     * on screen "Show images" is one tap away, so it is worth saying that there is
     * something to load. A Kindle has no such choice, so the EPUB stays clean.
     * Not transactional, for the same reason as {@link #getContentHtml}.
     */
    public String getReaderHtml(Article article, boolean includeImages) {
        String raw = resolveRawContent(article);
        return includeImages
                ? sanitizer.sanitizeWithImages(raw)
                : sanitizer.sanitizeWithImagePlaceholders(raw);
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
        if (discussionParser.supports(article)
                && !discussionParser.isParsedDiscussion(article.extractedContentHtml())) {
            Optional<String> discussion = discussionParser.parse(article);
            if (discussion.isPresent() && !discussion.get().isBlank()) {
                String sanitized = sanitizer.sanitizeWithImages(discussion.get());
                articleRepository.updateExtractedContent(article.id(), sanitized);
                return sanitized;
            }
        }
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
     * row rather than creating a duplicate. The page is fetched and extracted
     * before the transaction that stores it begins.
     */
    public Article importFromUrl(long userId, String rawUrl) {
        String url = normalizeHttpUrl(rawUrl);
        ExtractedPage page = extractPage(url);
        return transactions.execute(status -> storeImported(userId, page));
    }

    private Article storeImported(long userId, ExtractedPage page) {
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
            log.debug("Fetching {} failed: {}", url, e.getMessage());
            throw new IllegalArgumentException(e.userMessage(), e);
        }
        String finalUrl = fetched.finalUri().toString();
        if (looksLikeFeed(fetched)) {
            throw new IllegalArgumentException(
                    "That address is a whole site's news, not a single article. Follow the site from Feeds instead.");
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
