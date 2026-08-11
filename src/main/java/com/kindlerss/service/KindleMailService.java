package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.AppUser;
import com.kindlerss.domain.Article;
import com.kindlerss.repository.ArticleRepository;
import com.kindlerss.repository.UserRepository;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Builds an EPUB from an article and emails it to the account's Kindle address.
 * The {@code From} address is the shared, provider-verified sender; each user adds
 * it to their Amazon "Approved Personal Document E-mail List".
 */
@Service
public class KindleMailService {

    private final JavaMailSender mailSender;
    private final EpubService epubService;
    private final ArticleService articleService;
    private final ArticleRepository articleRepository;
    private final UserRepository userRepository;
    private final AppProperties properties;
    private final int maxSendsPerDay;

    public KindleMailService(JavaMailSender mailSender,
                             EpubService epubService,
                             ArticleService articleService,
                             ArticleRepository articleRepository,
                             UserRepository userRepository,
                             AppProperties properties) {
        this.mailSender = mailSender;
        this.epubService = epubService;
        this.articleService = articleService;
        this.articleRepository = articleRepository;
        this.userRepository = userRepository;
        this.properties = properties;
        this.maxSendsPerDay = properties.limits().maxSendsPerDay();
    }

    public void sendToKindle(long userId, long articleId, boolean includeImages) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Account not found"));
        requireSenderConfig();
        requireVerified(user);
        String kindleEmail = requireKindleEmail(user);
        requireWithinDailyQuota(userId);

        Article article = articleRepository.findById(userId, articleId)
                .orElseThrow(() -> new ArticleService.NotFoundException("Article not found"));

        String html = articleService.getContentHtml(article, includeImages);
        String author = StringUtils.hasText(article.author()) ? article.author() : article.feedTitle();
        byte[] epub = epubService.createEpub(article.title(), author, html);
        String filename = slugify(article.title()) + ".epub";

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(properties.mailFrom());
            helper.setTo(kindleEmail);
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
        articleRepository.markRead(userId, articleId, true);
    }

    private void requireSenderConfig() {
        if (!StringUtils.hasText(properties.mailFrom())) {
            throw new IllegalStateException("Sending is not configured yet (MAIL_FROM missing)");
        }
    }

    private void requireVerified(AppUser user) {
        if (!user.emailVerified()) {
            throw new IllegalStateException("Verify your e-mail address before sending to Kindle");
        }
    }

    private String requireKindleEmail(AppUser user) {
        if (!StringUtils.hasText(user.kindleEmail())) {
            throw new IllegalStateException("Add your Kindle e-mail address in Settings first");
        }
        return user.kindleEmail();
    }

    private void requireWithinDailyQuota(long userId) {
        Instant dayAgo = Instant.now().minus(1, ChronoUnit.DAYS);
        if (articleRepository.countSentSince(userId, dayAgo) >= maxSendsPerDay) {
            throw new IllegalStateException(
                    "Daily send limit reached (" + maxSendsPerDay + "). Try again later.");
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
