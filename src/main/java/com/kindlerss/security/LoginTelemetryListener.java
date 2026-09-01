package com.kindlerss.security;

import com.kindlerss.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * Persists last-login time for admin telemetry. Form login publishes
 * {@link AuthenticationSuccessEvent}; remember-me publishes the interactive
 * subclass. Restoring an existing session does neither.
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
        Object principal = event.getAuthentication().getPrincipal();
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
