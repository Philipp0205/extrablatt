package com.kindlerss.security;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitingFilterTest {

    private static final String RAILWAY_EDGE = "100.64.0.2";
    private static final String CLOUDFLARE = "172.68.10.20";

    private final RateLimitingFilter filter =
            new RateLimitingFilter(new RateLimiter(), ClientIpResolver.defaults());

    private int post(String path, String remoteAddr, String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        // ForwardedHeaderFilter first, as server.forward-headers-strategy: framework
        // runs it in production; it rewrites getRemoteAddr() to the leftmost entry.
        new MockFilterChain(new NoContent(), new ForwardedHeaderFilter(), filter)
                .doFilter(request, response);
        return response.getStatus();
    }

    private static final class NoContent extends HttpServlet {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) {
        }
    }

    @Test
    void aSpoofedForwardedForHeaderDoesNotBuyFreshAttempts() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(post("/login", "203.0.113.7", "10.0.0." + i)).isEqualTo(200);
        }
        assertThat(post("/login", "203.0.113.7", "10.0.0.99")).isEqualTo(429);
    }

    @Test
    void aSpoofedLeftmostEntryBehindCloudflareDoesNotBuyFreshAttempts() throws Exception {
        String chain = ", 203.0.113.7, " + CLOUDFLARE;
        for (int i = 0; i < 10; i++) {
            assertThat(post("/login", RAILWAY_EDGE, "198.51.100." + i + chain)).isEqualTo(200);
        }
        assertThat(post("/login", RAILWAY_EDGE, "198.51.100.99" + chain)).isEqualTo(429);
        // Another reader behind the same Cloudflare and Railway hops is unaffected.
        assertThat(post("/login", RAILWAY_EDGE, "203.0.113.8, " + CLOUDFLARE)).isEqualTo(200);
    }

    @Test
    void aRequestStraightToTheOriginIsKeyedOnTheCallerRailwaySaw() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(post("/login", RAILWAY_EDGE, "198.51.100." + i + ", 203.0.113.9")).isEqualTo(200);
        }
        assertThat(post("/login", RAILWAY_EDGE, CLOUDFLARE + ", 203.0.113.9")).isEqualTo(429);
    }

    @Test
    void otherClientsAreNotAffected() throws Exception {
        for (int i = 0; i < 11; i++) {
            post("/register", "203.0.113.7", null);
        }
        assertThat(post("/register", "203.0.113.7", null)).isEqualTo(429);
        assertThat(post("/register", "198.51.100.1", null)).isEqualTo(200);
    }

    @Test
    void thePublicCancellationFormIsThrottledToo() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(post("/cancel", "203.0.113.8", null)).isEqualTo(200);
        }
        assertThat(post("/cancel", "203.0.113.8", null)).isEqualTo(429);
    }
}
