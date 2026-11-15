package com.kindlerss.service;

import com.kindlerss.config.AppProperties;
import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends account e-mails (verification and password reset) through the shared
 * outbound sender. The same SMTP provider (Resend) delivers Kindle documents.
 *
 * <p>The sign-up and confirmation mails ({@link #sendVerification},
 * {@link #sendVerificationReminder}, {@link #sendAccountExists},
 * {@link #sendWelcome}) are handed to a small background executor, because an SMTP
 * round trip is over a second and nobody should wait for it to see "check your
 * inbox". They never throw: a failure is logged and nothing else. When called
 * inside a transaction the hand-off waits for the commit, so a rolled-back
 * sign-up sends nothing. Password reset and billing mail stay synchronous.
 */
@Service
public class AccountMailService {

    private static final Logger log = LoggerFactory.getLogger(AccountMailService.class);

    private static final int MAIL_THREADS = 2;
    private static final int MAIL_QUEUE_CAPACITY = 100;

    private final JavaMailSender mailSender;
    private final AppProperties properties;
    private final Executor mailExecutor;
    /** Set only when this service created the executor, so only then does it shut it down. */
    private final ExecutorService ownedMailExecutor;

    @Autowired
    public AccountMailService(JavaMailSender mailSender, AppProperties properties) {
        this(mailSender, properties, newMailExecutor(), true);
    }

    /** For tests: background mail runs on the given executor, which the test controls. */
    AccountMailService(JavaMailSender mailSender, AppProperties properties, Executor mailExecutor) {
        this(mailSender, properties, mailExecutor, false);
    }

    private AccountMailService(JavaMailSender mailSender, AppProperties properties,
                               Executor mailExecutor, boolean owned) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.mailExecutor = mailExecutor;
        this.ownedMailExecutor = owned ? (ExecutorService) mailExecutor : null;
    }

    private static ExecutorService newMailExecutor() {
        AtomicInteger counter = new AtomicInteger();
        // A short queue and two daemon threads. When the queue is full, execute()
        // rejects the task and it is logged and dropped rather than piling up
        // behind a slow SMTP server.
        return new ThreadPoolExecutor(MAIL_THREADS, MAIL_THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAIL_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "account-mail-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** Lets mail already queued go out before the application stops, for a few seconds at most. */
    @PreDestroy
    void shutdownMailExecutor() {
        if (ownedMailExecutor == null) {
            return;
        }
        ownedMailExecutor.shutdown();
        try {
            if (!ownedMailExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ownedMailExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ownedMailExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sends in the background and never throws. Inside a transaction the send is
     * queued only once it has committed; if it rolls back, nothing is sent.
     */
    private void sendInBackground(String toEmail, String subject, String body) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(toEmail, subject, body);
                }
            });
        } else {
            submit(toEmail, subject, body);
        }
    }

    private void submit(String toEmail, String subject, String body) {
        try {
            mailExecutor.execute(() -> deliverQuietly(toEmail, subject, body));
        } catch (RuntimeException e) {
            // Rejected (queue full or shutting down), or a synchronous executor failed.
            log.warn("Account e-mail ({}) was not sent: {}", subject, e.getMessage());
        }
    }

    private void deliverQuietly(String toEmail, String subject, String body) {
        try {
            send(toEmail, subject, body);
        } catch (RuntimeException e) {
            // send() has already logged an SMTP failure; only a configuration
            // problem arrives here unlogged.
            if (e.getCause() == null) {
                log.warn("Account e-mail ({}) was not sent: {}", subject, e.getMessage());
            }
        }
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
        sendInBackground(toEmail, "Confirm your Extrablatt account", body);
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
        sendInBackground(toEmail, "Confirm your Extrablatt account", body);
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
        sendInBackground(toEmail, "You already have an Extrablatt account", body);
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
        sendInBackground(toEmail, "Welcome to Extrablatt", body);
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
