package com.kindlerss.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitingFilterTest {

    private final RateLimitingFilter filter = new RateLimitingFilter(new RateLimiter());

    private int post(String path, String remoteAddr, String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void aSpoofedForwardedForHeaderDoesNotBuyFreshAttempts() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(post("/login", "203.0.113.7", "10.0.0." + i)).isEqualTo(200);
        }
        assertThat(post("/login", "203.0.113.7", "10.0.0.99")).isEqualTo(429);
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
