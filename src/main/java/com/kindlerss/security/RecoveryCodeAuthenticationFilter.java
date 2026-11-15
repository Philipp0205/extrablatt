package com.kindlerss.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Login with a recovery code: POST /login/recovery with the code in {@code code}. It is
 * an ordinary authentication filter, so the session, session-fixation protection,
 * remember-me (only with the {@code remember-me} box ticked) and success and failure
 * handlers are the same machinery form login uses; the code is never kept anywhere.
 */
public class RecoveryCodeAuthenticationFilter extends AbstractAuthenticationProcessingFilter {

    public static final String PATH = "/login/recovery";
    public static final String CODE_PARAMETER = "code";

    public RecoveryCodeAuthenticationFilter() {
        super(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, PATH));
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
            throws AuthenticationException {
        String code = request.getParameter(CODE_PARAMETER);
        return getAuthenticationManager().authenticate(new RecoveryCodeAuthenticationToken(code == null ? "" : code));
    }
}
