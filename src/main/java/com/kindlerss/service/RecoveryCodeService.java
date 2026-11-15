package com.kindlerss.service;

import com.kindlerss.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * One recovery code per account, in the form {@code XXXX-XXXX-XXXX}. It is shown once
 * when it is created and afterwards known only by a SHA-256 hash of the normalized
 * code. Creating a code on an account that has one replaces it, so the old one stops
 * working. The code is random and long enough that a fast hash is enough.
 */
@Service
public class RecoveryCodeService {

    /** No I, O, 0 or 1: they are too easy to misread when a code is copied by hand. */
    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int GROUPS = 3;
    static final int GROUP_LENGTH = 4;

    private final UserRepository users;
    private final SecureRandom random = new SecureRandom();

    public RecoveryCodeService(UserRepository users) {
        this.users = users;
    }

    /** Creates the account's code, or replaces it; the plain code is returned only here. */
    public String createOrRotate(long userId) {
        String code = newCode();
        users.setRecoveryHash(userId, hash(code));
        return code;
    }

    public boolean hasCode(long userId) {
        return users.findRecoveryHash(userId).isPresent();
    }

    String newCode() {
        StringBuilder code = new StringBuilder(GROUPS * GROUP_LENGTH + GROUPS - 1);
        for (int group = 0; group < GROUPS; group++) {
            if (group > 0) {
                code.append('-');
            }
            for (int i = 0; i < GROUP_LENGTH; i++) {
                code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
        }
        return code.toString();
    }

    /**
     * What a person typed, as a code: anything that is not a letter or digit is dropped
     * (spaces, dashes), letters are upper-cased, and the result is regrouped. Empty when
     * it is not a valid code.
     */
    public static Optional<String> normalize(String input) {
        if (input == null) {
            return Optional.empty();
        }
        StringBuilder chars = new StringBuilder();
        for (char c : input.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
                chars.append(c);
            }
        }
        if (chars.length() != GROUPS * GROUP_LENGTH) {
            return Optional.empty();
        }
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < chars.length(); i++) {
            if (ALPHABET.indexOf(chars.charAt(i)) < 0) {
                return Optional.empty();
            }
            if (i > 0 && i % GROUP_LENGTH == 0) {
                code.append('-');
            }
            code.append(chars.charAt(i));
        }
        return Optional.of(code.toString());
    }

    /** SHA-256 hex of the normalized code; throws for something that is not a code. */
    public static String hash(String code) {
        String normalized = normalize(code)
                .orElseThrow(() -> new IllegalArgumentException("Not a recovery code"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
