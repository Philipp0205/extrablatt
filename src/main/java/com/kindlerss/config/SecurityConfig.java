package com.kindlerss.config;

import com.kindlerss.security.ApiTokenAuthenticationFilter;
import com.kindlerss.security.AppUserDetails;
import com.kindlerss.security.ClientIpResolver;
import com.kindlerss.security.DatabaseUserDetailsService;
import com.kindlerss.security.EmailNotVerifiedException;
import com.kindlerss.security.LoginStatusChecker;
import com.kindlerss.security.RecoveryCodeAuthenticationFilter;
import com.kindlerss.security.RecoveryCodeAuthenticationProvider;
import com.kindlerss.security.RateLimiter;
import com.kindlerss.security.RateLimitingFilter;
import com.kindlerss.security.StaleSessionFilter;
import com.kindlerss.service.ApiTokenService;
import com.kindlerss.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpHeaders;
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
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
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
            "/login", "/login/recovery", "/register", "/verify", "/forgot-password", "/reset-password",
            "/check-email", "/privacy", "/terms", "/imprint", "/withdrawal",
            // "Lost your recovery code?": ask for a sign-in link, and the page the link opens.
            // The token is in the URL fragment and is only read by the POST.
            "/recover", "/recover/request",
            // § 312k BGB requires the cancellation path to be permanently available
            // and not to sit behind account credentials, so it is public on purpose.
            // CSRF still applies; the form carries a token like every other.
            "/cancel",
            // Where the e-mailed confirmation link lands; the token is the credential.
            "/cancel/confirm",
            // Where the container sends a failed request to be answered (see
            // RateLimitingFilter's 429, which also reaches signed-out visitors). Boot's
            // error controller only renders the status of the request that failed.
            "/error"
    };

    private static final RequestMatcher API_PATH =
            PathPatternRequestMatcher.withDefaults().matcher("/api/v1/**");
    private static final RequestMatcher HAS_AUTHORIZATION_HEADER =
            request -> request.getHeader(HttpHeaders.AUTHORIZATION) != null;

    /**
     * API paths that only a signed-in browser session may use, for every method: a leaked
     * API token must not be able to redirect sends (Kindle address), mint more tokens or
     * replace the recovery code. The bearer chain denies them; the session chain, which
     * enforces CSRF, serves them. Add a path here to make it session-only.
     */
    static final String[] SESSION_ONLY_PATHS = {
            "/api/v1/account/kindle-email",
            "/api/v1/account/recovery-code",
            "/api/v1/account/trial-ended/ack",
            "/api/v1/tokens",
            "/api/v1/tokens/**"
    };

    static final String SESSION_ONLY_MESSAGE = "This needs a signed-in browser session, not an API token";

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
                .securityMatcher("/js/**", "/css/**", "/app-kit/**", "/fonts/**",
                        // Icons and the web app manifest: same for everyone, stable
                        // names, so WebConfig gives them a short public cache.
                        "/icons/**", "/favicon.ico", "/favicon.svg",
                        "/apple-touch-icon.png", "/manifest.webmanifest")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(RequestCacheConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .cacheControl(HeadersConfigurer.CacheControlConfig::disable));
        return http.build();
    }

    /**
     * The REST API, for scripts and agents: every {@code /api/v1/**} request that carries
     * an {@code Authorization} header, valid or not. A bearer token is the only
     * credential: no session is created or read, there is no login page to be sent to,
     * and CSRF is off because no browser cookie is involved to forge a request with.
     * Kept apart from the chain below so none of its form login, remember-me or CSRF
     * changes.
     */
    @Bean
    @Order(1)
    SecurityFilterChain apiFilterChain(HttpSecurity http, ApiTokenService apiTokens,
                                       RateLimiter rateLimiter) throws Exception {
        http
                .securityMatcher(new AndRequestMatcher(API_PATH, HAS_AUTHORIZATION_HEADER))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(SESSION_ONLY_PATHS).denyAll()
                        .anyRequest().authenticated())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(RequestCacheConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                // Only the session-only rule can deny in this chain: a valid token that
                // asks for one of those paths.
                .exceptionHandling(handling -> handling
                        .accessDeniedHandler((request, response, exception) ->
                                writeJsonError(response, 403, SESSION_ONLY_MESSAGE)))
                .addFilterBefore(new ApiTokenAuthenticationFilter(apiTokens, rateLimiter,
                                ClientIpResolver.fromSettings(appProperties.trustedProxies(),
                                        appProperties.trustCloudflare())),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * The same API for the browser client, which has no token: {@code /api/v1/**}
     * without an {@code Authorization} header, answered from the signed-in session.
     *
     * <p>The session is only read, never created ({@link SessionCreationPolicy#NEVER}).
     * Because a browser attaches the cookie by itself, CSRF is on, as a double-submit
     * cookie: {@code XSRF-TOKEN}, readable by the page's script, which sends it back in
     * {@code X-XSRF-TOKEN}. The plain (not XOR-masked) handler is used because the
     * script reads the raw cookie, and it loads the token eagerly so the cookie is
     * written on the first GET already. There is no login page, remember-me or request
     * cache here: nobody is redirected, an unauthenticated request is a JSON 401.
     */
    @Bean
    @Order(2)
    SecurityFilterChain apiSessionFilterChain(HttpSecurity http, RememberMeServices rememberMeServices,
                                              UserService userService) throws Exception {
        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookiePath("/");
        csrfTokens.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        // No attribute name means the token is not deferred: it is loaded (and its cookie
        // written) on every request, not only when something reads it.
        csrfHandler.setCsrfRequestAttributeName(null);
        http
                .securityMatcher(API_PATH)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.NEVER))
                .requestCache(RequestCacheConfigurer::disable)
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(csrfHandler))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            writeJsonError(response, 401, "Sign in, or send an API token");
                        })
                        .accessDeniedHandler((request, response, exception) -> writeJsonError(response, 403,
                                exception instanceof CsrfException
                                        ? "Missing or invalid CSRF token"
                                        : "Access denied")))
                // Right after the session's context is loaded, so a session that outlived a
                // password reset or a switched-off account is not honoured.
                .addFilterAfter(new StaleSessionFilter(userService, rememberMeServices),
                        SecurityContextHolderFilter.class);
        return http.build();
    }

    /** Fixed sentences only, so there is nothing to escape. */
    private static void writeJsonError(HttpServletResponse response, int status, String error)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }

    @Bean
    @Order(3)
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            RememberMeServices rememberMeServices,
                                            RateLimitingFilter rateLimitingFilter,
                                            UserService userService,
                                            UserDetailsService userDetailsService,
                                            PasswordEncoder passwordEncoder,
                                            ObjectProvider<AuthenticationEventPublisher> eventPublisher,
                                            ObjectProvider<DatabaseUserDetailsService> accounts)
            throws Exception {
        AuthenticationManager authenticationManager = authenticationManager(
                userDetailsService, passwordEncoder, eventPublisher.getIfAvailable(), accounts);
        http
                .authenticationManager(authenticationManager)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
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
                .addFilterBefore(recoveryCodeFilter(authenticationManager, rememberMeServices, userService),
                        UsernamePasswordAuthenticationFilter.class)
                // After remember-me, so a session restored from either source is checked
                // against the account as it is now.
                .addFilterAfter(new StaleSessionFilter(userService, rememberMeServices),
                        RememberMeAuthenticationFilter.class)
                .csrf(csrf -> csrf
                        // A payment provider cannot carry a CSRF token; it signs the
                        // request body instead.
                        .ignoringRequestMatchers("/webhooks/billing"))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(contentSecurityPolicy()))
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));
        return http.build();
    }

    /**
     * Scripts only from this app's own files, so markup that slipped past the
     * sanitiser cannot run anything; styles likewise only from the app's own
     * stylesheet, with no inline style at all. Images may come from anywhere, since
     * articles show the images of the sites they came from. Forms may post here, and on to the payment
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
                + "style-src 'self'; "
                + "img-src 'self' data: https: http:; "
                + "connect-src 'self'; "
                + "font-src 'self'; "
                + "manifest-src 'self'; "
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
                                                        AuthenticationEventPublisher eventPublisher,
                                                        ObjectProvider<DatabaseUserDetailsService> accounts) {
        DaoAuthenticationProvider form = new DaoAuthenticationProvider(passwordEncoder);
        form.setUserDetailsService(userDetailsService);
        form.setPreAuthenticationChecks(user -> { });
        form.setPostAuthenticationChecks(new LoginStatusChecker());
        // Looked up per attempt: the account store is not part of the web-only test slices.
        RecoveryCodeAuthenticationProvider recovery = new RecoveryCodeAuthenticationProvider(hash ->
                java.util.Optional.ofNullable(accounts.getIfAvailable())
                        .flatMap(store -> store.findByRecoveryHash(hash)));
        ProviderManager manager = new ProviderManager(
                form, recovery, new RememberMeAuthenticationProvider(appProperties.rememberMeKey()));
        if (eventPublisher != null) {
            manager.setAuthenticationEventPublisher(eventPublisher);
        }
        return manager;
    }

    /**
     * Login with a recovery code, wired like form login: the same success handler (last
     * login, saved-request redirect), the same remember-me services, and a new session id
     * on success. Every failure is the same redirect, see {@link #recoveryFailureHandler()}.
     */
    private RecoveryCodeAuthenticationFilter recoveryCodeFilter(AuthenticationManager authenticationManager,
                                                                RememberMeServices rememberMeServices,
                                                                UserService userService) {
        RecoveryCodeAuthenticationFilter filter = new RecoveryCodeAuthenticationFilter();
        filter.setAuthenticationManager(authenticationManager);
        filter.setSecurityContextRepository(new HttpSessionSecurityContextRepository());
        filter.setSessionAuthenticationStrategy(new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(new HttpSessionCsrfTokenRepository()))));
        filter.setRememberMeServices(rememberMeServices);
        filter.setAuthenticationSuccessHandler(formLoginSuccessHandler(userService));
        filter.setAuthenticationFailureHandler(recoveryFailureHandler());
        return filter;
    }

    /**
     * A wrong, unknown, malformed or switched-off code all read as the same generic
     * error. Only a code that matched an account with an unconfirmed address earns the
     * "unverified" hint, as with a right password. The typed code is kept nowhere.
     */
    private AuthenticationFailureHandler recoveryFailureHandler() {
        return (request, response, exception) -> {
            String failure = exception instanceof EmailNotVerifiedException ? "unverified" : "recoveryError";
            response.sendRedirect(request.getContextPath() + "/login?" + failure);
        };
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
