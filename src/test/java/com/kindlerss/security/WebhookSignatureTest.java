package com.kindlerss.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The webhook is the only route by which an account becomes a subscriber, so the
 * signature check is the only thing standing between an account and a free
 * upgrade. Worth testing the ways it should say no as carefully as the way it says
 * yes.
 */
class WebhookSignatureTest {

    private static final String SECRET = "whsec_test_secret";
    private static final byte[] BODY = "{\"id\":\"evt_1\",\"type\":\"invoice.paid\"}"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void acceptsAGenuineStripeSignature() {
        Instant now = Instant.now();
        String header = stripeHeader(now, BODY, SECRET);

        assertTrue(WebhookSignature.verifyStripe(header, BODY, SECRET, now));
    }

    @Test
    void acceptsAGenuinePaddleSignature() {
        Instant now = Instant.now();
        String header = paddleHeader(now, BODY, SECRET);

        assertTrue(WebhookSignature.verifyPaddle(header, BODY, SECRET, now));
    }

    /** The two schemes join the timestamp and body differently, and must not mix. */
    @Test
    void oneProvidersSignatureDoesNotPassAsTheOthers() {
        Instant now = Instant.now();

        assertFalse(WebhookSignature.verifyPaddle(stripeHeader(now, BODY, SECRET), BODY, SECRET, now));
        assertFalse(WebhookSignature.verifyStripe(paddleHeader(now, BODY, SECRET), BODY, SECRET, now));
    }

    @Test
    void rejectsAnAlteredBody() {
        Instant now = Instant.now();
        String header = stripeHeader(now, BODY, SECRET);
        byte[] tampered = "{\"id\":\"evt_1\",\"type\":\"invoice.paid\",\"x\":1}"
                .getBytes(StandardCharsets.UTF_8);

        assertFalse(WebhookSignature.verifyStripe(header, tampered, SECRET, now));
    }

    @Test
    void rejectsTheWrongSecret() {
        Instant now = Instant.now();
        String header = stripeHeader(now, BODY, "whsec_someone_elses");

        assertFalse(WebhookSignature.verifyStripe(header, BODY, SECRET, now));
    }

    /**
     * Without a freshness check, one captured "your subscription renewed" callback
     * could be replayed forever.
     */
    @Test
    void rejectsAStaleTimestampEvenWhenTheDigestIsRight() {
        Instant longAgo = Instant.now().minus(Duration.ofHours(2));
        String header = stripeHeader(longAgo, BODY, SECRET);

        assertFalse(WebhookSignature.verifyStripe(header, BODY, SECRET, Instant.now()));
    }

    @Test
    void rejectsAMissingOrMalformedHeader() {
        Instant now = Instant.now();

        assertFalse(WebhookSignature.verifyStripe(null, BODY, SECRET, now));
        assertFalse(WebhookSignature.verifyStripe("", BODY, SECRET, now));
        assertFalse(WebhookSignature.verifyStripe("nonsense", BODY, SECRET, now));
        assertFalse(WebhookSignature.verifyStripe("t=abc,v1=deadbeef", BODY, SECRET, now));
        // No secret configured must never mean "everything is valid".
        assertFalse(WebhookSignature.verifyStripe(stripeHeader(now, BODY, SECRET), BODY, null, now));
    }

    /** A header may carry several digests while a secret is being rotated. */
    @Test
    void acceptsOneValidDigestAmongSeveral() {
        Instant now = Instant.now();
        long seconds = now.getEpochSecond();
        String valid = digest(seconds + "." + new String(BODY, StandardCharsets.UTF_8), SECRET);
        String header = "t=" + seconds + ",v1=0000000000000000,v1=" + valid;

        assertTrue(WebhookSignature.verifyStripe(header, BODY, SECRET, now));
    }

    private static String stripeHeader(Instant when, byte[] body, String secret) {
        long seconds = when.getEpochSecond();
        return "t=" + seconds + ",v1="
                + digest(seconds + "." + new String(body, StandardCharsets.UTF_8), secret);
    }

    private static String paddleHeader(Instant when, byte[] body, String secret) {
        long seconds = when.getEpochSecond();
        return "ts=" + seconds + ";h1="
                + digest(seconds + ":" + new String(body, StandardCharsets.UTF_8), secret);
    }

    private static String digest(String signedPayload, String secret) {
        return WebhookSignature.hmacHex(secret, signedPayload.getBytes(StandardCharsets.UTF_8));
    }
}
