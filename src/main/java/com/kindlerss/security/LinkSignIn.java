package com.kindlerss.security;

import com.kindlerss.domain.AppUser;
import com.kindlerss.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Signs an account in after it proved control of its mailbox with a one-time link.
 * It does what form login and the recovery-code login do: a fresh authenticated
 * token for an {@link AppUserDetails} built as {@link DatabaseUserDetailsService}
 * builds it, saved in the HTTP session, with a new session id and a rotated CSRF
 * token, and the login time recorded. No remember-me cookie is issued.
 */
@Component
public class LinkSignIn {

    private static final Logger log = LoggerFactory.getLogger(LinkSignIn.class);

    private final AdminAccess adminAccess;
    private final UserService userService;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
    private final SessionAuthenticationStrategy sessionStrategy = new CompositeSessionAuthenticationStrategy(List.of(
            new ChangeSessionIdAuthenticationStrategy(),
            new CsrfAuthenticationStrategy(new HttpSessionCsrfTokenRepository())));

    public LinkSignIn(AdminAccess adminAccess, UserService userService) {
        this.adminAccess = adminAccess;
        this.userService = userService;
    }

    public void signIn(AppUser user, HttpServletRequest request, HttpServletResponse response) {
        AppUserDetails details = new AppUserDetails(user, adminAccess.isAdmin(user.email()));
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                details, null, details.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        // A session that existed before (an anonymous visit) gets a new id; the CSRF
        // token is replaced so a token seen while signed out is of no use afterwards.
        sessionStrategy.onAuthentication(authentication, request, response);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        recordLastLogin(details);
    }

    private void recordLastLogin(AppUserDetails details) {
        try {
            userService.recordLastLogin(details.id());
        } catch (RuntimeException e) {
            log.warn("Could not record last login for user {}: {}", details.id(), e.getMessage());
        }
    }
}
