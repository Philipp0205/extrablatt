package com.kindlerss.service;

import com.kindlerss.domain.AppUser;
import com.kindlerss.domain.EmailToken;
import com.kindlerss.repository.EmailTokenRepository;
import com.kindlerss.repository.UserRepository;
import com.kindlerss.security.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private UserRepository userRepository;
    private EmailTokenRepository tokenRepository;
    private PasswordEncoder passwordEncoder;
    private AccountMailService mailService;
    private SubscriptionService subscriptionService;
    private UserService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        tokenRepository = mock(EmailTokenRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        mailService = mock(AccountMailService.class);
        subscriptionService = mock(SubscriptionService.class);
        service = new UserService(userRepository, tokenRepository, passwordEncoder, mailService,
                subscriptionService, new RateLimiter());
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
    }

    /** An account whose address has not been confirmed yet. */
    private AppUser user(long id, String email) {
        return new AppUser(id, email, "hashed", null, null, null, Instant.now(), Instant.now());
    }

    private AppUser verified(long id, String email) {
        return new AppUser(id, email, "hashed", null, Instant.now(), null, Instant.now(), Instant.now());
    }

    @Test
    void registrationCreatesAccountAndSendsVerification() {
        when(userRepository.insert(eq("new@example.com"), eq("hashed")))
                .thenReturn(user(1L, "new@example.com"));

        service.register("New@Example.com", "supersecret");

        verify(userRepository).insert("new@example.com", "hashed");
        verify(subscriptionService).startTrial(1L);
        verify(tokenRepository).insert(anyString(), eq(1L), eq(EmailToken.Purpose.VERIFY), any());
        verify(mailService).sendVerification(eq("new@example.com"), anyString());
    }

    @Test
    void registrationMarksTheCurrentReleaseSeenSoTheNoticeWaitsForTheNextOne() {
        when(userRepository.insert(anyString(), anyString())).thenReturn(user(1L, "new@example.com"));

        service.register("new@example.com", "supersecret");

        verify(userRepository).updateLastSeenChangelogId(1L,
                ChangelogCatalog.instance().latestId().orElseThrow());
    }

    @Test
    void signingUpAgainWithAnUnconfirmedAddressResendsTheConfirmationLink() {
        when(userRepository.findByEmail("taken@example.com"))
                .thenReturn(Optional.of(user(7L, "taken@example.com")));

        service.register("Taken@Example.com", "supersecret");

        verify(tokenRepository).deleteForUser(7L, EmailToken.Purpose.VERIFY);
        verify(tokenRepository).insert(anyString(), eq(7L), eq(EmailToken.Purpose.VERIFY), any());
        verify(mailService).sendVerificationReminder(eq("taken@example.com"), anyString());
        // The account is untouched: no second account, no restarted trial, and the
        // password stays the one the first sign-up chose.
        verify(userRepository, never()).insert(anyString(), anyString());
        verify(userRepository, never()).updatePasswordHash(anyLong(), anyString());
        verify(subscriptionService, never()).startTrial(anyLong());
    }

    @Test
    void signingUpAgainWithAConfirmedAddressSaysSoInsteadOfSendingALink() {
        when(userRepository.findByEmail("taken@example.com"))
                .thenReturn(Optional.of(verified(7L, "taken@example.com")));

        service.register("taken@example.com", "supersecret");

        verify(mailService).sendAccountExists("taken@example.com");
        verify(mailService, never()).sendVerificationReminder(anyString(), anyString());
        verify(tokenRepository, never()).insert(anyString(), anyLong(), anyPurpose(), any());
        verify(userRepository, never()).insert(anyString(), anyString());
    }

    @Test
    void repeatSignUpsForOneAddressStopMailingItAfterAFewTries() {
        when(userRepository.findByEmail("taken@example.com"))
                .thenReturn(Optional.of(verified(7L, "taken@example.com")));

        for (int attempt = 0; attempt < 10; attempt++) {
            service.register("taken@example.com", "supersecret");
        }

        verify(mailService, times(3)).sendAccountExists("taken@example.com");
    }

    @Test
    void repeatSignUpForADisabledAccountSendsNothing() {
        AppUser disabled = new AppUser(7L, "blocked@example.com", "hashed", null,
                Instant.now(), Instant.now(), Instant.now(), Instant.now());
        when(userRepository.findByEmail("blocked@example.com")).thenReturn(Optional.of(disabled));

        service.register("blocked@example.com", "supersecret");

        verify(mailService, never()).sendAccountExists(anyString());
        verify(mailService, never()).sendVerificationReminder(anyString(), anyString());
    }

    @Test
    void aFailedReminderStillLooksLikeASuccessfulSignUp() {
        when(userRepository.findByEmail("taken@example.com"))
                .thenReturn(Optional.of(verified(7L, "taken@example.com")));
        org.mockito.Mockito.doThrow(new IllegalStateException("Could not send e-mail"))
                .when(mailService).sendAccountExists(anyString());

        // Reporting the failure would tell the browser the address is taken.
        assertDoesNotThrow(() -> service.register("taken@example.com", "supersecret"));
    }

    @Test
    void losingTheRaceToACompetingSignUpIsSilent() {
        when(userRepository.insert(anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("exists"));

        service.register("taken@example.com", "supersecret");

        verify(mailService, never()).sendVerification(anyString(), anyString());
        verify(subscriptionService, never()).startTrial(anyLong());
        verify(userRepository, never()).updateLastSeenChangelogId(anyLong(), anyString());
    }

    @Test
    void registrationRejectsInvalidEmailAndShortPassword() {
        assertThrows(IllegalArgumentException.class, () -> service.register("not-an-email", "supersecret"));
        assertThrows(IllegalArgumentException.class, () -> service.register("ok@example.com", "short"));
    }

    @Test
    void verificationAppliesAUsableTokenAndSendsWelcome() {
        EmailToken token = new EmailToken("tok", 1L, EmailToken.Purpose.VERIFY,
                Instant.now().plus(1, ChronoUnit.DAYS), null, Instant.now());
        when(tokenRepository.find("tok", EmailToken.Purpose.VERIFY)).thenReturn(Optional.of(token));
        when(userRepository.markEmailVerified(1L)).thenReturn(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "new@example.com")));

        assertTrue(service.verifyEmail("tok"));
        verify(userRepository).markEmailVerified(1L);
        verify(tokenRepository).markUsed("tok");
        verify(mailService).sendWelcome("new@example.com");
    }

    @Test
    void verificationOfAnAlreadyVerifiedAccountDoesNotResendWelcome() {
        EmailToken token = new EmailToken("tok", 1L, EmailToken.Purpose.VERIFY,
                Instant.now().plus(1, ChronoUnit.DAYS), null, Instant.now());
        when(tokenRepository.find("tok", EmailToken.Purpose.VERIFY)).thenReturn(Optional.of(token));
        when(userRepository.markEmailVerified(1L)).thenReturn(false);

        assertTrue(service.verifyEmail("tok"));
        verify(mailService, never()).sendWelcome(anyString());
    }

    @Test
    void verificationRejectsAnExpiredToken() {
        EmailToken token = new EmailToken("old", 1L, EmailToken.Purpose.VERIFY,
                Instant.now().minus(1, ChronoUnit.DAYS), null, Instant.now());
        when(tokenRepository.find("old", EmailToken.Purpose.VERIFY)).thenReturn(Optional.of(token));

        assertFalse(service.verifyEmail("old"));
        verify(userRepository, never()).markEmailVerified(anyLong());
    }

    @Test
    void passwordResetUpdatesHashAndConsumesToken() {
        EmailToken token = new EmailToken("rst", 2L, EmailToken.Purpose.RESET,
                Instant.now().plus(1, ChronoUnit.HOURS), null, Instant.now());
        when(tokenRepository.find("rst", EmailToken.Purpose.RESET)).thenReturn(Optional.of(token));

        assertTrue(service.resetPassword("rst", "brandnewpass"));
        verify(userRepository).updatePasswordHash(2L, "hashed");
        verify(tokenRepository).markUsed("rst");
    }

    @Test
    void markReadOnNextPageDefaultsToOnWhenTheAccountIsMissing() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertTrue(service.markReadOnNextPage(99L));
    }

    @Test
    void updatingMarkReadOnNextPageWritesThrough() {
        service.updateMarkReadOnNextPage(1L, false);
        verify(userRepository).updateMarkReadOnNextPage(1L, false);
    }

    @Test
    void acknowledgingTheChangelogWritesTheReleaseId() {
        service.acknowledgeChangelog(1L, "2026-08-31");
        verify(userRepository).updateLastSeenChangelogId(1L, "2026-08-31");
    }

    @Test
    void acknowledgingABlankChangelogIdDoesNothing() {
        service.acknowledgeChangelog(1L, "  ");
        verify(userRepository, never()).updateLastSeenChangelogId(anyLong(), anyString());
    }

    @Test
    void recordLastLoginWritesTheTimestamp() {
        service.recordLastLogin(1L);
        verify(userRepository).updateLastLoginAt(eq(1L), org.mockito.ArgumentMatchers.any(Instant.class));
    }

    private static Instant any() {
        return org.mockito.ArgumentMatchers.any(Instant.class);
    }

    private static EmailToken.Purpose anyPurpose() {
        return org.mockito.ArgumentMatchers.any(EmailToken.Purpose.class);
    }
}
