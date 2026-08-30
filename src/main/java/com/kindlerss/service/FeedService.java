package com.kindlerss.service;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.Feed;
import com.kindlerss.domain.FeedSource;
import com.kindlerss.repository.ArticleRepository;
import com.kindlerss.repository.FeedRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Subscribes to RSS/Atom feeds, discovers feed URLs from HTML pages, and
 * periodically refreshes entries into the article store.
 */
@Service
public class FeedService {

    private static final Logger log = LoggerFactory.getLogger(FeedService.class);

    /**
     * Query parameters through which a feed service lets a client say how many
     * entries it wants. A URL that already carries one is left alone.
     */
    private static final Set<String> ENTRY_COUNT_PARAMETERS = Set.of("count", "limit", "n");
    private static final List<DefaultFeed> DEFAULT_FEEDS = List.of(
            new DefaultFeed("hacker-news", "Hacker News", "https://hnrss.org/frontpage", "Technology"),
            new DefaultFeed("android-developers", "Android Developers",
                    "https://android-developers.googleblog.com/feeds/posts/default", "Technology"),
            new DefaultFeed("ars-technica", "Ars Technica",
                    "https://feeds.arstechnica.com/arstechnica/index", "Technology"),
            new DefaultFeed("bbc-world", "BBC World News",
                    "https://feeds.bbci.co.uk/news/world/rss.xml", "News")
    );

    private static final Pattern EMAIL_ADDRESS = Pattern.compile("([a-zA-Z0-9._%+-]+)@([a-zA-Z0-9.-]+)");

    private final FeedRepository feedRepository;
    private final ArticleRepository articleRepository;
    private final SafeHttpClient httpClient;
    private final HtmlSanitizer sanitizer;
    private final int maxEntries;
    private final int maxFeedsPerUser;

    public FeedService(FeedRepository feedRepository,
                       ArticleRepository articleRepository,
                       SafeHttpClient httpClient,
                       HtmlSanitizer sanitizer,
                       AppProperties properties) {
        this.feedRepository = feedRepository;
        this.articleRepository = articleRepository;
        this.httpClient = httpClient;
        this.sanitizer = sanitizer;
        this.maxEntries = properties.feeds().maxEntries();
        this.maxFeedsPerUser = properties.limits().maxFeedsPerUser();
    }

    public List<Feed> listFeeds(long userId) {
        return feedRepository.findAllWithUnreadCounts(userId);
    }

    public Optional<Feed> findById(long userId, long id) {
        return feedRepository.findById(userId, id);
    }

    public List<DefaultFeed> defaultFeeds(long userId) {
        Set<String> existingUrls = feedRepository.findAll(userId).stream()
                .map(Feed::url)
                .collect(java.util.stream.Collectors.toSet());
        return DEFAULT_FEEDS.stream().filter(feed -> !existingUrls.contains(feed.url())).toList();
    }

    public Optional<DefaultFeed> defaultFeed(String key) {
        return DEFAULT_FEEDS.stream().filter(feed -> feed.key().equals(key)).findFirst();
    }

    @Transactional
    public Feed addFeed(long userId, String rawUrl, String category) {
        String trimmed = rawUrl == null ? "" : rawUrl.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Feed URL is required");
        }
        if (feedRepository.countByUser(userId) >= maxFeedsPerUser) {
            throw new IllegalArgumentException(
                    "Feed limit reached (" + maxFeedsPerUser + "). Delete a feed before adding another.");
        }
        SafeHttpClient.FetchedContent fetched = httpClient.get(trimmed);
        String feedUrl = fetched.finalUri().toString();
        String body = fetched.body();

        ParsedFeed parsed;
        try {
            parsed = parseFeed(body, feedUrl);
        } catch (Exception directParseError) {
            // The address was a web page, not a feed: try to find the feed it points
            // to (its <link> tags and feed-like anchors) and, failing that, the
            // conventional feed locations for the site.
            Discovered discovered = discoverFeed(body, feedUrl);
            feedUrl = discovered.url();
            parsed = discovered.feed();
        }

        if (feedRepository.findByUrl(userId, feedUrl).isPresent()) {
            throw new IllegalArgumentException("Feed already exists");
        }

