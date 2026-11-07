package com.kindlerss.security;

import com.kindlerss.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Ends a signed-in session once the account behind it has moved on: its password
 * was reset, or it was deleted or switched off. Without this a session started
 * before a password reset, perhaps by whoever made the reset necessary, kept
 * working until it expired on its own.
 *
 * <p>Remember-me cookies need no help here: their signature covers the password
 * hash, so a new password already makes every old cookie fail.
 *
 * <p>Not a {@code @Component}: Boot would register it as a servlet filter as well,
 * and it only makes sense inside the security chain, after the session's context
 * has been loaded.
 */
public class StaleSessionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(StaleSessionFilter.class);

    private final UserService userService;
    private final RememberMeServices rememberMeServices;

    public StaleSessionFilter(UserService userService, RememberMeServices rememberMeServices) {
        this.userService = userService;
        this.rememberMeServices = rememberMeServices;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof AppUserDetails details
                && userService.credentialsChanged(details.id(), details.getPassword())) {
            log.info("Ending a session for user {} whose credentials changed", details.id());
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.removeAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                session.invalidate();
            }
            // Clears the remember-me cookie, which would fail on its own anyway.
            rememberMeServices.loginFail(request, response);
        }
        filterChain.doFilter(request, response);
    }
}
