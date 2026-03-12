package com.smartguide.poc.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration for API key-based authentication
 *
 * Security model:
 * - Public endpoints: /health, / (root)
 * - USER-level: /api/v1/recommend, /api/products/**, /docs, /api-docs
 * - ADMIN-level: /api/admin/**, /ui/**, /api/scraper/**
 *
 * Future enhancements:
 * - Method-level security with @PreAuthorize
 * - Role-based access control (RBAC)
 * - OAuth2 integration
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;
    private final ApiKeyService apiKeyService;

    public SecurityConfig(ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
                         ApiKeyService apiKeyService) {
        this.apiKeyAuthenticationFilter = apiKeyAuthenticationFilter;
        this.apiKeyService = apiKeyService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        // If API key authentication is disabled, allow all requests (dev mode)
        if (!apiKeyService.isAuthEnabled()) {
            http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                    .frameOptions(frame -> frame.deny())
                    .xssProtection(Customizer.withDefaults())
                    .contentTypeOptions(Customizer.withDefaults())
                    .httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .maxAgeInSeconds(31536000))
                    .contentSecurityPolicy(csp -> csp
                        .policyDirectives("default-src 'self'; script-src 'self'; " +
                            "style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
                            "frame-ancestors 'none'"))
                );
            return http.build();
        }

        http
            // Disable CSRF since we're using API keys (stateless)
            .csrf(AbstractHttpConfigurer::disable)

            // Disable session management (stateless API)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // Security response headers (SEC-10)
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .xssProtection(Customizer.withDefaults())
                .contentTypeOptions(Customizer.withDefaults())
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000))
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("default-src 'self'; script-src 'self'; " +
                        "style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
                        "frame-ancestors 'none'"))
            )

            // Configure authorization rules
            .authorizeHttpRequests(auth -> auth
                // ========== PUBLIC ENDPOINTS (No authentication) ==========
                .requestMatchers("/health", "/").permitAll()

                // ========== AUTHENTICATED ENDPOINTS (USER or ADMIN) ==========
                // API Documentation
                .requestMatchers("/docs", "/docs/**", "/api-docs", "/api-docs/**",
                               "/swagger-ui/**", "/v3/api-docs/**").authenticated()

                // Recommendation API
                .requestMatchers("/api/v1/recommend").authenticated()

                // Product API (read-only for users)
                .requestMatchers("GET", "/api/products/**").authenticated()

                // ========== ADMIN-ONLY ENDPOINTS ==========
                // Admin API
                .requestMatchers("/api/admin/**").hasAuthority("SCOPE_admin:*")

                // Web UI
                .requestMatchers("/ui", "/ui/**").hasAuthority("SCOPE_admin:*")

                // Scraper API
                .requestMatchers("/api/scraper/**").hasAuthority("SCOPE_admin:*")

                // Product write operations (admin only)
                .requestMatchers("POST", "/api/products/**").hasAuthority("SCOPE_admin:*")
                .requestMatchers("PUT", "/api/products/**").hasAuthority("SCOPE_admin:*")
                .requestMatchers("DELETE", "/api/products/**").hasAuthority("SCOPE_admin:*")

                // ========== DEFAULT: Require authentication for everything else ==========
                .anyRequest().authenticated()
            )

            // Add our custom API key filter before username/password filter
            .addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
