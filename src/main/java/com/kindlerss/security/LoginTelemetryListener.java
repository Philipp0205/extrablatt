package com.kindlerss.security;

import com.kindlerss.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Persists last-login time for admin telemetry. Form login is also recorded in
 * {@code SecurityConfig}; this listener covers remember-me authentication, which
 * does not go through the form success handler. Restoring an existing session
 * does neither.
 */
@Component
public class LoginTelemetryListener {

    private static final Logger log = LoggerFactory.getLogger(LoginTelemetryListener.class);

    private final UserService userService;

    public LoginTelemetryListener(UserService userService) {
        this.userService = userService;
    }

    @EventListener
    public void onLogin(AuthenticationSuccessEvent event) {
        record(event.getAuthentication());
    }

    @EventListener
    public void onRememberMeLogin(InteractiveAuthenticationSuccessEvent event) {
        record(event.getAuthentication());
    }

    private void record(Authentication authentication) {
        if (authentication == null) {
            return;
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof AppUserDetails details)) {
            return;
        }
        try {
            userService.recordLastLogin(details.id());
        } catch (RuntimeException e) {
            log.warn("Could not record last login for user {}: {}", details.id(), e.getMessage());
        }
    }
}
