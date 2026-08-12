package com.kindlerss.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HackerNewsReaderTest {

    private static final String ITEM_URL = "https://news.ycombinator.com/item?id=4242";

    private final SafeHttpClient httpClient = mock(SafeHttpClient.class);
    private final HackerNewsReader reader = new HackerNewsReader(httpClient);

    private static String itemPage() {
        try (var in = HackerNewsReaderTest.class.getResourceAsStream("/hacker-news-item.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void recognizesItemLinksAndNothingElse() {
        assertEquals(Optional.of(ITEM_URL),
                HackerNewsReader.itemUrl("https://news.ycombinator.com/item?id=4242"));
        assertEquals(Optional.of(ITEM_URL),
                HackerNewsReader.itemUrl("http://www.news.ycombinator.com/item?id=4242#4301"));
        assertTrue(HackerNewsReader.itemUrl("https://example.com/item?id=4242").isEmpty());
        assertTrue(HackerNewsReader.itemUrl("https://news.ycombinator.com/newest").isEmpty());
        assertTrue(HackerNewsReader.itemUrl(null).isEmpty());
    }

    @Test
    void readsTheSubmittedTextAndTheDiscussion() {
        when(httpClient.getPage(ITEM_URL)).thenReturn(
                new SafeHttpClient.FetchedContent(URI.create(ITEM_URL), itemPage(), "text/html"));

        String html = reader.read(ITEM_URL).html();

        assertTrue(html.contains("I read on an e-reader"), html);
        assertTrue(html.contains("<h2>Hacker News discussion</h2>"), html);
        assertTrue(html.contains("<strong>first</strong> · 27 minutes ago"), html);
        assertTrue(html.contains("A feed reader that mails articles to a Kindle."), html);
        // The reply link belongs to the page, not to what was written.
        assertFalse(html.contains("reply?id="), html);
    }

    @Test
    void showsHowDeepAReplySitsAndKeepsTheIndentReadable() {
        when(httpClient.getPage(ITEM_URL)).thenReturn(
                new SafeHttpClient.FetchedContent(URI.create(ITEM_URL), itemPage(), "text/html"));

        String html = reader.read(ITEM_URL).html();

        assertTrue(html.contains("<blockquote><p><strong>replier</strong>"), html);
        // A reply nine levels down is indented as if it sat at the deepest level shown.
        assertTrue(html.contains("<blockquote>".repeat(4) + "<p><strong>deep</strong>"), html);
        assertFalse(html.contains("<blockquote>".repeat(5)), html);
    }

    @Test
    void skipsCommentsThatCarryNoText() {
        when(httpClient.getPage(ITEM_URL)).thenReturn(
                new SafeHttpClient.FetchedContent(URI.create(ITEM_URL), itemPage(), "text/html"));

        String html = reader.read(ITEM_URL).html();

        assertFalse(html.contains("flagged"), html);
        assertEquals(3, html.split("<p><strong>", -1).length - 1, html);
    }

    @Test
    void anItemPageThatCannotBeFetchedSaysSoRatherThanFailing() {
        when(httpClient.getPage(anyString())).thenThrow(new SafeHttpClient.FetchException("HTTP 503", 503));

        HackerNewsReader.Item item = reader.read(ITEM_URL);

        assertFalse(item.found());
        assertEquals("HTTP 503", item.failure());
    }

    @Test
    void anItemWithNothingOnItYetIsNotMistakenForAnUnreadablePage() {
        when(httpClient.getPage(ITEM_URL)).thenReturn(new SafeHttpClient.FetchedContent(
                URI.create(ITEM_URL), "<html><body><p>No such item.</p></body></html>", "text/html"));

        HackerNewsReader.Item item = reader.read(ITEM_URL);

        assertFalse(item.found());
        assertEquals("the item has no text and no comments yet", item.failure());
    }
}
