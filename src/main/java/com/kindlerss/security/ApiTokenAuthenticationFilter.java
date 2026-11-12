package com.kindlerss.security;

import com.kindlerss.service.ApiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Authenticates {@code /api/v1/**} by a personal API token in an
 * {@code Authorization: Bearer} header, and throttles those requests.
 *
 * <p>A request with a valid token is limited per token. One without is limited per
 * client address, so guessing tokens cannot be spread over fresh keys. Not a
 * {@code @Component}: it belongs to the API filter chain only, and a bean would also
 * be registered in the servlet container for every request.
 */
public class ApiTokenAuthenticationFilter extends OncePerRequestFilter {

    static final int MAX_REQUESTS = 60;
    static final Duration WINDOW = Duration.ofMinutes(1);
    private static final String BEARER = "bearer ";

    private final ApiTokenService tokens;
    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    public ApiTokenAuthenticationFilter(ApiTokenService tokens, RateLimiter rateLimiter,
                                        ClientIpResolver clientIpResolver) {
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String secret = bearerToken(request);
        Optional<AppUserDetails> user = tokens.authenticate(secret);
        String key = user.isPresent()
                ? "api|" + ApiTokenService.hashToken(secret)
                : "api-anonymous|" + clientIpResolver.resolve(request);
        if (!rateLimiter.tryAcquire(key, MAX_REQUESTS, WINDOW)) {
            write(response, 429, "Too many requests. Please wait a minute and try again.");
            return;
        }
        if (user.isEmpty()) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            write(response, 401, "Missing, invalid or revoked API token");
            return;
        }
        AppUserDetails details = user.get();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                details, null, details.getAuthorities()));
        SecurityContextHolder.setContext(context);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.length() <= BEARER.length()
                || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        return header.substring(BEARER.length()).trim();
    }

    /** Fixed sentences only, so there is nothing to escape. */
    private static void write(HttpServletResponse response, int status, String error) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }
}
