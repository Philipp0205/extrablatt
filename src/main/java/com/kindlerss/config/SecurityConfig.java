package com.kindlerss.config;

import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.EmailNotVerifiedException;
import com.kindlerss.security.LoginStatusChecker;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.security.StaleSessionFilter;
import com.kindlerss.service.UserService;
import com.kindlerss.web.StylesheetAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.RememberMeAuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;

/**
 * Multi-user form login backed by the database. Accounts register with an e-mail
 * and password; a long-lived remember-me cookie keeps the Kindle-friendly
 * workflow logged in.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AppProperties.class)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** Public pages that must be reachable without an account. */
    private static final String[] PUBLIC_PATHS = {
            "/login", "/register", "/verify", "/forgot-password", "/reset-password",
            "/check-email", "/privacy", "/terms", "/imprint", "/withdrawal",
            // § 312k BGB requires the cancellation path to be permanently available
            // and not to sit behind account credentials, so it is public on purpose.
            // CSRF still applies; the form carries a token like every other.
            "/cancel"
    };

    /** Session attribute holding the e-mail from a failed login, so the form can keep it. */
    public static final String LAST_LOGIN_USERNAME = "LAST_LOGIN_USERNAME";

    private final AppProperties appProperties;
    private final Environment environment;

    public SecurityConfig(AppProperties appProperties, Environment environment) {
        this.appProperties = appProperties;
        this.environment = environment;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    RememberMeServices rememberMeServices(UserDetailsService userDetailsService) {
        RememberMeKeyPolicy.check(appProperties.rememberMeKey(), appProperties.publicUrl(), isProduction());
        TokenBasedRememberMeServices services =
                new TokenBasedRememberMeServices(appProperties.rememberMeKey(), userDetailsService);
        services.setTokenValiditySeconds(365 * 24 * 60 * 60);
        services.setUseSecureCookie(isProduction());
        services.setParameter("remember-me");
        return services;
    }

    /**
     * The scripts, kept out of the chain below.
     *
     * <p>They were let through it with {@code permitAll}, which allows the request
     * but still runs every filter on it — including the one that writes Spring
     * Security's default headers. Those headers say {@code no-store}, which is the
     * right answer for a page holding somebody's reading list and the wrong one for
     * a file of code that is identical for every reader: it forbade the browser from
     * keeping any of it, so every page load fetched every script again. The same
     * applied to the stylesheet, and there a refetch also held up the first paint —
     * which is the moment of unstyled page a phone would show.
     *
     * <p>These files reveal nothing and are the same for everyone, so they get their
     * own chain that skips the header writer, the request cache and the session
     * entirely, and lets {@link WebConfig}'s year-long, content-addressed caching
     * stand.
     */
    @Bean
    @Order(0)
    SecurityFilterChain staticAssetFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/js/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(RequestCacheConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .cacheControl(HeadersConfigurer.CacheControlConfig::disable));
        return http.build();
    }

    @Bean
    @Order(1)
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            RememberMeServices rememberMeServices,
                                            RateLimitingFilter rateLimitingFilter,
                                            UserService userService,
                                            UserDetailsService userDetailsService,
                                            PasswordEncoder passwordEncoder,
                                            ObjectProvider<AuthenticationEventPublisher> eventPublisher)
            throws Exception {
        http
                .authenticationManager(authenticationManager(
                        userDetailsService, passwordEncoder, eventPublisher.getIfAvailable()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // Called by the inbound e-mail provider, not a browser; guarded by its
                        // own shared secret instead of a session (see NewsletterInboundController).
                        .requestMatchers("/inbound/newsletters").permitAll()
                        // Called by the payment provider, not a browser; authenticated by
                        // its HMAC signature (see BillingWebhookController).
                        .requestMatchers("/webhooks/billing").permitAll()
                        .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .successHandler(formLoginSuccessHandler(userService))
                        .failureHandler(loginFailureHandler())
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .deleteCookies("JSESSIONID", "remember-me")
                        .permitAll()
                )
                .rememberMe(remember -> remember
                        .rememberMeServices(rememberMeServices)
                        .key(appProperties.rememberMeKey())
                )
                .addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
                // After remember-me, so a session restored from either source is checked
                // against the account as it is now.
                .addFilterAfter(new StaleSessionFilter(userService, rememberMeServices),
                        RememberMeAuthenticationFilter.class)
                .csrf(csrf -> csrf
                        // A mail provider cannot carry a CSRF token; the shared secret is its
                        // authentication instead. Nor can a payment provider, which signs the
                        // request body instead.
                        .ignoringRequestMatchers("/inbound/newsletters", "/webhooks/billing"))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(contentSecurityPolicy()))
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));
        return http.build();
    }

    /**
     * Scripts only from this app's own files, so markup that slipped past the
     * sanitiser cannot run anything; the inline stylesheet by its hash and no other
     * inline style. Images may come from anywhere, since articles show the images
     * of the sites they came from. Forms may post here, and on to the payment
     * provider's checkout and portal, which the order and portal forms redirect to.
     */
    String contentSecurityPolicy() {
        StringBuilder formAction = new StringBuilder("'self'");
        AppProperties.Billing billing = appProperties.billing();
        for (String url : new String[] {billing.monthlyCheckoutUrl(), billing.yearlyCheckoutUrl(),
                billing.portalUrl()}) {
            String origin = originOf(url);
            if (origin != null && formAction.indexOf(" " + origin) < 0) {
                formAction.append(' ').append(origin);
            }
        }
        return "default-src 'self'; "
                + "script-src 'self'; "
                + "style-src " + StylesheetAdvice.contentSecurityPolicyHash() + "; "
                + "img-src 'self' data: https: http:; "
                + "connect-src 'self'; "
                + "font-src 'self'; "
                + "object-src 'none'; "
                + "base-uri 'self'; "
                + "frame-ancestors 'none'; "
                + "form-action " + formAction;
    }

    /** {@code https://host[:port]} of an absolute http(s) URL, or null. */
    static String originOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null) {
                return null;
            }
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            if (!host.matches("[a-z0-9.\\-\\[\\]:]+")) {
                return null;
            }
            return scheme + "://" + host + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Form login and remember-me, without a fallback to Spring's global manager. That
     * one checks account status before the password, so an unconfirmed address
     * answered "confirm your e-mail" to any password at all, which told a stranger
     * the address was registered. Here the password is checked first (see
     * {@link LoginStatusChecker}). It is not a bean on purpose: an
     * {@code AuthenticationManager} bean would switch off Boot's defaults that the
     * slice tests rely on.
     */
    private AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                        PasswordEncoder passwordEncoder,
                                                        AuthenticationEventPublisher eventPublisher) {
        DaoAuthenticationProvider form = new DaoAuthenticationProvider(passwordEncoder);
        form.setUserDetailsService(userDetailsService);
        form.setPreAuthenticationChecks(user -> { });
        form.setPostAuthenticationChecks(new LoginStatusChecker());
        ProviderManager manager = new ProviderManager(
                form, new RememberMeAuthenticationProvider(appProperties.rememberMeKey()));
        if (eventPublisher != null) {
            manager.setAuthenticationEventPublisher(eventPublisher);
        }
        return manager;
    }

    /**
     * Records last login, then continues the usual post-login redirect. A reader
     * sent here from a page they asked for — the landing page's "Choose yearly"
     * button lands on /billing/order — comes back to it after signing in instead of
     * being dumped on the home page. With no such destination remembered, "/" is
     * still the default.
     */
    private AuthenticationSuccessHandler formLoginSuccessHandler(UserService userService) {
        SavedRequestAwareAuthenticationSuccessHandler redirect =
                new SavedRequestAwareAuthenticationSuccessHandler();
        redirect.setDefaultTargetUrl("/");
        return (request, response, authentication) -> {
            recordLastLogin(userService, authentication);
            redirect.onAuthenticationSuccess(request, response, authentication);
        };
    }

    static void recordLastLogin(UserService userService, Authentication authentication) {
        if (authentication == null) {
            return;
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof AppUserDetails details)) {
            return;
        }
        try {
            userService.recordLastLogin(details.id());
        } catch (RuntimeException e) {
            log.warn("Could not record last login for user {}: {}", details.id(), e.getMessage());
        }
    }

    /**
     * On a failed login, remember the e-mail that was tried so the form can put it
     * back — a wrong password should not also make the user retype their address.
     * The password is never kept.
     */
    private AuthenticationFailureHandler loginFailureHandler() {
        return (request, response, exception) -> {
            String username = request.getParameter("username");
            if (username != null && !username.isBlank()) {
                request.getSession().setAttribute(LAST_LOGIN_USERNAME, username.trim());
            }
            // Only a correct password on an unconfirmed account earns the specific hint;
            // everything else, a switched-off account included, reads as a plain failure.
            String failure = exception instanceof EmailNotVerifiedException ? "unverified" : "error";
            response.sendRedirect(request.getContextPath() + "/login?" + failure);
        };
    }

    private boolean isProduction() {
        return environment.acceptsProfiles(Profiles.of("production"));
    }
}
