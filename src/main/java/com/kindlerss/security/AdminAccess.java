package com.kindlerss.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Grants administrator access to the comma-separated ADMIN_EMAILS allowlist.
 * Keeping the allowlist in deployment secrets avoids a public "promote user"
 * endpoint and lets the first administrator be configured before registration.
 */
@Component
public class AdminAccess {

    private final Set<String> emails;

    public AdminAccess(@Value("${app.admin-emails:}") String configuredEmails) {
        this.emails = Arrays.stream(configuredEmails.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAdmin(String email) {
        return email != null && emails.contains(email.trim().toLowerCase());
    }
}
