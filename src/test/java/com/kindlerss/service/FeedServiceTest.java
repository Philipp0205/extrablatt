package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.Feed;
import com.kindlerss.repository.ArticleRepository;
import com.kindlerss.repository.FeedRepository;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.net.URI;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

    private FeedService service(int maxEntries) {
        AppProperties properties = new AppProperties(
                "password", "kindle@example.com", "from@example.com", "remember-me",
                null, new AppProperties.Feeds(maxEntries), null);
        return new FeedService(feedRepository, articleRepository, httpClient, new HtmlSanitizer(), properties);
    }

    private static Feed feed(String url) {
        return new Feed(1L, "Example", url, "https://example.com/", null,
                Instant.EPOCH, Instant.EPOCH);
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
}
