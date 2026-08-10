package com.kindlerss.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String password,
        String kindleEmail,
        String mailFrom,
        String rememberMeKey,
        Http http
) {
    public AppProperties {
        if (http == null) {
            http = new Http(Duration.ofSeconds(10), Duration.ofSeconds(20), 2_097_152);
        }
        if (rememberMeKey == null || rememberMeKey.isBlank()) {
            rememberMeKey = "kindle-rss-remember-me-change-me";
        }
    }

    public record Http(Duration connectTimeout, Duration readTimeout, int maxBytes) {
        public Http {
            if (connectTimeout == null) {
                connectTimeout = Duration.ofSeconds(10);
            }
            if (readTimeout == null) {
                readTimeout = Duration.ofSeconds(20);
            }
            if (maxBytes <= 0) {
                maxBytes = 2_097_152;
            }
        }
    }
}
