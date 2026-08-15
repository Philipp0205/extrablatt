package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Sends account e-mails (verification and password reset) through the shared
 * outbound sender. The same SMTP provider (Resend) delivers Kindle documents.
 */
@Service
public class AccountMailService {

    private static final Logger log = LoggerFactory.getLogger(AccountMailService.class);

    private final JavaMailSender mailSender;
    private final AppProperties properties;

    public AccountMailService(JavaMailSender mailSender, AppProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public void sendVerification(String toEmail, String token) {
        String link = accountLink("/verify", token);
        String body = """
                Welcome to Extrablatt.

                Confirm this e-mail address to start sending articles to your Kindle:

                %s

                If you did not create this account, you can ignore this message.
                """.formatted(link);
        send(toEmail, "Confirm your Extrablatt account", body);
    }

    public void sendPasswordReset(String toEmail, String token) {
        String link = accountLink("/reset-password", token);
        String body = """
                A password reset was requested for your Extrablatt account.

                Set a new password using the link below (valid for a short time):

                %s

                If you did not request this, you can ignore this message and your
                password will stay unchanged.
                """.formatted(link);
        send(toEmail, "Reset your Extrablatt password", body);
    }

    private String accountLink(String path, String token) {
        String baseUrl = properties.publicUrl().replaceFirst("/+$", "");
        return baseUrl + path + "?token=" + token;
    }

    private void send(String toEmail, String subject, String body) {
        if (!StringUtils.hasText(properties.mailFrom())) {
            throw new IllegalStateException("MAIL_FROM must be configured to send account e-mail");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(properties.mailFrom());
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(message);
        } catch (Exception e) {
            // Surface the failure so registration/reset can report it, but keep the
            // message generic to callers to avoid leaking address existence.
            log.warn("Failed to send account e-mail to {}: {}", toEmail, e.getMessage());
            throw new IllegalStateException("Could not send e-mail", e);
        }
    }
}
