package com.kindlerss.service;

import com.kindlerss.domain.AppUser;
import com.kindlerss.domain.EmailToken;
import com.kindlerss.repository.EmailTokenRepository;
import com.kindlerss.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/** Registration, e-mail verification, password reset, and profile management. */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 200;
    private static final Duration VERIFY_TTL = Duration.ofDays(2);
    private static final Duration RESET_TTL = Duration.ofHours(1);

    private final UserRepository userRepository;
    private final EmailTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccountMailService mailService;
    private final SecureRandom random = new SecureRandom();

    public UserService(UserRepository userRepository,
                       EmailTokenRepository tokenRepository,
                       PasswordEncoder passwordEncoder,
                       AccountMailService mailService) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
    }

    public Optional<AppUser> findById(long id) {
        return userRepository.findById(id);
    }

    /**
     * Creates an account and sends a verification e-mail. To avoid revealing which
     * addresses are registered, a collision is treated as a silent success and a
     * reminder is not sent; the caller shows the same "check your inbox" message
     * regardless.
     */
    @Transactional
    public void register(String email, String password) {
        String normalized = validateEmail(email);
        validatePassword(password);
        AppUser user;
        try {
            user = userRepository.insert(normalized, passwordEncoder.encode(password));
        } catch (DuplicateKeyException duplicate) {
            log.info("Registration attempt for existing address ignored");
            return;
        }
        issueVerification(user);
    }

    private void issueVerification(AppUser user) {
        String token = newToken();
        tokenRepository.deleteForUser(user.id(), EmailToken.Purpose.VERIFY);
        tokenRepository.insert(token, user.id(), EmailToken.Purpose.VERIFY,
                Instant.now().plus(VERIFY_TTL));
        mailService.sendVerification(user.email(), token);
    }

    /** Confirms an address from a verification token. Returns true when it applied. */
    @Transactional
    public boolean verifyEmail(String token) {
        Optional<EmailToken> found = tokenRepository.find(token, EmailToken.Purpose.VERIFY);
        if (found.isEmpty() || !found.get().usable(Instant.now())) {
            return false;
        }
        userRepository.markEmailVerified(found.get().userId());
        tokenRepository.markUsed(token);
        return true;
    }

    /**
     * Starts a password reset if the address exists and is verified. Always behaves
     * the same to the caller so it cannot be used to probe for accounts.
     */
    @Transactional
    public void requestPasswordReset(String email) {
        if (email == null || !EMAIL.matcher(email.trim().toLowerCase()).matches()) {
            return;
        }
        Optional<AppUser> user = userRepository.findByEmail(email);
        if (user.isEmpty() || !user.get().enabled()) {
            return;
        }
        String token = newToken();
        tokenRepository.deleteForUser(user.get().id(), EmailToken.Purpose.RESET);
        tokenRepository.insert(token, user.get().id(), EmailToken.Purpose.RESET,
                Instant.now().plus(RESET_TTL));
        try {
            mailService.sendPasswordReset(user.get().email(), token);
        } catch (RuntimeException e) {
            log.warn("Password reset e-mail failed: {}", e.getMessage());
        }
    }

    /** Applies a new password from a reset token. Returns true when it applied. */
    @Transactional
    public boolean resetPassword(String token, String newPassword) {
        Optional<EmailToken> found = tokenRepository.find(token, EmailToken.Purpose.RESET);
        if (found.isEmpty() || !found.get().usable(Instant.now())) {
            return false;
        }
        validatePassword(newPassword);
        userRepository.updatePasswordHash(found.get().userId(), passwordEncoder.encode(newPassword));
        tokenRepository.markUsed(token);
        // A completed reset also confirms control of the mailbox.
        userRepository.markEmailVerified(found.get().userId());
        return true;
    }

    @Transactional
    public void updateKindleEmail(long userId, String kindleEmail) {
        String value = kindleEmail == null ? "" : kindleEmail.trim();
        if (value.isEmpty()) {
            userRepository.updateKindleEmail(userId, null);
            return;
        }
        if (!EMAIL.matcher(value.toLowerCase()).matches()) {
            throw new IllegalArgumentException("Enter a valid Kindle e-mail address");
        }
        userRepository.updateKindleEmail(userId, value);
    }

    @Transactional
    public void deleteAccount(long userId) {
        userRepository.deleteById(userId);
    }

    boolean tokenValidForTest(String token, EmailToken.Purpose purpose) {
        return tokenRepository.find(token, purpose).map(t -> t.usable(Instant.now())).orElse(false);
    }

    private String validateEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        if (!EMAIL.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Enter a valid e-mail address");
        }
        return normalized;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password is too long");
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
