package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.AppUser;
import com.kindlerss.domain.Article;
import com.kindlerss.repository.ArticleRepository;
import com.kindlerss.repository.UserRepository;
import com.kindlerss.repository.UserSendLimitRepository;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Builds an EPUB from an article and emails it to the account's Kindle address.
 * The {@code From} address is the shared, provider-verified sender; each user adds
 * it to their Amazon "Approved Personal Document E-mail List".
 */
@Service
public class KindleMailService {

    /**
     * How often, in lifetime successful sends, the "help keep the servers running"
     * donation reminder resurfaces. The app is free to use; this is a gentle, easy
     * to dismiss nudge rather than a paywall, so it repeats sparingly instead of
     * showing on every send.
     */
    static final int DONATION_REMINDER_INTERVAL = 10;

    private final JavaMailSender mailSender;
    private final EpubService epubService;
    private final ArticleService articleService;
    private final ArticleRepository articleRepository;
    private final UserRepository userRepository;
    private final UserSendLimitRepository sendLimitRepository;
    private final AppProperties properties;
    private final int maxSendsPerDay;

    public KindleMailService(JavaMailSender mailSender,
                             EpubService epubService,
                             ArticleService articleService,
                             ArticleRepository articleRepository,
                             UserRepository userRepository,
                             UserSendLimitRepository sendLimitRepository,
                             AppProperties properties) {
        this.mailSender = mailSender;
        this.epubService = epubService;
        this.articleService = articleService;
        this.articleRepository = articleRepository;
        this.userRepository = userRepository;
        this.sendLimitRepository = sendLimitRepository;
        this.properties = properties;
        this.maxSendsPerDay = properties.limits().maxSendsPerDay();
    }

    /**
     * Sends the article and returns whether the donation reminder should be shown
     * now, i.e. this delivery just completed a multiple of
     * {@link #DONATION_REMINDER_INTERVAL} lifetime sends for the account.
     */
    public boolean sendToKindle(long userId, long articleId, boolean includeImages) {
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
        String filename = documentName(article.title()) + ".epub";

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

        articleRepository.recordSend(userId, articleId, Instant.now());
        articleRepository.markRead(userId, articleId, true);

        long totalSent = articleRepository.countSentTotal(userId);
        return totalSent > 0 && totalSent % DONATION_REMINDER_INTERVAL == 0;
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
        var override = sendLimitRepository.findByUserId(userId);
        if (override.isPresent() && override.get().blocked(Instant.now())) {
            throw new IllegalStateException("Sending is temporarily paused for this account");
        }
        int effectiveLimit = override.map(limit -> limit.maxSendsPerDay() == null
                        ? maxSendsPerDay : limit.maxSendsPerDay())
                .orElse(maxSendsPerDay);
        Instant dayAgo = Instant.now().minus(1, ChronoUnit.DAYS);
        if (articleRepository.countSentSince(userId, dayAgo) >= effectiveLimit) {
            throw new IllegalStateException(
                    "Daily send limit reached (" + effectiveLimit + "). Try again later.");
        }
    }

    /**
     * The attachment name is what shows up in the Kindle library, so it keeps the
     * article's real title — words, spaces and capitals — instead of a dashed slug.
     * Only characters a file name cannot hold are dropped, and runs of whitespace
     * are collapsed so the name stays on one tidy line.
     */
    static String documentName(String title) {
        String base = title == null ? "" : title
                // Characters that are illegal in file names on common systems.
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (base.isBlank()) {
            base = "Article";
        }
        if (base.length() > 80) {
            base = base.substring(0, 80).trim();
        }
        return base;
    }
}
