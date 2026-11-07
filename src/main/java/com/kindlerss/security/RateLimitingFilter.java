package com.kindlerss.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/**
 * Throttles the unauthenticated POST endpoints that are attractive to abuse:
 * login, registration, password reset, and the public cancellation form. Keyed by
 * client IP as the servlet container reports it. A raw X-Forwarded-For header is
 * never read here: the client controls it, so trusting it would let anyone pick a
 * fresh key per request. Behind a proxy, {@code server.forward-headers-strategy}
 * decides what {@link HttpServletRequest#getRemoteAddr()} returns.
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

    public RateLimitingFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(request.getMethod()) && LIMITED_PATHS.contains(request.getServletPath())) {
            String key = request.getServletPath() + "|" + request.getRemoteAddr();
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
