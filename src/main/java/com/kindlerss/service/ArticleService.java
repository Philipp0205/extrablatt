package com.kindlerss.service;

import com.kindlerss.domain.Article;
import com.kindlerss.repository.ArticleRepository;
import net.dankito.readability4j.Readability4J;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ArticleService {

    private static final Logger log = LoggerFactory.getLogger(ArticleService.class);

    private final ArticleRepository articleRepository;
    private final SafeHttpClient httpClient;
    private final HtmlSanitizer sanitizer;

    public ArticleService(ArticleRepository articleRepository,
                          SafeHttpClient httpClient,
                          HtmlSanitizer sanitizer) {
        this.articleRepository = articleRepository;
        this.httpClient = httpClient;
        this.sanitizer = sanitizer;
    }

    public Optional<Article> findById(long id) {
        return articleRepository.findById(id);
    }

    public List<Article> findPage(Long feedId, Boolean unreadOnly, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        int offset = (safePage - 1) * safeSize;
        return articleRepository.findPage(feedId, unreadOnly, safeSize, offset);
    }

    public long count(Long feedId, Boolean unreadOnly) {
        return articleRepository.count(feedId, unreadOnly);
    }

    @Transactional
    public Article markRead(long id, boolean read) {
        Article article = articleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Article not found"));
        articleRepository.markRead(id, read);
        return articleRepository.findById(id).orElse(article);
    }

    /**
     * Returns sanitized HTML for display/EPUB. Images are stripped by default.
     * Caches extracted content when Readability succeeds.
     */
    @Transactional
    public String getContentHtml(Article article, boolean includeImages) {
        String raw = resolveRawContent(article);
        return sanitizer.sanitize(raw, includeImages);
    }

    private String resolveRawContent(Article article) {
        if (article.extractedContentHtml() != null && !article.extractedContentHtml().isBlank()) {
            return article.extractedContentHtml();
        }

        String extracted = extractFromSource(article);
        if (extracted != null && !extracted.isBlank()) {
            String sanitized = sanitizer.sanitizeWithImages(extracted);
            articleRepository.updateExtractedContent(article.id(), sanitized);
            return sanitized;
        }

        if (article.feedContentHtml() != null && !article.feedContentHtml().isBlank()) {
            return article.feedContentHtml();
        }
        if (article.summaryHtml() != null && !article.summaryHtml().isBlank()) {
            return article.summaryHtml();
        }
        return "<p>No content available.</p>";
    }

    private String extractFromSource(Article article) {
        if (article.url() == null || article.url().isBlank()) {
            return null;
        }
        try {
            SafeHttpClient.FetchedContent fetched = httpClient.get(article.url());
            Readability4J readability = new Readability4J(fetched.finalUri().toString(), fetched.body());
            net.dankito.readability4j.Article parsed = readability.parse();
            if (parsed == null) {
                return null;
            }
            String content = parsed.getContent();
            if (content == null || content.isBlank()) {
                return null;
            }
            return content;
        } catch (Exception e) {
            log.debug("Extraction failed for article {}: {}", article.id(), e.getMessage());
            return null;
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
