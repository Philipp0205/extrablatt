package com.kindlerss.security;

import com.kindlerss.domain.AppUser;
import com.kindlerss.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.core.userdetails.User;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LoginTelemetryListenerTest {

    @Test
    void recordsLastLoginForAppUsers() {
        UserService userService = mock(UserService.class);
        LoginTelemetryListener listener = new LoginTelemetryListener(userService);
        AppUser account = new AppUser(9L, "user@example.com", "hash", null,
                Instant.now(), null, Instant.now(), Instant.now());
        var authentication = new UsernamePasswordAuthenticationToken(
                new AppUserDetails(account), "pw", List.of());

        listener.onLogin(new AuthenticationSuccessEvent(authentication));

        verify(userService).recordLastLogin(9L);
    }

    @Test
    void rememberMeUsesTheSamePath() {
        UserService userService = mock(UserService.class);
        LoginTelemetryListener listener = new LoginTelemetryListener(userService);
        AppUser account = new AppUser(9L, "user@example.com", "hash", null,
                Instant.now(), null, Instant.now(), Instant.now());
        var authentication = new UsernamePasswordAuthenticationToken(
                new AppUserDetails(account), "pw", List.of());

        listener.onLogin(new InteractiveAuthenticationSuccessEvent(authentication, getClass()));

        verify(userService).recordLastLogin(9L);
    }

    @Test
    void ignoresNonAppPrincipals() {
        UserService userService = mock(UserService.class);
        LoginTelemetryListener listener = new LoginTelemetryListener(userService);
        var authentication = new UsernamePasswordAuthenticationToken(
                User.withUsername("other").password("pw").roles("USER").build(),
                "pw", List.of());

        listener.onLogin(new AuthenticationSuccessEvent(authentication));

        verify(userService, never()).recordLastLogin(org.mockito.ArgumentMatchers.anyLong());
    }
}
