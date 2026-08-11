package com.kindlerss.service;

import com.kindlerss.domain.Article;
import com.kindlerss.repository.ArticleRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ArticleServiceTest {

    private final ArticleService service = new ArticleService(
            mock(ArticleRepository.class), mock(SafeHttpClient.class), new HtmlSanitizer());

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
}
