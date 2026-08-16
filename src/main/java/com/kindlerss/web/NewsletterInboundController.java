package com.kindlerss.web;

import com.kindlerss.config.AppProperties;
import com.kindlerss.service.FeedService;
import jakarta.mail.internet.MailDateFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Receives newsletter issues forwarded by an inbound e-mail provider (Postmark,
 * Mailgun routes, a Cloudflare Worker, …) and stores them as articles of the
 * newsletter feed whose inbound address the message was sent to. Not part of the
 * authenticated app: it is guarded by a shared secret instead, since the provider
 * cannot log in.
 */
@RestController
public class NewsletterInboundController {

    private static final Logger log = LoggerFactory.getLogger(NewsletterInboundController.class);

    private static final Pattern EMAIL = Pattern.compile("([a-zA-Z0-9._%+-]+)@([a-zA-Z0-9.-]+)");

    private final FeedService feedService;
    private final AppProperties.Newsletters properties;

    public NewsletterInboundController(FeedService feedService, AppProperties properties) {
        this.feedService = feedService;
        this.properties = properties.newsletters();
    }

    @PostMapping("/inbound/newsletters")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestParam(value = "secret", required = false) String secret,
            @RequestBody(required = false) InboundEmailPayload payload) {
        if (!properties.enabled() || properties.inboundSecret() == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Newsletters are not configured on this server"));
        }
        if (!validSecret(secret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid secret"));
        }
        if (payload == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing message body"));
        }

        Optional<Long> feedId = candidateTokens(payload).stream()
                .map(feedService::findNewsletterFeedIdByToken)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
        if (feedId.isEmpty()) {
            log.info("Inbound newsletter message matched no feed (recipient(s): {})", payload.to());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "No newsletter subscription matches the recipient address"));
        }

        String guid = StringUtils.hasText(payload.messageId()) ? payload.messageId() : fallbackGuid(payload);
        String author = StringUtils.hasText(payload.fromName()) ? payload.fromName() : payload.from();
        Instant publishedAt = parseDate(payload.date());
        String html = StringUtils.hasText(payload.htmlBody())
                ? payload.htmlBody() : plainTextToHtml(payload.textBody());

        long articleId = feedService.receiveNewsletterIssue(
                feedId.get(), guid, payload.subject(), author, publishedAt, html);
        if (articleId < 0) {
            return ResponseEntity.ok(Map.of("status", "duplicate"));
        }
        return ResponseEntity.ok(Map.of("status", "stored", "articleId", articleId));
    }

    private boolean validSecret(String secret) {
        if (secret == null) {
            return false;
        }
        byte[] provided = secret.getBytes(StandardCharsets.UTF_8);
        byte[] expected = properties.inboundSecret().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(provided, expected);
    }

    /** Local parts (the token before @) of every address the message was sent to. */
    private static List<String> candidateTokens(InboundEmailPayload payload) {
        List<String> raw = new ArrayList<>();
        addIfPresent(raw, payload.originalRecipient());
        if (payload.toFull() != null) {
            payload.toFull().forEach(r -> addIfPresent(raw, r.email()));
        }
        addIfPresent(raw, payload.to());
        if (payload.ccFull() != null) {
            payload.ccFull().forEach(r -> addIfPresent(raw, r.email()));
        }
        addIfPresent(raw, payload.cc());

        List<String> tokens = new ArrayList<>();
        for (String value : raw) {
            Matcher matcher = EMAIL.matcher(value);
            while (matcher.find()) {
                tokens.add(matcher.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    private static void addIfPresent(List<String> values, String value) {
        if (StringUtils.hasText(value)) {
            values.add(value);
        }
    }

    private static String fallbackGuid(InboundEmailPayload payload) {
        return (payload.from() == null ? "" : payload.from())
                + "|" + (payload.subject() == null ? "" : payload.subject())
                + "|" + (payload.date() == null ? "" : payload.date());
    }

    private static Instant parseDate(String date) {
        if (!StringUtils.hasText(date)) {
            return Instant.now();
        }
        try {
            return new MailDateFormat().parse(date.trim()).toInstant();
        } catch (Exception e) {
            return Instant.now();
        }
    }

    private static String plainTextToHtml(String text) {
        if (!StringUtils.hasText(text)) {
            return "<p>No content available.</p>";
        }
        return "<pre>" + HtmlUtils.htmlEscape(text) + "</pre>";
    }
}
