package com.kindlerss.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** form-action names the payment provider's origin, and only an origin. */
class ContentSecurityPolicyTest {

    @Test
    void anOriginIsSchemeHostAndPortAndNothingElse() {
        assertEquals("https://buy.stripe.com", SecurityConfig.originOf("https://buy.stripe.com/abc?x=1"));
        assertEquals("https://pay.example:8443", SecurityConfig.originOf("https://Pay.Example:8443/x"));
    }

    @Test
    void anythingThatIsNotAPlainHttpUrlIsLeftOut() {
        assertNull(SecurityConfig.originOf(null));
        assertNull(SecurityConfig.originOf("javascript:alert(1)"));
        assertNull(SecurityConfig.originOf("/relative"));
        assertNull(SecurityConfig.originOf("https://a b/"));
        assertNull(SecurityConfig.originOf("https://evil.example;script-src*/"));
    }
}
