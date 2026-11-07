package com.kindlerss.security;

import com.kindlerss.domain.AppUser;
import com.kindlerss.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;

import jakarta.servlet.http.Cookie;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StaleSessionFilterTest {

    private static final String KEY = "a-remember-me-key-that-is-long-enough-123";

    private final UserService userService = mock(UserService.class);
    private final UserDetailsService userDetailsService = mock(UserDetailsService.class);
    private final TokenBasedRememberMeServices rememberMe =
            new TokenBasedRememberMeServices(KEY, userDetailsService);
    private final StaleSessionFilter filter = new StaleSessionFilter(userService, rememberMe);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static AppUserDetails details(String hash) {
        return new AppUserDetails(new AppUser(7L, "reader@example.com", hash, null,
                Instant.now(), null, Instant.now(), Instant.now()));
    }

    private static void signIn(AppUserDetails details) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
    }

    @Test
    void aSessionFromBeforeAPasswordResetIsEnded() throws Exception {
        AppUserDetails old = details("old-hash");
        signIn(old);
        when(userService.credentialsChanged(7L, "old-hash")).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(session.isInvalid()).isTrue();
        Cookie cleared = response.getCookie("remember-me");
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
    }

    @Test
    void aCurrentSessionIsLeftAlone() throws Exception {
        signIn(details("same-hash"));
        when(userService.credentialsChanged(7L, "same-hash")).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void aRememberMeCookieIssuedBeforeAPasswordChangeNoLongerSignsIn() {
        AppUserDetails before = details(
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("old-password"));
        when(userDetailsService.loadUserByUsername("reader@example.com")).thenReturn(before);

        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/login");
        login.setParameter("remember-me", "on");
        MockHttpServletResponse issued = new MockHttpServletResponse();
        rememberMe.setParameter("remember-me");
        rememberMe.loginSuccess(login, issued,
                UsernamePasswordAuthenticationToken.authenticated(before, null, before.getAuthorities()));
        Cookie cookie = issued.getCookie("remember-me");
        assertThat(cookie).isNotNull();

        // Still the same password: the cookie signs in.
        MockHttpServletRequest sameRequest = new MockHttpServletRequest("GET", "/");
        sameRequest.setCookies(cookie);
        assertThat(rememberMe.autoLogin(sameRequest, new MockHttpServletResponse()))
                .isInstanceOf(RememberMeAuthenticationToken.class);

        // After a reset the signature no longer matches, so the cookie is refused.
        AppUserDetails after = details(
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("new-password"));
        when(userDetailsService.loadUserByUsername("reader@example.com")).thenReturn(after);
        MockHttpServletRequest staleRequest = new MockHttpServletRequest("GET", "/");
        staleRequest.setCookies(cookie);
        assertThat(rememberMe.autoLogin(staleRequest, new MockHttpServletResponse())).isNull();
    }
}
