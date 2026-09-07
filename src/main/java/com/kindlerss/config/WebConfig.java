package com.kindlerss.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.VersionResourceResolver;

import java.time.Duration;

/**
 * Serves the scripts under a name that includes a hash of their contents, so they
 * can be cached for good.
 *
 * <p>Reading is a sequence of page loads — a page turn in the reader is a fresh
 * document — and every one of them used to refetch every script, because nothing
 * the app served was cacheable. That is around fifteen kilobytes of compressed
 * JavaScript per page turn on a device that is often on a slow connection, for
 * files that had not changed since the deploy.
 *
 * <p>A hashed name makes a long lifetime safe: {@code /js/reader-<hash>.js} is a
 * different address after every change to the file, so a browser can be told to
 * keep it for a year and still never serve yesterday's reader. Thymeleaf's
 * {@code @{/js/reader.js}} is rewritten to the hashed address on the way out, so
 * the templates keep naming the plain file.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * A year, which is as long as HTTP allows. {@code immutable} additionally tells
     * the browser not to check back even when the reader pulls to refresh — for a
     * name that already contains the file's hash there is nothing to check.
     */
    private static final CacheControl FOREVER = CacheControl
            .maxAge(Duration.ofDays(365))
            .cachePublic()
            .immutable();

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/js/**")
                .addResourceLocations("classpath:/static/js/")
                .setCacheControl(FOREVER)
                .resourceChain(true)
                .addResolver(new VersionResourceResolver().addContentVersionStrategy("/**"));
    }
}
