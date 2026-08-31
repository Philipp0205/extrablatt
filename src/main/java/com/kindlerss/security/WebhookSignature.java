package com.kindlerss.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Authenticates a payment provider's callback. A webhook cannot carry a session or
 * a CSRF token, and a shared secret in the URL would be enough for anyone who ever
 * saw a server log, so both providers sign the request body with HMAC-SHA256 over
 * a timestamp and the raw bytes.
 *
 * <p>The timestamp is checked as well as the digest. Without it a valid callback
 * captured once could be replayed forever, and "your subscription was renewed" is
 * exactly the message an attacker would want to repeat.
 *
 * <p>Both schemes are the same idea with different punctuation, which is why they
 * share one implementation:
 * <ul>
 *   <li>Stripe — {@code Stripe-Signature: t=<unix>,v1=<hex>} over {@code t + "." + body}</li>
 *   <li>Paddle — {@code Paddle-Signature: ts=<unix>;h1=<hex>} over {@code ts + ":" + body}</li>
 * </ul>
 */
public final class WebhookSignature {

    /** How far out of date a callback may be. Stripe's own recommendation. */
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    private WebhookSignature() {
    }

    public static boolean verifyStripe(String header, byte[] body, String secret, Instant now) {
        return verify(header, body, secret, now, ",", "t", "v1", '.');
    }

    public static boolean verifyPaddle(String header, byte[] body, String secret, Instant now) {
        return verify(header, body, secret, now, ";", "ts", "h1", ':');
    }

    private static boolean verify(String header, byte[] body, String secret, Instant now,
                                  String pairSeparator, String timestampKey, String digestKey,
                                  char joiner) {
        if (header == null || header.isBlank() || secret == null || secret.isBlank() || body == null) {
            return false;
        }
        List<String> timestamps = values(header, pairSeparator, timestampKey);
        if (timestamps.size() != 1 || !timestampFresh(timestamps.get(0), now)) {
            return false;
        }
        String expected = hmacHex(secret, concat(timestamps.get(0), joiner, body));
        // A header can carry more than one digest while a signing secret is being
        // rotated, so every candidate is tried rather than only the first.
        return values(header, pairSeparator, digestKey).stream()
                .anyMatch(candidate -> constantTimeEquals(expected, candidate));
    }

    private static List<String> values(String header, String pairSeparator, String key) {
        List<String> found = new ArrayList<>(2);
        for (String pair : header.split(pairSeparator)) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                continue;
            }
            if (key.equals(pair.substring(0, equals).trim())) {
                found.add(pair.substring(equals + 1).trim());
            }
        }
        return found;
    }

    private static boolean timestampFresh(String timestamp, Instant now) {
        try {
            Instant sent = Instant.ofEpochSecond(Long.parseLong(timestamp));
            Duration difference = Duration.between(sent, now).abs();
            return difference.compareTo(DEFAULT_TOLERANCE) <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static byte[] concat(String timestamp, char joiner, byte[] body) {
        byte[] prefix = (timestamp + joiner).getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, combined, 0, prefix.length);
        System.arraycopy(body, 0, combined, prefix.length, body.length);
        return combined;
    }

    static String hmacHex(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (Exception e) {
            throw new IllegalStateException("Could not compute webhook signature", e);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
