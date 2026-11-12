package com.kindlerss.service;

import com.kindlerss.domain.ApiToken;
import com.kindlerss.repository.ApiTokenRepository;
import com.kindlerss.repository.UserRepository;
import com.kindlerss.security.AppUserDetails;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Personal API tokens: created by a signed-in account, shown once, and afterwards
 * known only by a SHA-256 hash. The secret is long and random, so a fast hash is
 * enough; a password hash would only slow down every API request.
 */
@Service
public class ApiTokenService {

    static final String SECRET_PREFIX = "eb_";
    static final int DISPLAY_PREFIX_LENGTH = 8;
    public static final int MAX_NAME_LENGTH = 60;

    private final ApiTokenRepository tokens;
    private final UserRepository users;
    private final SecureRandom random = new SecureRandom();

    public ApiTokenService(ApiTokenRepository tokens, UserRepository users) {
        this.tokens = tokens;
        this.users = users;
    }

    /** The new token's listing plus its secret, which is not recoverable afterwards. */
    public record Created(ApiToken token, String secret) {
    }

    public Created create(long userId, String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Give the token a name");
        }
        if (clean.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Use at most " + MAX_NAME_LENGTH + " characters for the name");
        }
        String secret = newToken();
        ApiToken token = tokens.insert(userId, clean, hashToken(secret),
                secret.substring(0, DISPLAY_PREFIX_LENGTH));
        return new Created(token, secret);
    }

    public List<ApiToken> list(long userId) {
        return tokens.findActive(userId);
    }

    public boolean revoke(long userId, long id) {
        return tokens.revoke(userId, id);
    }

    /**
     * The account a bearer token belongs to, if the token exists, is not revoked and
     * the account could sign in today: switched on and its mailbox confirmed.
     */
    public Optional<AppUserDetails> authenticate(String rawToken) {
        if (rawToken == null || !rawToken.startsWith(SECRET_PREFIX)) {
            return Optional.empty();
        }
        String hash = hashToken(rawToken);
        return tokens.findActiveByHash(hash)
                .filter(stored -> MessageDigest.isEqual(
                        stored.tokenHash().getBytes(StandardCharsets.UTF_8),
                        hash.getBytes(StandardCharsets.UTF_8)))
                .flatMap(stored -> users.findById(stored.userId())
                        .filter(user -> user.enabled() && user.emailVerified())
                        .map(user -> {
                            tokens.markUsed(stored.id());
                            return new AppUserDetails(user);
                        }));
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
