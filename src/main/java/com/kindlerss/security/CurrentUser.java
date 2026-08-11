package com.kindlerss.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Resolves the authenticated account from the security context. */
@Component
public class CurrentUser {

    /** The signed-in user's id, or throws when there is no authenticated account. */
    public long requireId() {
        return details()
                .map(AppUserDetails::id)
                .orElseThrow(() -> new IllegalStateException("No authenticated user"));
    }

    public Optional<AppUserDetails> details() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        if (auth.getPrincipal() instanceof AppUserDetails details) {
            return Optional.of(details);
        }
        return Optional.empty();
    }
}
