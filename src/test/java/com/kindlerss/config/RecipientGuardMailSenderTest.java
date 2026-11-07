package com.kindlerss.config;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mock.env.MockEnvironment;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Staging rewrites every account it does not keep to an .invalid address, and
 * this guard is what makes sure those never reach an SMTP server.
 */
class RecipientGuardMailSenderTest {

    private final JavaMailSender delegate = mock(JavaMailSender.class);

    @Test
    void invalidAddressesAreAlwaysRefused() {
        RecipientGuardMailSender guard = new RecipientGuardMailSender(delegate, "");

        assertFalse(guard.allows("user-1@staging.invalid"));
        assertFalse(guard.allows("KINDLE-1@Staging.Invalid"));
        assertFalse(guard.allows("someone@invalid"));
        assertFalse(guard.allows("someone@staging.invalid."));
        assertFalse(guard.allows("no-at-sign"));
        assertTrue(guard.allows("reader@example.com"));
        assertTrue(guard.allows("someone@invalid.example.com"));
    }

    @Test
    void anAllowListLimitsRecipientsToThoseDomains() {
        RecipientGuardMailSender guard = new RecipientGuardMailSender(delegate, " kindle.com, @Example.org ");

        assertTrue(guard.allows("reader@kindle.com"));
        assertTrue(guard.allows("reader@free.kindle.com"));
        assertTrue(guard.allows("tester@EXAMPLE.org"));
        assertFalse(guard.allows("reader@gmail.com"));
        assertFalse(guard.allows("reader@notkindle.com"));
        assertFalse(guard.allows("user-1@kindle.com.invalid"));
    }

    @Test
    void aMimeMessageToAnInvalidAddressIsNotSent() throws Exception {
        RecipientGuardMailSender guard = new RecipientGuardMailSender(delegate, "");
        MimeMessage message = message("user-7@staging.invalid");

        MailSendException refused = assertThrows(MailSendException.class, () -> guard.send(message));

        assertFalse(refused.getMessage().contains("user-7"), "the address stays out of the error");
        verify(delegate, never()).send(any(MimeMessage[].class));
    }

    @Test
    void anyRefusedRecipientStopsTheWholeMessage() throws Exception {
        RecipientGuardMailSender guard = new RecipientGuardMailSender(delegate, "");
        MimeMessage message = message("reader@example.com");
        message.addRecipients(MimeMessage.RecipientType.BCC, "user-2@staging.invalid");

        assertThrows(MailSendException.class, () -> guard.send(message));
        verify(delegate, never()).send(any(MimeMessage[].class));
    }

    @Test
    void allowedMessagesGoThrough() throws Exception {
        RecipientGuardMailSender guard = new RecipientGuardMailSender(delegate, "");
        MimeMessage message = message("reader@example.com");

        guard.send(message);

        verify(delegate).send(new MimeMessage[] {message});
    }

    @Test
    void simpleMessagesAreCheckedToo() {
        RecipientGuardMailSender guard = new RecipientGuardMailSender(delegate, "");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo("reader@example.com");
        message.setCc("user-3@staging.invalid");

        assertThrows(MailSendException.class, () -> guard.send(message));
        verify(delegate, never()).send(any(SimpleMailMessage[].class));
    }

    @Test
    void thePostProcessorWrapsEveryMailSenderOnce() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.mail-allowed-recipient-domains", "kindle.com");
        RecipientGuardPostProcessor processor = new RecipientGuardPostProcessor(environment);

        Object wrapped = processor.postProcessAfterInitialization(delegate, "mailSender");

        assertTrue(wrapped instanceof RecipientGuardMailSender);
        assertFalse(((RecipientGuardMailSender) wrapped).allows("reader@gmail.com"));
        assertSame(wrapped, processor.postProcessAfterInitialization(wrapped, "mailSender"));
        Object other = new Object();
        assertSame(other, processor.postProcessAfterInitialization(other, "other"));
    }

    private MimeMessage message(String to) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(delegate.createMimeMessage()).thenReturn(message);
        MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
        helper.setFrom("mail@extrablatt.app");
        helper.setTo(to);
        helper.setSubject("Hello");
        helper.setText("Body", false);
        return message;
    }
}
