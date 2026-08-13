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
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
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

    private boolean isProduction() {
        return environment.acceptsProfiles(Profiles.of("production"));
    }
}
