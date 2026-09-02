package com.kindlerss.service;

import com.kindlerss.domain.Article;
import org.junit.jupiter.api.Test;
import org.jsoup.Jsoup;

import java.net.URI;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscussionParserTest {

    private final SafeHttpClient httpClient = mock(SafeHttpClient.class);
    private final DiscussionParser parser = new DiscussionParser(httpClient);

    @Test
    void redditPostIncludesItsBodyAndComments() {
        String postUrl = "https://www.reddit.com/r/stuttgart/comments/1w4c3i8/karlsruhe_vs_stuttgart/";
        String feedUrl = postUrl + ".rss";
        when(httpClient.get(feedUrl)).thenReturn(fetched(feedUrl, """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>Karlsruhe vs. Stuttgart : stuttgart</title>
                  <entry>
                    <author><name>/u/highlasagna</name></author>
                    <content type="html">&lt;div class="md"&gt;&lt;p&gt;Which university should I choose?&lt;/p&gt;&lt;/div&gt;
                      submitted by &lt;a href="https://reddit.com/u/highlasagna"&gt;/u/highlasagna&lt;/a&gt;</content>
                    <id>t3_1w4c3i8</id>
                    <link href="%s"/>
                    <title>Karlsruhe vs. Stuttgart</title>
                    <updated>2026-09-01T13:31:33Z</updated>
                  </entry>
                  <entry>
                    <author><name>/u/helpful_reader</name></author>
                    <content type="html">&lt;div class="md"&gt;&lt;p&gt;Visit both departments before deciding.&lt;/p&gt;&lt;/div&gt;</content>
                    <id>t1_example</id>
                    <link href="%s"/>
                    <title>/u/helpful_reader on Karlsruhe vs. Stuttgart</title>
                    <updated>2026-09-01T14:00:00Z</updated>
                  </entry>
                </feed>
                """.formatted(postUrl, postUrl), "application/atom+xml"));

        String html = parser.parse(article(postUrl, null, null, "Stuttgart")).orElseThrow();

        assertTrue(html.contains("<h2>Post</h2>"));
        assertTrue(html.contains("Which university should I choose?"));
        assertTrue(html.contains("<h2>Comments</h2>"));
        assertTrue(html.contains("/u/helpful_reader"));
        assertTrue(html.contains("Visit both departments before deciding."));
    }

    @Test
    void hackerNewsIncludesTheLinkedArticleAndThreadComments() {
        String articleUrl = "https://example.com/efficient-computing";
        String commentsUrl = "https://news.ycombinator.com/item?id=49529898";
        when(httpClient.get(articleUrl)).thenReturn(fetched(articleUrl, """
                <html><head><title>Efficient computing</title></head><body>
                  <article>
                    <h1>Efficient computing</h1>
                    <p>A sufficiently long introduction to efficient computing and its practical
                       trade-offs gives the readability parser enough prose to retain this article.</p>
                    <p>The second paragraph explains how the measurements were gathered and why
                       readers should compare throughput, latency, and energy use together.</p>
                  </article>
                </body></html>
                """, "text/html"));
        when(httpClient.get(commentsUrl)).thenReturn(fetched(commentsUrl, """
                <html><body><table>
                  <tr class="athing comtr" id="49530001"><td>
                    <span class="comhead"><a class="hnuser">alice</a>
                      <span class="age">12 minutes ago</span></span>
                    <div class="comment"><div class="commtext c00">The energy numbers are the useful part.</div></div>
                  </td></tr>
                  <tr class="athing comtr" id="49530002"><td>
                    <span class="comhead"><a class="hnuser">bob</a>
                      <span class="age">8 minutes ago</span></span>
                    <div class="comment"><div class="commtext c00"><p>I would also compare memory bandwidth.</p></div></div>
                  </td></tr>
                </table></body></html>
                """, "text/html"));

        String summary = "<p>Comments URL: <a href=\"" + commentsUrl + "\">comments</a></p>";
        String html = parser.parse(article(articleUrl, summary, null, "Hacker News")).orElseThrow();

        assertTrue(html.contains("<h2>Post</h2>"));
        assertTrue(html.contains("sufficiently long introduction"));
        assertTrue(html.contains("<h2>Comments</h2>"));
        assertTrue(Jsoup.parseBodyFragment(html).text().contains("alice · 12 minutes ago"));
        assertTrue(html.contains("The energy numbers are the useful part."));
        assertTrue(html.contains("memory bandwidth"));
    }

    @Test
    void derivesRedditCommentFeedWithoutKeepingTrackingParameters() {
        assertEquals(
                "https://www.reddit.com/r/stuttgart/comments/abc123/a_post/.rss",
                DiscussionParser.redditCommentsFeedUrl(
                        "https://www.reddit.com/r/stuttgart/comments/abc123/a_post/?utm_source=rss"));
    }

    private static Article article(String url, String summary, String content, String feedTitle) {
        return new Article(1L, 2L, "guid", "Example", url, null, Instant.EPOCH,
                summary, content, null, false, null, Instant.EPOCH, Instant.EPOCH, feedTitle);
    }

    private static SafeHttpClient.FetchedContent fetched(String url, String body, String type) {
        return new SafeHttpClient.FetchedContent(URI.create(url), body, type);
    }
}
