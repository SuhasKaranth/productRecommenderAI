package com.smartguide.poc.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration for the application.
 *
 * Allowed origins are driven by the {@code app.cors.allowed-origins} property
 * (env var: {@code CORS_ALLOWED_ORIGINS}).  In production, set this to the
 * bank's actual domains (e.g. {@code https://mobilebanking.bank.ae}).
 */
@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:3001}")
    private String[] allowedOrigins;

    @Value("${app.cors.allowed-headers:Content-Type,X-API-Key,X-Correlation-ID,Authorization}")
    private String[] allowedHeaders;

    @Value("${app.cors.exposed-headers:X-Correlation-ID}")
    private String[] exposedHeaders;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins(allowedOrigins)
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                        .allowedHeaders(allowedHeaders)
                        .exposedHeaders(exposedHeaders)
                        .allowCredentials(true)
                        .maxAge(3600);
            }
        };
    }
}
