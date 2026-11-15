package com.kindlerss.service;

import com.kindlerss.domain.AppUser;
import com.kindlerss.domain.EmailToken;
import com.kindlerss.repository.EmailTokenRepository;
import com.kindlerss.repository.UserRepository;
import com.kindlerss.security.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * "Lost your recovery code?": a one-time sign-in link sent by e-mail.
 *
 * <p>The token is 32 random bytes, base64url without padding. Only its SHA-256 hex is
 * stored, in the {@code email_tokens} table under {@link EmailToken.Purpose#SIGN_IN};
 * the plain token exists only in the mail. A link lives {@value #TTL_MINUTES} minutes,
 * works once (spent by a single {@code DELETE ... RETURNING}, so two parallel requests
 * cannot both win) and a user has at most one live link: asking again replaces it.
 * Using a link replaces the recovery code, so the one that was lost stops working.
 */
@Service
public class RecoverySignInService {

    private static final Logger log = LoggerFactory.getLogger(RecoverySignInService.class);

    static final int TTL_MINUTES = 20;
    static final Duration TTL = Duration.ofMinutes(TTL_MINUTES);
    static final int MAX_REQUESTS_PER_ADDRESS = 3;
    static final Duration REQUEST_WINDOW = Duration.ofMinutes(15);

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    /** 32 bytes in base64url without padding. */
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    /** The account that was signed in and the new recovery code, shown once. */
    public record SignIn(AppUser user, String newRecoveryCode) {
    }

    private final UserRepository users;
    private final EmailTokenRepository tokens;
    private final RecoveryCodeService recoveryCodes;
    private final AccountMailService mail;
    private final RateLimiter rateLimiter;
    private final SecureRandom random = new SecureRandom();

    public RecoverySignInService(UserRepository users,
                                 EmailTokenRepository tokens,
                                 RecoveryCodeService recoveryCodes,
                                 AccountMailService mail,
                                 RateLimiter rateLimiter) {
        this.users = users;
        this.tokens = tokens;
        this.recoveryCodes = recoveryCodes;
        this.mail = mail;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Mails a sign-in link if the address belongs to an enabled account with a
     * confirmed e-mail. Never throws and never says whether it sent anything: an
     * invalid address, an unknown one, an unconfirmed or switched-off account, the
     * per-address limit and a failure all end the same way.
     */
    @Transactional
    public void requestLink(String email) {
        try {
            String address = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
            if (!EMAIL.matcher(address).matches()) {
                return;
            }
            if (!rateLimiter.tryAcquire("signin-link|" + address, MAX_REQUESTS_PER_ADDRESS, REQUEST_WINDOW)) {
                log.info("Sign-in link request throttled");
                return;
            }
            Optional<AppUser> found = users.findByEmail(address);
            if (found.isEmpty() || !found.get().enabled() || !found.get().emailVerified()) {
                return;
            }
            AppUser user = found.get();
            String token = newToken();
            // One live link per account: a new request replaces the older ones.
            tokens.deleteForUser(user.id(), EmailToken.Purpose.SIGN_IN);
            tokens.insert(hash(token), user.id(), EmailToken.Purpose.SIGN_IN, Instant.now().plus(TTL));
            // Queued in the background, after the commit; never throws.
            mail.sendSignInLink(user.email(), token);
        } catch (RuntimeException e) {
            // No token, address or body in the log line.
            log.warn("Could not prepare a sign-in link: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Spends a link and, in the same transaction, replaces the account's recovery
     * code. Empty for anything wrong with the link or the account; a rollback leaves
     * the link unspent.
     */
    @Transactional
    public Optional<SignIn> consume(String token) {
        String candidate = token == null ? "" : token.trim();
        if (!TOKEN.matcher(candidate).matches()) {
            return Optional.empty();
        }
        OptionalLong userId = tokens.consumeSignIn(hash(candidate));
        if (userId.isEmpty()) {
            return Optional.empty();
        }
        // Whatever else was outstanding for this account is spent with it.
        tokens.deleteForUser(userId.getAsLong(), EmailToken.Purpose.SIGN_IN);
        Optional<AppUser> user = users.findById(userId.getAsLong());
        if (user.isEmpty() || !user.get().enabled() || !user.get().emailVerified()) {
            return Optional.empty();
        }
        String code = recoveryCodes.createOrRotate(user.get().id());
        return Optional.of(new SignIn(user.get(), code));
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex, as stored in the {@code token} column. */
    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
