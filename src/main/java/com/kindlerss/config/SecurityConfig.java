package com.kindlerss.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Single-user form login: one in-memory account from {@code APP_PASSWORD},
 * plus a long-lived remember-me cookie for the Kindle-friendly workflow.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AppProperties.class)
public class SecurityConfig implements WebMvcConfigurer {

    private final AppProperties appProperties;
    private final Environment environment;

    public SecurityConfig(AppProperties appProperties, Environment environment) {
        this.appProperties = appProperties;
        this.environment = environment;
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/login").setViewName("login");
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        String raw = appProperties.password();
        if (!StringUtils.hasText(raw)) {
            throw new IllegalStateException("APP_PASSWORD must be set");
        }
        // Spring Security's DaoAuthenticationProvider compares the login form
        // password against an already-encoded hash (BCrypt). Encoding once at
        // startup means APP_PASSWORD never sits in memory as the stored credential.
        //
        // InMemoryUserDetailsManager is the user store for this single-user app:
        // one hard-coded "kindle" account, no database-backed users needed.
        return new InMemoryUserDetailsManager(
                User.builder()
                        .username("kindle")
                        .password(passwordEncoder.encode(raw))
                        .roles("USER")
                        .build()
        );
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
                                            RememberMeServices rememberMeServices) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/css/**", "/js/**").permitAll()
                        .requestMatchers("/login").permitAll()
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
                .csrf(Customizer.withDefaults());
        return http.build();
    }

    private boolean isProduction() {
        return environment.acceptsProfiles(Profiles.of("production"));
    }
}
