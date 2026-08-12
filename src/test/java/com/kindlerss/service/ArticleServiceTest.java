package com.kindlerss.service;

import com.kindlerss.domain.Article;
import com.kindlerss.repository.ArticleRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleServiceTest {

    private static final String STORY_URL = "https://example.com/story";
    private static final String ITEM_URL = "https://news.ycombinator.com/item?id=12345";

    /** What a Hacker News feed carries instead of the article: two links and a score. */
    private static final String FEED_SUMMARY = """
            <p>Article URL: <a href="https://example.com/story">story</a></p>
            <p>Comments URL:
              <a href="https://news.ycombinator.com/item?id=12345">comments</a>
            </p>
            <p>Points: 114</p>
            """;

    private final ArticleRepository articleRepository = mock(ArticleRepository.class);
    private final SafeHttpClient httpClient = mock(SafeHttpClient.class);
    private final ArticleService service = new ArticleService(
            articleRepository, httpClient, new HtmlSanitizer(), new HackerNewsReader(httpClient));

    private static Article article(String url, String summaryHtml, String extractedHtml) {
        return new Article(1L, 1L, "guid", "Story", url, null, null, summaryHtml,
                null, extractedHtml, false, null, null, null, "Hacker News");
    }

    private static String itemPage() {
        try (var in = ArticleServiceTest.class.getResourceAsStream("/hacker-news-item.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static SafeHttpClient.FetchedContent page(String url, String body) {
        return new SafeHttpClient.FetchedContent(URI.create(url), body, "text/html; charset=utf-8");
    }

    @Test
    void keepsHackerNewsCommentsAvailableAlongsideExtractedContent() {
        Article article = article(STORY_URL, FEED_SUMMARY, "<p>Extracted story</p>");

        assertEquals(ITEM_URL, service.findCommentsUrl(article).orElseThrow());
    }

    @Test
    void readsTheLinkedArticleRatherThanWhatTheFeedCarried() {
        when(httpClient.getPage(STORY_URL)).thenReturn(page(STORY_URL, """
                <html><body><article><h1>Story</h1>
                <p>The first paragraph of a story that is long enough to be read as one,
                   with sentences that carry actual words rather than markup.</p>
                <p>A second paragraph, because one is rarely enough to be sure.</p>
                </article></body></html>
                """));

        String html = service.getContentHtml(article(STORY_URL, FEED_SUMMARY, null), false);

        assertTrue(html.contains("The first paragraph"), html);
        assertFalse(html.contains("Points: 114"), html);
        verify(articleRepository).updateExtractedContent(anyLong(), anyString());
    }

    @Test
    void anEntryThatLinksToAHackerNewsItemIsReadAsThatItem() {
        when(httpClient.getPage(ITEM_URL)).thenReturn(page(ITEM_URL, itemPage()));

        String html = service.getContentHtml(article(ITEM_URL, FEED_SUMMARY, null), false);

        assertTrue(html.contains("I read on an e-reader"), html);
        assertTrue(html.contains("Hacker News discussion"), html);
        verify(articleRepository).updateExtractedContent(anyLong(), anyString());
    }

    @Test
    void aLinkedPageThatRefusesToBeReadFallsBackToTheDiscussion() {
        when(httpClient.getPage(STORY_URL)).thenThrow(new SafeHttpClient.FetchException("HTTP 403", 403));
        when(httpClient.getPage(ITEM_URL)).thenReturn(page(ITEM_URL, itemPage()));

        String html = service.getContentHtml(article(STORY_URL, FEED_SUMMARY, null), false);

        assertTrue(html.contains("HTTP 403"), html);
        assertTrue(html.contains("A feed reader that mails articles to a Kindle."), html);
        // The page may be readable later, so the discussion does not take its place.
        verify(articleRepository, never()).updateExtractedContent(anyLong(), anyString());
    }

    @Test
    void anItemWithNothingOnItYetSaysThatRatherThanNothing() {
        when(httpClient.getPage(ITEM_URL)).thenReturn(page(ITEM_URL,
                "<html><body><table class=\"fatitem\"></table></body></html>"));

        String html = service.getContentHtml(article(ITEM_URL, FEED_SUMMARY, null), false);

        assertTrue(html.contains("no text and no comments yet"), html);
        assertTrue(html.contains("Points: 114"), html);
    }

    @Test
    void whenNothingCanBeFetchedTheFeedSummarySaysWhy() {
        when(httpClient.getPage(anyString()))
                .thenThrow(new SafeHttpClient.FetchException("DNS resolution failed"));

        String html = service.getContentHtml(article(STORY_URL, "<p>A summary line.</p>", null), false);

        assertTrue(html.contains("could not be fetched"), html);
        assertTrue(html.contains("DNS resolution failed"), html);
        assertTrue(html.contains("A summary line."), html);
    }

    @Test
    void aLinkThatIsNotAWebPageIsNotRunThroughExtraction() {
        when(httpClient.getPage(STORY_URL)).thenReturn(new SafeHttpClient.FetchedContent(
                URI.create(STORY_URL), "%PDF-1.7", "application/pdf"));

        String html = service.getContentHtml(article(STORY_URL, "<p>A summary line.</p>", null), false);

        assertTrue(html.contains("application/pdf"), html);
        assertTrue(html.contains("A summary line."), html);
    }

    @Test
    void contentThatHasAlreadyBeenExtractedIsNotFetchedAgain() {
        String html = service.getContentHtml(article(STORY_URL, FEED_SUMMARY, "<p>Extracted story</p>"), false);

        assertEquals("<p>Extracted story</p>", html);
        verify(httpClient, never()).getPage(anyString());
    }
}
