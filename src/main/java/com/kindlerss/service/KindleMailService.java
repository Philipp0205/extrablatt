package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import com.kindlerss.domain.AppUser;
import com.kindlerss.domain.Article;
import com.kindlerss.domain.Entitlement;
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
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Builds an EPUB from an article and emails it to the account's Kindle address.
 * The {@code From} address is the shared, provider-verified sender; each user adds
 * it to their Amazon "Approved Personal Document E-mail List".
 */
@Service
public class KindleMailService {

    /** "1 September 2026" rather than "2026-09-01", since a reader reads this. */
    private static final DateTimeFormatter RESET_DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final JavaMailSender mailSender;
    private final EpubService epubService;
    private final ArticleService articleService;
    private final ArticleRepository articleRepository;
    private final UserRepository userRepository;
    private final UserSendLimitRepository sendLimitRepository;
    private final EntitlementService entitlements;
    private final AppProperties properties;

    public KindleMailService(JavaMailSender mailSender,
                             EpubService epubService,
                             ArticleService articleService,
                             ArticleRepository articleRepository,
                             UserRepository userRepository,
                             UserSendLimitRepository sendLimitRepository,
                             EntitlementService entitlements,
                             AppProperties properties) {
        this.mailSender = mailSender;
        this.epubService = epubService;
        this.articleService = articleService;
        this.articleRepository = articleRepository;
        this.userRepository = userRepository;
        this.sendLimitRepository = sendLimitRepository;
        this.entitlements = entitlements;
        this.properties = properties;
    }

    /**
     * Sends the article as an EPUB to the account's Kindle address.
     */
    public void sendToKindle(long userId, long articleId, boolean includeImages) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Account not found"));
        requireSenderConfig();
        requireVerified(user);
        String kindleEmail = requireKindleEmail(user);
        Entitlement entitlement = entitlements.forUser(userId);
        requireWithinQuota(userId, entitlement);

        Article article = articleRepository.findById(userId, articleId)
                .orElseThrow(() -> new ArticleService.NotFoundException("Article not found"));

        String html = articleService.getContentHtml(article, includeImages);
        String author = StringUtils.hasText(article.author()) ? article.author() : article.feedTitle();
        byte[] epub = epubService.createEpub(article.title(), author, html);
        String filename = documentName(article.title()) + ".epub";

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(properties.mailFrom(), "Extrablatt");
            helper.setTo(kindleEmail);
            helper.setSubject(article.title());
            helper.setText("Sent by Extrablatt", false);
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

    /**
     * A block is not an allowance, so it stays here rather than moving into
     * {@link EntitlementService}: an administrator pausing an account overrides
     * whatever that account has paid for. The number of sends it is allowed, on the
     * other hand, is a plan question, and comes from the entitlement.
     */
    private void requireWithinQuota(long userId, Entitlement entitlement) {
        var override = sendLimitRepository.findByUserId(userId);
        if (override.isPresent() && override.get().blocked(Instant.now())) {
            throw new IllegalStateException("Sending is temporarily paused for this account");
        }

        // Unpaid after the trial: there is no monthly ration to wait for. Reading
        // in the browser is not gated; sending an e-mail is.
        if (!entitlement.paid() && !entitlement.hasMonthlyCap()) {
            throw new IllegalStateException(
                    "Your free week has ended. Subscribe under Settings → Subscription "
                            + "to send articles to your Kindle. Reading in the browser is not limited.");
        }

        // An operator can still configure a small monthly ration instead of a hard
        // paywall. The message then says both ways out — subscribe, or wait.
        if (entitlement.hasMonthlyCap()) {
            long usedThisMonth = articleRepository.countSentSince(
                    userId, entitlements.startOfCurrentMonth());
            if (usedThisMonth >= entitlement.maxSendsPerMonth()) {
                throw new IllegalStateException(
                        "You have used all " + entitlement.maxSendsPerMonth()
                                + " of this month's included articles. The Supporter plan has no "
                                + "monthly limit — see Settings → Subscription. Otherwise they come "
                                + "back on " + RESET_DATE.format(entitlements.nextResetDate()) + ".");
            }
        }

        int dailyLimit = entitlement.maxSendsPerDay();
        Instant dayAgo = Instant.now().minus(1, ChronoUnit.DAYS);
        if (articleRepository.countSentSince(userId, dayAgo) >= dailyLimit) {
            throw new IllegalStateException(
                    "Daily send limit reached (" + dailyLimit + "). Try again later.");
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
