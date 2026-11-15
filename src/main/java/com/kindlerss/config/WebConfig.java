package com.kindlerss.config;

import org.springframework.context.annotation.Configuration;
import com.kindlerss.web.LayoutInterceptor;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.VersionResourceResolver;

import java.time.Duration;

/**
 * Serves the scripts and the stylesheet under a name that includes a hash of their
 * contents, so they can be cached for good.
 *
 * <p>Reading is a sequence of page loads, and every one of them used to refetch
 * every script, because nothing the app served was cacheable. That is kilobytes
 * of compressed JavaScript per page on a device that is often on a slow connection, for
 * files that had not changed since the deploy.
 *
 * <p>A hashed name makes a long lifetime safe: {@code /js/app-<hash>.js} is a
 * different address after every change to the file, so a browser can be told to
 * keep it for a year and still never serve yesterday's file. Thymeleaf's
 * {@code @{/js/app.js}} is rewritten to the hashed address on the way out, so
 * the templates keep naming the plain file. The stylesheet is linked the same way,
 * from {@code /css/app-<hash>.css}, so a page carries no inline styles at all.
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

    /**
     * About a week, public, not immutable. The icon file names are stable (no
     * hash), so a later logo change must be able to reach browsers within days
     * instead of being stuck for a year.
     */
    private static final CacheControl WEEK = CacheControl
            .maxAge(Duration.ofDays(7))
            .cachePublic();

    /**
     * The manifest is revalidated on every use: {@code max-age=0, must-revalidate,
     * public}. It is tiny and carries the theme and background colours of an
     * installed app, so a stale copy (the week-long one that kept the old light
     * colours) is worse than one conditional request. Not {@code no-store}: the
     * browser may keep the file and just ask whether it changed (ETag/Last-Modified
     * gives a cheap 304), and it is the same for everyone.
     */
    private static final CacheControl REVALIDATE = CacheControl
            .maxAge(Duration.ZERO)
            .cachePublic()
            .mustRevalidate();

    /**
     * Decides per request between the scrolling layout and the e-ink, page-turning
     * one (see {@link LayoutInterceptor}). Static files are the same for everyone.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LayoutInterceptor())
                .excludePathPatterns("/js/**", "/css/**", "/app-kit/**", "/fonts/**",
                        "/icons/**", "/favicon.ico", "/favicon.svg",
                        "/apple-touch-icon.png", "/manifest.webmanifest");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/icons/**")
                .addResourceLocations("classpath:/static/icons/")
                .setCacheControl(WEEK);
        registry.addResourceHandler("/favicon.ico", "/favicon.svg",
                        "/apple-touch-icon.png")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(WEEK);
        registry.addResourceHandler("/manifest.webmanifest")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(REVALIDATE);
        // The font file names carry their version (newsreader-5.3.0-...), so no
        // version resolver is needed to make a year-long cache safe.
        registry.addResourceHandler("/fonts/**")
                .addResourceLocations("classpath:/static/fonts/")
                .setCacheControl(FOREVER);
        registry.addResourceHandler("/js/**")
                .addResourceLocations("classpath:/static/js/")
                .setCacheControl(FOREVER)
                .resourceChain(true)
                .addResolver(new VersionResourceResolver().addContentVersionStrategy("/**"));
        registry.addResourceHandler("/css/**")
                .addResourceLocations("classpath:/static/css/")
                .setCacheControl(FOREVER)
                .resourceChain(true)
                .addResolver(new VersionResourceResolver().addContentVersionStrategy("/**"));
        // The vendored app-kit (see static/app-kit/VERSION), hashed like /css and /js.
        registry.addResourceHandler("/app-kit/**")
                .addResourceLocations("classpath:/static/app-kit/")
                .setCacheControl(FOREVER)
                .resourceChain(true)
                .addResolver(new VersionResourceResolver().addContentVersionStrategy("/**"));
    }
}
