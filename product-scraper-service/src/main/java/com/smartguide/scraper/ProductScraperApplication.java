package com.smartguide.scraper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Main application class for Product Scraper Service
 * Excludes R2DBC autoconfiguration since we use standard JPA/JDBC
 */
@SpringBootApplication(exclude = {R2dbcAutoConfiguration.class})
@EnableAsync
public class ProductScraperApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductScraperApplication.class, args);
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
