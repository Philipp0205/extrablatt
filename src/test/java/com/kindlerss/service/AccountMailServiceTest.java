package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sign-up and confirmation mails leave the request thread. The tests drive the
 * hand-off with an executor they control, so nothing here depends on timing.
 */
class AccountMailServiceTest {

    /** Holds submitted work until the test runs it, standing in for the background thread. */
    private static final class QueueingExecutor implements Executor {
        final List<Runnable> queued = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }

        void runAll() {
            List<Runnable> work = new ArrayList<>(queued);
            queued.clear();
            work.forEach(Runnable::run);
        }
    }

    private JavaMailSender mailSender;
    private QueueingExecutor executor;
    private AccountMailService service;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage())
                .thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
        executor = new QueueingExecutor();
        service = new AccountMailService(mailSender, properties("noreply@example.com"), executor);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static AppProperties properties(String mailFrom) {
        return new AppProperties(mailFrom, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void theSignUpAndConfirmationMailsReturnBeforeAnythingIsSent() {
        service.sendVerification("a@example.com", "tok");
        service.sendVerificationReminder("a@example.com", "tok");
        service.sendAccountExists("a@example.com");
        service.sendWelcome("a@example.com");

        verify(mailSender, never()).send(any(MimeMessage.class));
        assertEquals(4, executor.queued.size());

        executor.runAll();

        verify(mailSender, org.mockito.Mockito.times(4)).send(any(MimeMessage.class));
    }

    @Test
    void theSignInLinkIsMultipartWithTheFragmentLinkAndNoImages() throws Exception {
        service.sendSignInLink("a@example.com", "tok_123-abc");
        executor.runAll();

        org.mockito.ArgumentCaptor<MimeMessage> sent = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        sent.getValue().saveChanges();
        assertEquals("Your Extrablatt sign-in link", sent.getValue().getSubject());
        assertEquals("a@example.com", sent.getValue().getAllRecipients()[0].toString());
        assertTrue(sent.getValue().isMimeType("multipart/*"));

        String all = allText(sent.getValue());
        String link = "http://localhost:8080/recover#t=tok_123-abc";
        assertTrue(all.contains(link), all);
        assertTrue(all.contains("<a href=\"" + link + "\">" + link + "</a>"), all);
        assertTrue(all.contains("Sign-in code (only if the link does not work)"), all);
        assertTrue(all.contains("valid for 20 minutes"), all);
        assertTrue(all.contains("old recovery code"), all);
        assertTrue(all.contains("If you did not ask for this, ignore this email."), all);
        assertFalse(all.toLowerCase().contains("<img"), all);
        assertFalse(all.contains("style="), all);
    }

    @Test
    void theSignInLinkIsQueuedInTheBackgroundAndAFailureIsNotThrown() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));

        assertDoesNotThrow(() -> service.sendSignInLink("a@example.com", "secret-token"));
        verify(mailSender, never()).send(any(MimeMessage.class));
        assertDoesNotThrow(executor::runAll);
    }

    private static String allText(jakarta.mail.Part part) throws Exception {
        if (part.isMimeType("multipart/*")) {
            jakarta.mail.Multipart multipart = (jakarta.mail.Multipart) part.getContent();
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                text.append(allText(multipart.getBodyPart(i))).append('\n');
            }
            return text.toString();
        }
        return part.getContent().toString();
    }

    @Test
    void theQueuedVerificationMessageCarriesTheLink() throws Exception {
        service.sendVerification("a@example.com", "tok-123");
        executor.runAll();

        org.mockito.ArgumentCaptor<MimeMessage> sent = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        sent.getValue().saveChanges();
        assertEquals("Confirm your Extrablatt account", sent.getValue().getSubject());
        assertEquals("a@example.com", sent.getValue().getAllRecipients()[0].toString());
        assertTrue(sent.getValue().getContent().toString().contains("/verify?token=tok-123"));
    }

    @Test
    void anSmtpFailureInTheBackgroundIsLoggedNotThrown() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));

        assertDoesNotThrow(() -> service.sendVerification("a@example.com", "tok"));
        assertDoesNotThrow(executor::runAll);
    }

    @Test
    void aMissingSenderAddressIsLoggedNotThrownFromTheBackgroundMails() {
        AccountMailService unconfigured = new AccountMailService(mailSender, properties(" "), executor);

        assertDoesNotThrow(() -> unconfigured.sendWelcome("a@example.com"));
        assertDoesNotThrow(executor::runAll);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void aFullQueueDropsTheMailInsteadOfFailingTheCaller() {
        Executor rejecting = command -> {
            throw new java.util.concurrent.RejectedExecutionException("full");
        };
        AccountMailService saturated = new AccountMailService(mailSender, properties("noreply@example.com"), rejecting);

        assertDoesNotThrow(() -> saturated.sendVerification("a@example.com", "tok"));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void insideATransactionTheMailIsQueuedOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        service.sendVerification("a@example.com", "tok");

        assertTrue(executor.queued.isEmpty(), "nothing is queued while the transaction is open");
        List<TransactionSynchronization> callbacks = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, callbacks.size());

        callbacks.forEach(TransactionSynchronization::afterCommit);
        assertEquals(1, executor.queued.size());
        executor.runAll();
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void aRolledBackTransactionSendsNothing() {
        TransactionSynchronizationManager.initSynchronization();

        service.sendVerification("a@example.com", "tok");
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        executor.runAll();
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void passwordResetStaysSynchronousAndReportsFailure() {
        service.sendPasswordReset("a@example.com", "tok");
        verify(mailSender).send(any(MimeMessage.class));
        assertTrue(executor.queued.isEmpty());

        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.sendPasswordReset("a@example.com", "tok"));
    }
}
