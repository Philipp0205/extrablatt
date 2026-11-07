package com.kindlerss.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RememberMeKeyPolicyTest {

    private static final String GOOD = "q7Vh2kLw9xZp4RbT8mNc3sYd6fGj1aEu0oIi5HlK";

    @Test
    void localDevelopmentNeedsNoKey() {
        assertThatCode(() -> RememberMeKeyPolicy.check(
                "kindle-rss-remember-me-change-me", "http://localhost:8080", false)).doesNotThrowAnyException();
        assertThatCode(() -> RememberMeKeyPolicy.check(
                "short", "http://127.0.0.1:8080", false)).doesNotThrowAnyException();
    }

    @Test
    void theProductionProfileRefusesTheBuiltInDefault() {
        assertThatThrownBy(() -> RememberMeKeyPolicy.check(
                "kindle-rss-remember-me-change-me", "http://localhost:8080", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    void aPublicUrlRefusesTheEnvExamplePlaceholderEvenWithoutTheProfile() {
        assertThatThrownBy(() -> RememberMeKeyPolicy.check(
                "change-me-another-long-random", "https://reader.example.com", false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aShortKeyIsRefused() {
        assertThatThrownBy(() -> RememberMeKeyPolicy.check("only-twenty-chars-xx", "https://reader.example.com", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void aLongRandomKeyIsAccepted() {
        assertThatCode(() -> RememberMeKeyPolicy.check(GOOD, "https://reader.example.com", true))
                .doesNotThrowAnyException();
    }

    @Test
    void theErrorNeverEchoesTheKey() {
        assertThatThrownBy(() -> RememberMeKeyPolicy.check("change-me-but-secret-ish-value-123456",
                "https://reader.example.com", true))
                .message()
                .doesNotContain("secret-ish");
    }

    @Test
    void recognisesLoopbackHosts() {
        assertThat(RememberMeKeyPolicy.isLoopback("http://localhost:8080")).isTrue();
        assertThat(RememberMeKeyPolicy.isLoopback("http://[::1]:8080")).isTrue();
        assertThat(RememberMeKeyPolicy.isLoopback("http://127.0.0.1")).isTrue();
        assertThat(RememberMeKeyPolicy.isLoopback("https://reader.example.com")).isFalse();
        assertThat(RememberMeKeyPolicy.isLoopback("https://localhost.example.com")).isFalse();
    }
}