        String title = parsed.title() == null || parsed.title().isBlank() ? feedUrl : parsed.title().trim();
        Feed feed = feedRepository.insert(userId, title, feedUrl, parsed.siteUrl(), category);
        storeEntries(feed, parsed);
        return feedRepository.findById(userId, feed.id()).orElse(feed);
    }

    @Transactional
    public boolean deleteFeed(long userId, long id) {
        return feedRepository.deleteById(userId, id);
    }

    @Transactional
    public boolean categorizeFeed(long userId, long id, String category) {
        return feedRepository.updateCategory(userId, id, category);
    }

    /** Returned by {@link #receiveNewsletterIssue} when the account's feed limit blocked a new sender. */
    public static final long NEWSLETTER_FEED_LIMIT_REACHED = -2;
    /** Returned by {@link #receiveNewsletterIssue} for a message already stored (repeat delivery). */
    public static final long NEWSLETTER_DUPLICATE = -1;

    /**
     * Stores one incoming newsletter issue as an article, auto-creating (once,
     * per distinct sender) a feed for it the same way {@link #addFeed} creates one
     * for an RSS URL — an account only ever gives out one inbox address, and each
     * sender that mails it becomes its own entry in the feed list. Deduped by
     * {@code guid} (ideally the message's {@code Message-ID}) like a polled feed's
     * entries.
     */
    @Transactional
    public long receiveNewsletterIssue(long userId, String senderAddress, String senderName, String guid,
                                       String subject, Instant publishedAt, String contentHtml) {
        String sender = normalizedSenderAddress(senderAddress);
        if (sender == null) {
            return NEWSLETTER_DUPLICATE; // no usable sender identity; nothing sensible to store
        }
        String senderUrl = "newsletter:" + sender;
        Feed feed = feedRepository.findByUrl(userId, senderUrl).orElse(null);
        if (feed == null) {
            if (feedRepository.countByUser(userId) >= maxFeedsPerUser) {
                log.info("Dropping newsletter issue from {} for user {}: feed limit reached", sender, userId);
                return NEWSLETTER_FEED_LIMIT_REACHED;
            }
            String title = senderName == null || senderName.isBlank() ? sender : senderName.trim();
            feed = feedRepository.findOrCreateNewsletterFeed(userId, senderUrl, title, "Newsletters");
        }
        if (articleRepository.existsByFeedIdAndGuid(feed.id(), guid)) {
            return NEWSLETTER_DUPLICATE;
        }
        String title = subject == null || subject.isBlank() ? "(untitled)" : subject.trim();
        long id = articleRepository.insert(feed.id(), guid, title, null, senderName, publishedAt,
                null, sanitizer.sanitizeWithImages(contentHtml));
        feedRepository.clearError(feed.id());
        return id;
    }

    /** Just the {@code local@domain} part, lower-cased, from a possibly-decorated address. */
    private static String normalizedSenderAddress(String rawAddress) {
        if (rawAddress == null) {
            return null;
        }
        Matcher matcher = EMAIL_ADDRESS.matcher(rawAddress);
        return matcher.find() ? matcher.group().toLowerCase(Locale.ROOT) : null;
    }

    /**
     * Renames a category across all of an account's feeds. "Uncategorized" is a
     * placeholder for feeds with no category rather than a real one, so it cannot
     * be renamed; giving feeds a category through the usual form is how they leave it.
     */
    @Transactional
    public int renameCategory(long userId, String oldCategory, String newCategory) {
        String from = oldCategory == null ? "" : oldCategory.trim();
        String to = newCategory == null ? "" : newCategory.trim();
        if (from.isEmpty() || Feed.UNCATEGORIZED.equalsIgnoreCase(from)) {
            throw new IllegalArgumentException("Choose a category to rename");
        }
        if (to.isEmpty()) {
            throw new IllegalArgumentException("New category name is required");
        }
        if (to.equalsIgnoreCase(from)) {
            return 0;
        }
        return feedRepository.renameCategory(userId, from, to);
    }

    @Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT2M")
    public void scheduledRefresh() {
        log.info("Scheduled feed refresh starting");
        refreshAll();
    }

    /** Refreshes every account's feeds; used by the scheduler. */
    public void refreshAll() {
        refreshFeeds(feedRepository.findAllAcrossUsers());
    }

    /** Refreshes only the given account's feeds; used by manual refresh. */
    public void refreshForUser(long userId) {
        refreshFeeds(feedRepository.findAll(userId));
    }

    private void refreshFeeds(List<Feed> feeds) {
        for (Feed feed : feeds) {
            // Newsletters arrive by e-mail; pasted URLs are fetched once on send.
            if (feed.source() != FeedSource.RSS) {
                continue;
            }
            try {
                refreshFeed(feed);
            } catch (Exception e) {
                log.warn("Failed to refresh feed {}: {}", feed.id(), e.getMessage());
                feedRepository.setError(feed.id(), e.getMessage());
            }
        }
    }

    @Transactional
    public void refreshFeed(Feed feed) {
        try {
            SafeHttpClient.FetchedContent fetched = fetchEntries(feed.url());
            ParsedFeed parsed = parseFeed(fetched.body(), feed.url());
            String title = parsed.title() == null || parsed.title().isBlank() ? feed.title() : parsed.title().trim();
            feedRepository.updateTitleAndSite(feed.id(), title, parsed.siteUrl());
            int inserted = storeEntries(feed, parsed);
            feedRepository.clearError(feed.id());
            log.info("Refreshed feed {} ({} new articles)", feed.id(), inserted);
        } catch (Exception e) {
            feedRepository.setError(feed.id(), e.getMessage());
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    private int storeEntries(Feed feed, ParsedFeed parsed) {
        int inserted = 0;
        for (ParsedEntry entry : parsed.entries()) {
            if (entry.guid() == null || entry.guid().isBlank()) {
                continue;
            }
            if (articleRepository.existsByFeedIdAndGuid(feed.id(), entry.guid())) {
                continue;
            }
            long id = articleRepository.insert(
                    feed.id(),
                    entry.guid(),
                    entry.title(),
                    entry.url(),
                    entry.author(),
                    entry.publishedAt(),
                    sanitizer.sanitizeWithImages(entry.summaryHtml()),
                    sanitizer.sanitizeWithImages(entry.contentHtml())
            );
            if (id > 0) {
                inserted++;
            }
        }
        return inserted;
    }

    /**
     * A feed publishes only its newest entries, and how many is up to the
     * publisher: hnrss.org sends 20 unless asked for more, so a reader that takes
     * the URL at face value never sees the rest of the front page. Services that
     * understand a count parameter answer with everything they have, the rest
     * ignore a parameter they do not know, and a server that rejects the extra
     * parameter outright is asked again for the URL as it stands.
     */
    private SafeHttpClient.FetchedContent fetchEntries(String url) {
        String withCount = withEntryCount(url, maxEntries);
        if (!withCount.equals(url)) {
            try {
                return httpClient.get(withCount);
            } catch (RuntimeException e) {
                log.debug("Asking {} for {} entries failed ({}); fetching it unchanged",
                        url, maxEntries, e.getMessage());
            }
        }
        return httpClient.get(url);
    }

    static String withEntryCount(String url, int count) {
        if (url == null || url.isBlank() || count <= 0) {
            return url;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return url;
        }
        if (uri.getRawFragment() != null) {
            return url;
        }
        String host = uri.getHost();
        if (host != null && (host.equalsIgnoreCase("reddit.com")
                || host.toLowerCase(Locale.ROOT).endsWith(".reddit.com"))) {
            // Reddit's anonymous RSS endpoint is tightly rate-limited. Avoid a
            // cache-busting count query that gains no extra entries there.
            return url;
        }
        String query = uri.getRawQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                String name = pair.split("=", 2)[0].toLowerCase(Locale.ROOT);
                if (ENTRY_COUNT_PARAMETERS.contains(name)) {
                    return url;
                }
            }
        }
        String trimmed = url.trim();
        String separator = trimmed.indexOf('?') < 0 ? "?"
                : trimmed.endsWith("?") || trimmed.endsWith("&") ? "" : "&";
        return trimmed + separator + "count=" + count;
    }

    /** Guards against fetching an unbounded number of URLs from one add-feed action. */
    private static final int MAX_DISCOVERY_ATTEMPTS = 12;

    /** Conventional locations a feed sits at when a page advertises none. */
    private static final List<String> WELL_KNOWN_FEED_PATHS = List.of(
            "/feed", "/feed/", "/rss", "/rss.xml", "/feed.xml", "/atom.xml",
            "/index.xml", "/feeds/posts/default", "/?feed=rss2");

    private record Discovered(String url, ParsedFeed feed) {}

    /**
     * Finds a real feed for a page that is not itself one. Candidates are tried in
     * order of confidence — declared {@code <link>} feeds, then feed-like anchors,
     * then the site's conventional feed paths — and the first that actually parses
     * as RSS/Atom wins. Each candidate is SSRF-validated and fetched at most once.
     */
    private Discovered discoverFeed(String html, String baseUrl) {
        java.util.LinkedHashSet<String> attempted = new java.util.LinkedHashSet<>();
        int attempts = 0;
        for (String candidate : candidateFeedUrls(html, baseUrl)) {
            URI validated;
            try {
                validated = httpClient.validateAndResolve(candidate);
            } catch (RuntimeException invalid) {
                continue;
            }
            String url = validated.toString();
            if (url.equals(baseUrl) || !attempted.add(url)) {
                continue;
            }
            if (++attempts > MAX_DISCOVERY_ATTEMPTS) {
                break;
            }
            try {
                SafeHttpClient.FetchedContent fetched = httpClient.get(url);
                String finalUrl = fetched.finalUri().toString();
                ParsedFeed parsed = parseFeed(fetched.body(), finalUrl);
                return new Discovered(finalUrl, parsed);
            } catch (Exception notAFeed) {
                log.debug("Feed candidate {} did not parse: {}", url, notAFeed.getMessage());
            }
        }
        throw new IllegalArgumentException(
                "Could not find an RSS/Atom feed at that address. Try the feed's direct URL.");
    }

    /** Ordered, de-duplicated feed candidates gathered from a page's markup and site conventions. */
    private List<String> candidateFeedUrls(String html, String baseUrl) {
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        Document doc = Jsoup.parse(html, baseUrl);

        for (Element link : doc.select("link[rel~=(?i)alternate][type]")) {
            String type = link.attr("type").toLowerCase(Locale.ROOT);
            if (type.contains("rss") || type.contains("atom") || type.contains("xml")) {
                addCandidate(candidates, link.attr("abs:href"));
            }
        }
        for (Element link : doc.select("link[type]")) {
            String type = link.attr("type").toLowerCase(Locale.ROOT);
            if (type.contains("rss") || type.contains("atom")) {
                addCandidate(candidates, link.attr("abs:href"));
            }
        }
        for (Element anchor : doc.select("a[href]")) {
            String href = anchor.attr("abs:href");
            String lower = href.toLowerCase(Locale.ROOT);
            if (lower.contains("/feed") || lower.contains("rss") || lower.contains("atom")
                    || lower.endsWith(".xml")) {
                addCandidate(candidates, href);
            }
        }
        candidates.addAll(wellKnownFeedUrls(baseUrl));
        return new java.util.ArrayList<>(candidates);
    }

    private static void addCandidate(java.util.Set<String> candidates, String href) {
        if (href != null && !href.isBlank()) {
            candidates.add(href.trim());
        }
    }

    /** The site's conventional feed paths, resolved against the page's origin. */
    private static List<String> wellKnownFeedUrls(String baseUrl) {
        URI base;
        try {
            base = URI.create(baseUrl);
        } catch (IllegalArgumentException invalid) {
            return List.of();
        }
        if (base.getScheme() == null || base.getRawAuthority() == null) {
            return List.of();
        }
        String origin = base.getScheme() + "://" + base.getRawAuthority();
        return WELL_KNOWN_FEED_PATHS.stream().map(path -> origin + path).toList();
    }

    private ParsedFeed parseFeed(String body, String feedUrl) throws Exception {
        SyndFeedInput input = new SyndFeedInput();
        input.setPreserveWireFeed(false);
        try (XmlReader reader = new XmlReader(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))) {
            SyndFeed syndFeed = input.build(reader);
            String title = syndFeed.getTitle();
            String siteUrl = syndFeed.getLink();
            if (siteUrl == null || siteUrl.isBlank()) {
                siteUrl = feedUrl;
            }
            var entries = syndFeed.getEntries().stream().map(this::toEntry).toList();
            return new ParsedFeed(title, siteUrl, entries);
        }
    }

    private ParsedEntry toEntry(SyndEntry entry) {
        String guid = entry.getUri();
        if (guid == null || guid.isBlank()) {
            guid = entry.getLink();
        }
        if (guid == null || guid.isBlank()) {
            guid = entry.getTitle() + "|" + (entry.getPublishedDate() == null ? "" : entry.getPublishedDate().getTime());
        }
        String title = entry.getTitle() == null || entry.getTitle().isBlank() ? "(untitled)" : entry.getTitle().trim();
        String url = entry.getLink();
        String author = entry.getAuthor();
        Instant published = toInstant(entry.getPublishedDate());
        if (published == null) {
            published = toInstant(entry.getUpdatedDate());
        }
        String summary = contentValue(entry.getDescription());
        String content = "";
        if (entry.getContents() != null && !entry.getContents().isEmpty()) {
            content = contentValue(entry.getContents().getFirst());
        }
        return new ParsedEntry(guid, title, url, author, published, summary, content);
    }

    private static String contentValue(SyndContent content) {
        return content == null || content.getValue() == null ? "" : content.getValue();
    }

    private static Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }

    private record ParsedFeed(String title, String siteUrl, List<ParsedEntry> entries) {}

    public record DefaultFeed(String key, String title, String url, String category) {}

    private record ParsedEntry(
            String guid,
            String title,
            String url,
            String author,
            Instant publishedAt,
            String summaryHtml,
            String contentHtml
    ) {}
}
