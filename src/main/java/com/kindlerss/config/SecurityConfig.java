package com.kindlerss.config;

import com.kindlerss.security.RateLimitingFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
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

    /** Public pages that must be reachable without an account. */
    private static final String[] PUBLIC_PATHS = {
            "/login", "/register", "/verify", "/forgot-password", "/reset-password",
            "/check-email", "/privacy", "/terms"
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
        TokenBasedRememberMeServices services =
                new TokenBasedRememberMeServices(appProperties.rememberMeKey(), userDetailsService);
        services.setTokenValiditySeconds(365 * 24 * 60 * 60);
        services.setUseSecureCookie(isProduction());
        services.setParameter("remember-me");
        return services;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            RememberMeServices rememberMeServices,
                                            RateLimitingFilter rateLimitingFilter) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/css/**", "/js/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
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
                .csrf(Customizer.withDefaults());
        return http.build();
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
            String failure = exception instanceof DisabledException ? "unverified" : "error";
            response.sendRedirect(request.getContextPath() + "/login?" + failure);
        };
    }

    private boolean isProduction() {
        return environment.acceptsProfiles(Profiles.of("production"));
    }
}
