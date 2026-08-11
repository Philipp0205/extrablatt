package com.kindlerss.service;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.Feed;
import com.kindlerss.repository.ArticleRepository;
import com.kindlerss.repository.FeedRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
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

    private final FeedRepository feedRepository;
    private final ArticleRepository articleRepository;
    private final SafeHttpClient httpClient;
    private final HtmlSanitizer sanitizer;
    private final int maxEntries;

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
    }

    public List<Feed> listFeeds() {
        return feedRepository.findAllWithUnreadCounts();
    }

    public Optional<Feed> findById(long id) {
        return feedRepository.findById(id);
    }

    public List<DefaultFeed> defaultFeeds() {
        Set<String> existingUrls = feedRepository.findAll().stream()
                .map(Feed::url)
                .collect(java.util.stream.Collectors.toSet());
        return DEFAULT_FEEDS.stream().filter(feed -> !existingUrls.contains(feed.url())).toList();
    }

    public Optional<DefaultFeed> defaultFeed(String key) {
        return DEFAULT_FEEDS.stream().filter(feed -> feed.key().equals(key)).findFirst();
    }

    @Transactional
    public Feed addFeed(String rawUrl) {
        return addFeed(rawUrl, null);
    }

    @Transactional
    public Feed addFeed(String rawUrl, String category) {
        String trimmed = rawUrl == null ? "" : rawUrl.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Feed URL is required");
        }
        SafeHttpClient.FetchedContent fetched = httpClient.get(trimmed);
        String feedUrl = fetched.finalUri().toString();
        String body = fetched.body();

        ParsedFeed parsed;
        try {
            parsed = parseFeed(body, feedUrl);
        } catch (Exception directParseError) {
            Optional<String> discovered = discoverFeedUrl(body, feedUrl);
            if (discovered.isEmpty()) {
                throw new IllegalArgumentException("Could not parse RSS/Atom and no alternate feed link found");
            }
            feedUrl = discovered.get();
            if (feedRepository.findByUrl(feedUrl).isPresent()) {
                throw new IllegalArgumentException("Feed already exists");
            }
            SafeHttpClient.FetchedContent feedFetched = httpClient.get(feedUrl);
            feedUrl = feedFetched.finalUri().toString();
            try {
                parsed = parseFeed(feedFetched.body(), feedUrl);
            } catch (Exception e) {
                throw new IllegalArgumentException("Discovered feed could not be parsed: " + e.getMessage(), e);
            }
        }

        if (feedRepository.findByUrl(feedUrl).isPresent()) {
            throw new IllegalArgumentException("Feed already exists");
        }

        String title = parsed.title() == null || parsed.title().isBlank() ? feedUrl : parsed.title().trim();
        Feed feed = feedRepository.insert(title, feedUrl, parsed.siteUrl(), category);
        storeEntries(feed, parsed);
        return feedRepository.findById(feed.id()).orElse(feed);
    }

    @Transactional
    public boolean deleteFeed(long id) {
        return feedRepository.deleteById(id);
    }

    @Transactional
    public boolean categorizeFeed(long id, String category) {
        return feedRepository.updateCategory(id, category);
    }

    @Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT2M")
    public void scheduledRefresh() {
        log.info("Scheduled feed refresh starting");
        refreshAll();
    }

    public void refreshAll() {
        for (Feed feed : feedRepository.findAll()) {
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

    private Optional<String> discoverFeedUrl(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        Elements links = doc.select("link[rel~=alternate]");
        for (Element link : links) {
            String type = link.hasAttr("type") ? link.attr("type").toLowerCase(Locale.ROOT) : "";
            String href = link.hasAttr("abs:href") ? link.attr("abs:href") : link.attr("href");
            if (href == null || href.isBlank()) {
                continue;
            }
            if (type.contains("rss") || type.contains("atom") || type.contains("xml")) {
                try {
                    URI validated = httpClient.validateAndResolve(href);
                    return Optional.of(validated.toString());
                } catch (SafeHttpClient.FetchException ignored) {
                    // try next
                }
            }
        }
        return Optional.empty();
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
