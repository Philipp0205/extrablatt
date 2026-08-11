package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.Article;
import com.kindlerss.repository.ArticleRepository;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Locale;

/** Builds an EPUB from an article and emails it to the configured Kindle address. */
@Service
public class KindleMailService {

    private final JavaMailSender mailSender;
    private final EpubService epubService;
    private final ArticleService articleService;
    private final ArticleRepository articleRepository;
    private final AppProperties properties;

    public KindleMailService(JavaMailSender mailSender,
                             EpubService epubService,
                             ArticleService articleService,
                             ArticleRepository articleRepository,
                             AppProperties properties) {
        this.mailSender = mailSender;
        this.epubService = epubService;
        this.articleService = articleService;
        this.articleRepository = articleRepository;
        this.properties = properties;
    }

    public void sendToKindle(long articleId, boolean includeImages) {
        requireMailConfig();
        Article article = articleRepository.findById(articleId)
                .orElseThrow(() -> new ArticleService.NotFoundException("Article not found"));

        String html = articleService.getContentHtml(article, includeImages);
        String author = StringUtils.hasText(article.author()) ? article.author() : article.feedTitle();
        byte[] epub = epubService.createEpub(article.title(), author, html);
        String filename = slugify(article.title()) + ".epub";

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(properties.mailFrom());
            helper.setTo(properties.kindleEmail());
            helper.setSubject(article.title());
            helper.setText("Sent by Kindle RSS", false);
            helper.addAttachment(filename, new ByteArrayResource(epub) {
                @Override
                public String getFilename() {
                    return filename;
                }
            }, "application/epub+zip");
            mailSender.send(message);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to send EPUB to Kindle: " + e.getMessage(), e);
        }

        articleRepository.markSent(articleId, Instant.now());
        articleRepository.markRead(articleId, true);
    }

    private void requireMailConfig() {
        if (!StringUtils.hasText(properties.kindleEmail()) || !StringUtils.hasText(properties.mailFrom())) {
            throw new IllegalStateException("KINDLE_EMAIL and MAIL_FROM must be configured");
        }
    }

    static String slugify(String title) {
        String base = title == null ? "article" : title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.isBlank()) {
            base = "article";
        }
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        return base;
    }
}
