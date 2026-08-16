package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.Feed;
import com.kindlerss.domain.FeedSource;
import com.kindlerss.repository.ArticleRepository;
import com.kindlerss.repository.FeedRepository;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedServiceTest {

    private static final String FEED_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <title>Example</title>
              <link>https://example.com/</link>
              <item>
                <title>First</title>
                <link>https://example.com/1</link>
                <guid isPermaLink="false">https://example.com/1</guid>
              </item>
            </channel></rss>
            """;

    private final FeedRepository feedRepository = mock(FeedRepository.class);
    private final ArticleRepository articleRepository = mock(ArticleRepository.class);
    private final SafeHttpClient httpClient = mock(SafeHttpClient.class);

    private static final long UID = 7L;

    private FeedService service(int maxEntries) {
        return service(maxEntries, null);
    }

    private FeedService service(int maxEntries, AppProperties.Newsletters newsletters) {
        AppProperties properties = new AppProperties(
                "from@example.com", null, "remember-me",
                null, new AppProperties.Feeds(maxEntries), null, null, newsletters, null);
        return new FeedService(feedRepository, articleRepository, httpClient, new HtmlSanitizer(), properties);
    }

    private static Feed feed(String url) {
        return new Feed(1L, "Example", url, "https://example.com/", null,
                Instant.EPOCH, Instant.EPOCH);
    }

    private static Feed newsletterFeed(String token) {
        return new Feed(2L, "A Newsletter", "newsletter:" + token, null, null, null,
                Instant.EPOCH, Instant.EPOCH, 0, FeedSource.NEWSLETTER, token);
    }

    private static Answer<SafeHttpClient.FetchedContent> respondWithFeed() {
        return invocation -> new SafeHttpClient.FetchedContent(
                URI.create(invocation.getArgument(0)), FEED_XML, "application/rss+xml");
    }

    @Test
    void aFeedUrlIsAskedForMoreEntriesThanItPublishesByDefault() {
        assertEquals("https://hnrss.org/frontpage?count=100",
                FeedService.withEntryCount("https://hnrss.org/frontpage", 100));
        assertEquals("https://example.com/feed?format=rss&count=100",
                FeedService.withEntryCount("https://example.com/feed?format=rss", 100));
    }

    @Test
    void aUrlThatAlreadySaysHowManyEntriesItWantsIsLeftAlone() {
        assertEquals("https://hnrss.org/frontpage?count=5",
                FeedService.withEntryCount("https://hnrss.org/frontpage?count=5", 100));
        assertEquals("https://example.com/feed?limit=3",
                FeedService.withEntryCount("https://example.com/feed?limit=3", 100));
        assertEquals("https://example.com/feed",
                FeedService.withEntryCount("https://example.com/feed", 0));
        assertEquals("https://www.reddit.com/r/stuttgart/.rss",
                FeedService.withEntryCount("https://www.reddit.com/r/stuttgart/.rss", 100));
    }

    @Test
    void refreshAsksForTheConfiguredNumberOfEntries() {
        when(httpClient.get(anyString())).thenAnswer(respondWithFeed());

        service(100).refreshFeed(feed("https://hnrss.org/frontpage"));

        verify(httpClient).get("https://hnrss.org/frontpage?count=100");
        verify(articleRepository).insert(eq(1L), eq("https://example.com/1"), eq("First"),
                anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void aFeedThatRejectsTheCountIsFetchedAsItStands() {
        when(httpClient.get("https://example.com/feed?count=100"))
                .thenThrow(new SafeHttpClient.FetchException("HTTP 400"));
        when(httpClient.get("https://example.com/feed")).thenAnswer(respondWithFeed());

        service(100).refreshFeed(feed("https://example.com/feed"));

        verify(httpClient, times(2)).get(anyString());
        verify(articleRepository).insert(anyLong(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void addingAFeedStoresTheFirstResponseWithoutFetchingItTwice() {
        String url = "https://www.reddit.com/r/stuttgart/.rss";
        when(httpClient.get(url)).thenAnswer(respondWithFeed());
        when(feedRepository.insert(eq(UID), eq("Example"), eq(url), eq("https://example.com/"), eq("Local")))
                .thenReturn(feed(url));
        when(feedRepository.findById(UID, 1L)).thenReturn(Optional.of(feed(url)));
        when(articleRepository.insert(anyLong(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), anyString())).thenReturn(1L);

        service(100).addFeed(UID, url, "Local");

        verify(httpClient, times(1)).get(url);
    }

    @Test
    void addingAHomepageDiscoversTheFeedItDeclares() {
        String homepage = "https://example.com/";
        String feedUrl = "https://example.com/feed.xml";
        String html = "<html><head><link rel=\"alternate\" type=\"application/rss+xml\" href=\""
                + feedUrl + "\"></head><body>Hello</body></html>";
        when(httpClient.get(homepage))
                .thenReturn(new SafeHttpClient.FetchedContent(URI.create(homepage), html, "text/html"));
        when(httpClient.validateAndResolve(anyString()))
                .thenAnswer(invocation -> URI.create(invocation.getArgument(0)));
        when(httpClient.get(feedUrl)).thenAnswer(respondWithFeed());
        when(feedRepository.insert(eq(UID), eq("Example"), eq(feedUrl), eq("https://example.com/"), isNull()))
                .thenReturn(feed(feedUrl));
        when(feedRepository.findById(UID, 1L)).thenReturn(Optional.of(feed(feedUrl)));
        when(articleRepository.insert(anyLong(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), anyString())).thenReturn(1L);

        Feed added = service(100).addFeed(UID, homepage, null);

        assertEquals(feedUrl, added.url());
        verify(httpClient).get(feedUrl);
    }

    @Test
    void addingAHomepageFallsBackToAWellKnownFeedPath() {
        String homepage = "https://example.com/";
        String feedUrl = "https://example.com/feed";
        String html = "<html><head><title>No feed links here</title></head><body>Hello</body></html>";
        when(httpClient.get(homepage))
                .thenReturn(new SafeHttpClient.FetchedContent(URI.create(homepage), html, "text/html"));
        when(httpClient.validateAndResolve(anyString()))
                .thenAnswer(invocation -> URI.create(invocation.getArgument(0)));
        when(httpClient.get(feedUrl)).thenAnswer(respondWithFeed());
        when(feedRepository.insert(eq(UID), eq("Example"), eq(feedUrl), eq("https://example.com/"), isNull()))
                .thenReturn(feed(feedUrl));
        when(feedRepository.findById(UID, 1L)).thenReturn(Optional.of(feed(feedUrl)));
        when(articleRepository.insert(anyLong(), anyString(), anyString(),
                anyString(), any(), any(), anyString(), anyString())).thenReturn(1L);

        Feed added = service(100).addFeed(UID, homepage, null);

        assertEquals(feedUrl, added.url());
        verify(httpClient).get(feedUrl);
    }

    @Test
    void addingANewsletterFailsWhenNoInboundDomainIsConfigured() {
        assertFalse(service(100).newslettersEnabled());
        assertThrows(IllegalStateException.class, () -> service(100).addNewsletter(UID, "Stratechery", null));
        verify(feedRepository, never()).insertNewsletter(anyLong(), anyString(), any(), anyString());
    }

    @Test
    void addingANewsletterCreatesAFreshInboundAddress() {
        var newsletters = new AppProperties.Newsletters("news.example.com", "shh");
        FeedService service = service(100, newsletters);
        assertTrue(service.newslettersEnabled());
        when(feedRepository.insertNewsletter(eq(UID), eq("Stratechery"), eq("Tech"), anyString()))
                .thenAnswer(invocation -> newsletterFeed(invocation.getArgument(3)));

        Feed added = service.addNewsletter(UID, "Stratechery", "Tech");

        assertTrue(added.isNewsletter());
        assertEquals(added.inboundToken() + "@news.example.com", service.newsletterAddress(added));
    }

    @Test
    void anRssFeedHasNoNewsletterAddress() {
        var newsletters = new AppProperties.Newsletters("news.example.com", "shh");
        assertEquals(null, service(100, newsletters).newsletterAddress(feed("https://example.com/feed")));
    }

    @Test
    void receivingANewsletterIssueStoresItAsAnArticleOfThatFeed() {
        when(articleRepository.existsByFeedIdAndGuid(2L, "message-1")).thenReturn(false);
        when(articleRepository.insert(eq(2L), eq("message-1"), eq("Issue #1"), isNull(), eq("Author"),
                any(), isNull(), anyString())).thenReturn(42L);

        long id = service(100).receiveNewsletterIssue(2L, "message-1", "Issue #1", "Author",
                Instant.EPOCH, "<p>Hello</p>");

        assertEquals(42L, id);
        verify(feedRepository).clearError(2L);
    }

    @Test
    void receivingTheSameNewsletterIssueTwiceIsIgnored() {
        when(articleRepository.existsByFeedIdAndGuid(2L, "message-1")).thenReturn(true);

        long id = service(100).receiveNewsletterIssue(2L, "message-1", "Issue #1", "Author",
                Instant.EPOCH, "<p>Hello</p>");

        assertEquals(-1, id);
        verify(articleRepository, never()).insert(anyLong(), anyString(), anyString(), any(), any(),
                any(), any(), anyString());
    }

    @Test
    void refreshingAllFeedsSkipsNewslettersSinceTheyHaveNothingToPoll() {
        Feed newsletter = newsletterFeed("abc123");
        when(feedRepository.findAllAcrossUsers()).thenReturn(java.util.List.of(newsletter));

        service(100).refreshAll();

        verify(httpClient, never()).get(anyString());
    }
}
