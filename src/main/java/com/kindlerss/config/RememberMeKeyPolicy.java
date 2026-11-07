package com.kindlerss.config;

import java.net.URI;
import java.util.Locale;

/**
 * Refuses to start a deployment whose remember-me key is guessable. The key signs
 * the year-long login cookie, so anyone who knows it can mint a cookie for any
 * account. The built-in default and the {@code .env.example} placeholder are both
 * public, which made them exactly that.
 *
 * <p>Local development keeps working with no key at all: the check only applies
 * under the {@code production} profile, or when the public URL points anywhere
 * other than this machine.
 */
final class RememberMeKeyPolicy {

    static final int MIN_LENGTH = 32;

    private RememberMeKeyPolicy() {
    }

    static void check(String key, String publicUrl, boolean production) {
        if (!production && isLoopback(publicUrl)) {
            return;
        }
        String problem = problem(key);
        if (problem != null) {
            throw new IllegalStateException("REMEMBER_ME_KEY " + problem
                    + ". It signs login cookies, so a guessable key lets anyone sign in as any account."
                    + " Generate one with: openssl rand -base64 48");
        }
    }

    /** What is wrong with the key, or null when it is acceptable. */
    static String problem(String key) {
        if (key == null || key.isBlank()) {
            return "is not set";
        }
        if (key.toLowerCase(Locale.ROOT).contains("change-me")) {
            return "is still a placeholder value";
        }
        if (key.length() < MIN_LENGTH) {
            return "is too short (" + key.length() + " characters, at least " + MIN_LENGTH + " needed)";
        }
        return null;
    }

    static boolean isLoopback(String publicUrl) {
        if (publicUrl == null || publicUrl.isBlank()) {
            return true;
        }
        String host;
        try {
            host = URI.create(publicUrl.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        return host.equals("localhost") || host.endsWith(".localhost")
                || host.equals("::1") || host.startsWith("127.");
    }
}
