package com.kindlerss.service;

import com.kindlerss.domain.Article;
import com.kindlerss.domain.Feed;
import com.kindlerss.domain.FeedSource;
import com.kindlerss.repository.ArticleRepository;
import com.kindlerss.repository.FeedRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleServiceTest {

    private static final long UID = 9L;
    private static final String PAGE_URL = "https://example.com/long-read";
    private static final String PAGE_HTML = """
            <!DOCTYPE html>
            <html lang="en"><head>
              <title>Why paper still wins</title>
            </head><body>
              <nav><a href="/">Home</a></nav>
              <article>
                <h1>Why paper still wins</h1>
                <p class="byline">By Ada Lovelace</p>
                <p>Electronic ink is pleasant, but a printed page still has a quiet
                   authority that a glowing rectangle cannot match. The weight of the
                   paper, the smell of the ink, and the way a folded corner keeps your
                   place are all part of the reading, not distractions from it.</p>
                <p>That is why a document that arrives on a Kindle as a real book,
                   rather than a web page with menus and pop-ups, feels like something
                   you can finish. The argument is not that screens are bad. It is that
                   an article deserves the same calm as a chapter.</p>
                <p>Extractors that look for the longest run of paragraphs will keep
                   this block and drop the navigation around it, which is exactly what
                   we want when a URL is pasted in to be sent as an EPUB.</p>
              </article>
            </body></html>
            """;

    private final ArticleRepository articleRepository = mock(ArticleRepository.class);
    private final FeedRepository feedRepository = mock(FeedRepository.class);
    private final SafeHttpClient httpClient = mock(SafeHttpClient.class);
    private ArticleService service;

    @BeforeEach
    void setUp() {
        service = new ArticleService(articleRepository, feedRepository, httpClient, new HtmlSanitizer());
    }

    @Test
    void keepsHackerNewsCommentsAvailableAlongsideExtractedContent() {
        Article article = new Article(
                1L, 1L, "guid", "Story", "https://example.com/story", null, null,
                """
                <p>Article URL: <a href="https://example.com/story">story</a></p>
                <p>Comments URL:
                  <a href="https://news.ycombinator.com/item?id=12345">comments</a>
                </p>
                """,
                null, "<p>Extracted story</p>", false, null, null, null, "Hacker News");

        assertEquals("https://news.ycombinator.com/item?id=12345",
                service.findCommentsUrl(article).orElseThrow());
    }

    @Test
    void aPastedUrlIsFetchedExtractedAndStoredOnTheClippingFeed() {
        Feed clipping = clippingFeed();
        when(httpClient.get(PAGE_URL)).thenReturn(fetched(PAGE_URL, PAGE_HTML, "text/html"));
        when(feedRepository.findOrCreateClippingFeed(UID)).thenReturn(clipping);
        when(articleRepository.findByFeedIdAndGuid(UID, 11L, PAGE_URL)).thenReturn(Optional.empty());
        when(articleRepository.insert(eq(11L), eq(PAGE_URL), anyString(), eq(PAGE_URL), any(),
                any(), isNull(), isNull())).thenReturn(42L);
        Article stored = storedArticle(42L, "Why paper still wins");
        when(articleRepository.findById(UID, 42L)).thenReturn(Optional.of(stored));

        Article imported = service.importFromUrl(UID, "  " + PAGE_URL + "  ");

        assertEquals(42L, imported.id());
        verify(articleRepository).updateExtractedContent(eq(42L), org.mockito.ArgumentMatchers.argThat(
                html -> html.contains("Electronic ink is pleasant")));
    }

    @Test
    void pastingTheSameUrlAgainRefreshesTheExistingArticle() {
        Feed clipping = clippingFeed();
        Article existing = storedArticle(7L, "Old title");
        when(httpClient.get(PAGE_URL)).thenReturn(fetched(PAGE_URL, PAGE_HTML, "text/html"));
        when(feedRepository.findOrCreateClippingFeed(UID)).thenReturn(clipping);
        when(articleRepository.findByFeedIdAndGuid(UID, 11L, PAGE_URL)).thenReturn(Optional.of(existing));
        when(articleRepository.findById(UID, 7L)).thenReturn(Optional.of(existing));

        Article imported = service.importFromUrl(UID, PAGE_URL);

        assertEquals(7L, imported.id());
        verify(articleRepository, never()).insert(anyLong(), anyString(), anyString(), any(), any(),
                any(), any(), any());
        verify(articleRepository).updateImportedContent(eq(7L), anyString(), any(), anyString());
    }

    @Test
    void aFeedUrlIsRejectedInsteadOfBeingStoredAsAnArticle() {
        when(httpClient.get("https://example.com/feed.xml")).thenReturn(fetched(
                "https://example.com/feed.xml",
                "<rss version=\"2.0\"><channel><title>News</title></channel></rss>",
                "application/rss+xml"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.importFromUrl(UID, "https://example.com/feed.xml"));
        assertTrue(error.getMessage().contains("Add feed"));
        verify(feedRepository, never()).findOrCreateClippingFeed(anyLong());
    }

    @Test
    void anEmptyPasteIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.importFromUrl(UID, "  "));
        verify(httpClient, never()).get(anyString());
    }

    @Test
    void aBareHostIsTurnedIntoHttpsBeforeFetching() {
        assertEquals("https://example.com/story", ArticleService.normalizeHttpUrl("example.com/story"));
        assertEquals("https://example.com/story",
                ArticleService.normalizeHttpUrl("<https://example.com/story>"));
    }

    private static Feed clippingFeed() {
        return new Feed(11L, "Pasted URLs", Feed.CLIPPING_URL, null, "Pasted", null,
                Instant.EPOCH, Instant.EPOCH, 0, FeedSource.CLIPPING);
    }

    private static Article storedArticle(long id, String title) {
        return new Article(id, 11L, PAGE_URL, title, PAGE_URL, null, Instant.EPOCH,
                null, null, "<p>body</p>", false, null, Instant.EPOCH, Instant.EPOCH, "Pasted URLs");
    }

    private static SafeHttpClient.FetchedContent fetched(String url, String body, String type) {
        return new SafeHttpClient.FetchedContent(URI.create(url), body, type);
    }
}
