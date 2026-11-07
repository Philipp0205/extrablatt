package com.kindlerss.config;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wraps the real mail sender and refuses messages to addresses that must never
 * receive mail. Addresses under the reserved {@code .invalid} top-level domain
 * are always refused: the staging sync rewrites every account it does not keep
 * to one, so a staging build cannot mail real readers even if it shares an SMTP
 * account with production. {@code MAIL_ALLOWED_RECIPIENT_DOMAINS} narrows it
 * further, e.g. to a test domain and {@code kindle.com} on staging.
 *
 * <p>A refused message fails like any other send, so callers report or log it
 * the way they already do. The address itself stays out of the exception.
 */
public class RecipientGuardMailSender implements JavaMailSender {

    private final JavaMailSender delegate;
    private final Set<String> allowedDomains;

    public RecipientGuardMailSender(JavaMailSender delegate, String allowedDomains) {
        this.delegate = delegate;
        this.allowedDomains = parseDomains(allowedDomains);
    }

    static Set<String> parseDomains(String value) {
        if (value == null) {
            return Set.of();
        }
        return Arrays.stream(value.split("[,\\s]+"))
                .map(d -> d.trim().toLowerCase(Locale.ROOT).replaceFirst("^@", "").replaceFirst("\\.$", ""))
                .filter(d -> !d.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Whether a message to this address may be handed to the SMTP server. */
    boolean allows(String address) {
        if (address == null) {
            return false;
        }
        String trimmed = address.trim().toLowerCase(Locale.ROOT);
        int at = trimmed.lastIndexOf('@');
        if (at < 0 || at == trimmed.length() - 1) {
            return false;
        }
        String domain = trimmed.substring(at + 1).replaceFirst("\\.$", "");
        if (domain.equals("invalid") || domain.endsWith(".invalid")) {
            return false;
        }
        if (allowedDomains.isEmpty()) {
            return true;
        }
        for (String allowed : allowedDomains) {
            if (domain.equals(allowed) || domain.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public MimeMessage createMimeMessage() {
        return delegate.createMimeMessage();
    }

    @Override
    public MimeMessage createMimeMessage(InputStream contentStream) throws MailException {
        return delegate.createMimeMessage(contentStream);
    }

    @Override
    public void send(MimeMessage... mimeMessages) throws MailException {
        for (MimeMessage message : mimeMessages) {
            List<String> recipients = new ArrayList<>();
            try {
                Address[] all = message.getAllRecipients();
                if (all != null) {
                    for (Address address : all) {
                        recipients.add(address instanceof InternetAddress internet
                                ? internet.getAddress() : address.toString());
                    }
                }
            } catch (MessagingException e) {
                throw new MailPreparationException("Could not read message recipients", e);
            }
            check(recipients);
        }
        delegate.send(mimeMessages);
    }

    @Override
    public void send(SimpleMailMessage... simpleMessages) throws MailException {
        for (SimpleMailMessage message : simpleMessages) {
            List<String> recipients = new ArrayList<>();
            for (String[] field : new String[][] {message.getTo(), message.getCc(), message.getBcc()}) {
                if (field != null) {
                    recipients.addAll(Arrays.asList(field));
                }
            }
            check(recipients);
        }
        delegate.send(simpleMessages);
    }

    private void check(List<String> recipients) {
        if (recipients.isEmpty()) {
            return;
        }
        for (String recipient : recipients) {
            if (!allows(recipient)) {
                throw new MailSendException(
                        "Recipient not allowed (.invalid address or outside MAIL_ALLOWED_RECIPIENT_DOMAINS)");
            }
        }
    }
}
