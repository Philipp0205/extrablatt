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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        AppProperties properties = new AppProperties(
                "from@example.com", null, "remember-me",
                null, new AppProperties.Feeds(maxEntries), null, null, null, null);
        return new FeedService(feedRepository, articleRepository, httpClient, new HtmlSanitizer(), properties);
    }

    private static Feed feed(String url) {
        return new Feed(1L, "Example", url, "https://example.com/", null,
                Instant.EPOCH, Instant.EPOCH);
    }

    private static Feed newsletterFeed(String senderUrl) {
        return new Feed(2L, "A Newsletter", senderUrl, null, "Newsletters", null,
                Instant.EPOCH, Instant.EPOCH, 0, FeedSource.NEWSLETTER);
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
    void receivingANewsletterIssueCreatesAFeedForItsSenderTheFirstTime() {
        when(feedRepository.findByUrl(UID, "newsletter:editor@example.com")).thenReturn(Optional.empty());
        when(feedRepository.countByUser(UID)).thenReturn(0L);
        when(feedRepository.findOrCreateNewsletterFeed(UID, "newsletter:editor@example.com",
                "The Editor", "Newsletters")).thenReturn(newsletterFeed("newsletter:editor@example.com"));
        when(articleRepository.existsByFeedIdAndGuid(2L, "message-1")).thenReturn(false);
        when(articleRepository.insert(eq(2L), eq("message-1"), eq("Issue #1"), isNull(), eq("The Editor"),
                any(), isNull(), anyString())).thenReturn(42L);

        long id = service(100).receiveNewsletterIssue(UID, "Editor@Example.com", "The Editor",
                "message-1", "Issue #1", Instant.EPOCH, "<p>Hello</p>");

        assertEquals(42L, id);
        verify(feedRepository).clearError(2L);
    }

    @Test
    void receivingANewsletterIssueFromAKnownSenderReusesItsFeed() {
        Feed existing = newsletterFeed("newsletter:editor@example.com");
        when(feedRepository.findByUrl(UID, "newsletter:editor@example.com")).thenReturn(Optional.of(existing));
        when(articleRepository.existsByFeedIdAndGuid(2L, "message-2")).thenReturn(false);
        when(articleRepository.insert(anyLong(), anyString(), anyString(), any(), any(),
                any(), any(), anyString())).thenReturn(43L);

        service(100).receiveNewsletterIssue(UID, "editor@example.com", "The Editor",
                "message-2", "Issue #2", Instant.EPOCH, "<p>Hello again</p>");

        verify(feedRepository, never()).findOrCreateNewsletterFeed(anyLong(), anyString(), any(), any());
    }

    @Test
    void receivingTheSameNewsletterIssueTwiceIsIgnored() {
        Feed existing = newsletterFeed("newsletter:editor@example.com");
        when(feedRepository.findByUrl(UID, "newsletter:editor@example.com")).thenReturn(Optional.of(existing));
        when(articleRepository.existsByFeedIdAndGuid(2L, "message-1")).thenReturn(true);

        long id = service(100).receiveNewsletterIssue(UID, "editor@example.com", "The Editor",
                "message-1", "Issue #1", Instant.EPOCH, "<p>Hello</p>");

        assertEquals(FeedService.NEWSLETTER_DUPLICATE, id);
        verify(articleRepository, never()).insert(anyLong(), anyString(), anyString(), any(), any(),
                any(), any(), anyString());
    }

    @Test
    void aNewSenderIsDroppedOncePerAccountFeedLimitIsReached() {
        when(feedRepository.findByUrl(UID, "newsletter:editor@example.com")).thenReturn(Optional.empty());
        when(feedRepository.countByUser(UID)).thenReturn(50L);

        long id = service(100).receiveNewsletterIssue(UID, "editor@example.com", "The Editor",
                "message-1", "Issue #1", Instant.EPOCH, "<p>Hello</p>");

        assertEquals(FeedService.NEWSLETTER_FEED_LIMIT_REACHED, id);
        verify(feedRepository, never()).findOrCreateNewsletterFeed(anyLong(), anyString(), any(), any());
    }

    @Test
    void refreshingAllFeedsSkipsNewslettersSinceTheyHaveNothingToPoll() {
        Feed newsletter = newsletterFeed("newsletter:editor@example.com");
        Feed clipping = new Feed(3L, "Pasted URLs", Feed.CLIPPING_URL, null, "Pasted", null,
                Instant.EPOCH, Instant.EPOCH, 0, FeedSource.CLIPPING);
        when(feedRepository.findAllAcrossUsers()).thenReturn(java.util.List.of(newsletter, clipping));

        service(100).refreshAll();

        verify(httpClient, never()).get(anyString());
    }

    @Test
    void renamingACategoryUpdatesEveryFeedThatHasIt() {
        when(feedRepository.renameCategory(UID, "Technology", "Tech")).thenReturn(3);

        int updated = service(100).renameCategory(UID, "Technology", "Tech");

        assertEquals(3, updated);
        verify(feedRepository).renameCategory(UID, "Technology", "Tech");
    }

    @Test
    void renamingUncategorizedIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service(100).renameCategory(UID, "Uncategorized", "Tech"));
        assertThrows(IllegalArgumentException.class,
                () -> service(100).renameCategory(UID, "", "Tech"));
    }

    @Test
    void renamingToABlankNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service(100).renameCategory(UID, "Technology", "  "));
    }

    @Test
    void renamingACategoryToItsOwnNameIsANoOp() {
        int updated = service(100).renameCategory(UID, "Technology", " Technology ");

        assertEquals(0, updated);
        verify(feedRepository, never()).renameCategory(anyLong(), anyString(), anyString());
    }
}
