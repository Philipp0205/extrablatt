package com.kindlerss.service;

import com.kindlerss.domain.AppUser;
import com.kindlerss.domain.EmailToken;
import com.kindlerss.repository.EmailTokenRepository;
import com.kindlerss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private UserRepository userRepository;
    private EmailTokenRepository tokenRepository;
    private PasswordEncoder passwordEncoder;
    private AccountMailService mailService;
    private UserService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        tokenRepository = mock(EmailTokenRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        mailService = mock(AccountMailService.class);
        service = new UserService(userRepository, tokenRepository, passwordEncoder, mailService);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
    }

    private AppUser user(long id, String email) {
        return new AppUser(id, email, "hashed", null, null, null, Instant.now(), Instant.now());
    }

    @Test
    void registrationCreatesAccountAndSendsVerification() {
        when(userRepository.insert(eq("new@example.com"), eq("hashed")))
                .thenReturn(user(1L, "new@example.com"));

        service.register("New@Example.com", "supersecret");

        verify(userRepository).insert("new@example.com", "hashed");
        verify(tokenRepository).insert(anyString(), eq(1L), eq(EmailToken.Purpose.VERIFY), any());
        verify(mailService).sendVerification(eq("new@example.com"), anyString());
    }

    @Test
    void registrationForExistingEmailIsSilent() {
        when(userRepository.insert(anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("exists"));

        service.register("taken@example.com", "supersecret");

        verify(mailService, never()).sendVerification(anyString(), anyString());
    }

    @Test
    void registrationRejectsInvalidEmailAndShortPassword() {
        assertThrows(IllegalArgumentException.class, () -> service.register("not-an-email", "supersecret"));
        assertThrows(IllegalArgumentException.class, () -> service.register("ok@example.com", "short"));
    }

    @Test
    void verificationAppliesAUsableToken() {
        EmailToken token = new EmailToken("tok", 1L, EmailToken.Purpose.VERIFY,
                Instant.now().plus(1, ChronoUnit.DAYS), null, Instant.now());
        when(tokenRepository.find("tok", EmailToken.Purpose.VERIFY)).thenReturn(Optional.of(token));

        assertTrue(service.verifyEmail("tok"));
        verify(userRepository).markEmailVerified(1L);
        verify(tokenRepository).markUsed("tok");
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

    private static Instant any() {
        return org.mockito.ArgumentMatchers.any(Instant.class);
    }
}
