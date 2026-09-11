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
        // TODO Refactor email templates into extra file  
        String body = """
                Welcome to Extrablatt.

                Confirm this e-mail address to start sending articles to your Kindle:

                %s

                If you did not create this account, you can ignore this message.
                """.formatted(link);
        send(toEmail, "Confirm your Extrablatt account", body);
    }

    /**
     * Sent when someone signs up again with an address whose account was never
     * confirmed. The link is what they came for; the note about the password is
     * because a repeat sign-up deliberately leaves the first one in place, so the
     * new one they just typed is not the one that works.
     */
    public void sendVerificationReminder(String toEmail, String token) {
        String link = accountLink("/verify", token);
        String appUrl = appUrl();
        String body = """
                You signed up for Extrablatt with this address but the account was
                never confirmed, so here is a fresh link for it:

                %s

                Afterwards, log in with the password you chose when you first signed
                up. If you picked a different one just now, or no longer remember it,
                set a new password here: %s/forgot-password

                If you did not create this account, you can ignore this message.
                """.formatted(link, appUrl);
        send(toEmail, "Confirm your Extrablatt account", body);
    }

    /**
     * Sent when someone signs up again with an address that already has a confirmed
     * account. Nothing needs confirming, so the message is about getting back in —
     * and it goes out at all because the sign-up form promises an e-mail either way.
     */
    public void sendAccountExists(String toEmail) {
        String appUrl = appUrl();
        String body = """
                Someone just signed up for Extrablatt with this address, which already
                has a confirmed account. There is nothing to confirm — log in as usual:

                %s/login

                Forgotten the password? Set a new one: %s/forgot-password

                If that was not you, you can ignore this message. The sign-up changed
                nothing and your password is unchanged.
                """.formatted(appUrl, appUrl);
        send(toEmail, "You already have an Extrablatt account", body);
    }

    /**
     * Sent once an account's e-mail is confirmed — a separate, friendlier message
     * than the confirmation link itself, pointing the new user at what to do next.
     */
    public void sendWelcome(String toEmail) {
        String appUrl = appUrl();
        String body = """
                Welcome aboard — your Extrablatt account is ready.

                Two quick steps to get the most out of it:

                1. Follow some news: pick from the suggested newspapers on
                   the Feeds page, or paste a site's normal address
                   (for example bbc.com).
                2. Open Settings and add your Kindle e-mail so articles you send
                   land on your device. Amazon lists that address (it ends in
                   @kindle.com) under Manage Your Content and Devices →
                   Preferences → Personal Document Settings.

                Open Extrablatt: %s

                Thanks for trying it out.
                """.formatted(appUrl);
        send(toEmail, "Welcome to Extrablatt", body);
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
        return appUrl() + path + "?token=" + token;
    }

    private String appUrl() {
        return properties.publicUrl().replaceFirst("/+$", "");
    }

    /** Package-private so billing e-mail reuses the one sender and its error handling. */
    void send(String toEmail, String subject, String body) {
        if (!StringUtils.hasText(properties.mailFrom())) {
            throw new IllegalStateException("MAIL_FROM must be configured to send account e-mail");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(properties.mailFrom(), "Extrablatt");
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(message);
        } catch (Exception e) {
            // Surface the failure so registration/reset can report it, but keep the
            // message generic to callers to avoid leaking address existence. The
            // address stays out of the log line too: production logs at WARN, and a
            // log file is a place personal data ends up and is never cleaned out.
            log.warn("Failed to send account e-mail ({}): {}", subject, e.getMessage());
            throw new IllegalStateException("Could not send e-mail", e);
        }
    }
}
