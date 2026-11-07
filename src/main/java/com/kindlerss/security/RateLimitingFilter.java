package com.kindlerss.security;

import com.kindlerss.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/**
 * Throttles the unauthenticated POST endpoints that are attractive to abuse:
 * login, registration, password reset, and the public cancellation form. Keyed by
 * the client address {@link ClientIpResolver} works out from the socket peer and
 * X-Forwarded-For, walked from the right past {@code app.trusted-proxies}. Not
 * {@link HttpServletRequest#getRemoteAddr()}: behind
 * {@code server.forward-headers-strategy: framework} that is the leftmost header
 * entry, which the client picks, so anyone could have a fresh key per request.
 */
@Component
@Order(1)
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED_PATHS = Set.of(
            "/login", "/register", "/forgot-password", "/reset-password",
            "/cancel", "/cancel/confirm");
    private static final int MAX_HITS = 10;
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    @Autowired
    public RateLimitingFilter(RateLimiter rateLimiter, AppProperties properties) {
        this(rateLimiter, ClientIpResolver.fromSettings(properties.trustedProxies(),
                properties.trustCloudflare()));
    }

    public RateLimitingFilter(RateLimiter rateLimiter, ClientIpResolver clientIpResolver) {
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(request.getMethod()) && LIMITED_PATHS.contains(request.getServletPath())) {
            String key = request.getServletPath() + "|" + clientIpResolver.resolve(request);
            if (!rateLimiter.tryAcquire(key, MAX_HITS, WINDOW)) {
                response.setStatus(429);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("Too many attempts. Please wait a few minutes and try again.");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
