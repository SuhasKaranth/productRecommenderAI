package com.smartguide.poc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for automatically starting development services
 * (Frontend and Scraper) when the main application starts.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app.dev-services")
public class DevServicesProperties {

    /**
     * Enable/disable automatic startup of development services.
     * Set to true to start frontend and scraper automatically.
     */
    private boolean enabled = false;

    /**
     * Frontend service configuration
     */
    private FrontendConfig frontend = new FrontendConfig();

    /**
     * Scraper service configuration
     */
    private ScraperConfig scraper = new ScraperConfig();

    @Data
    public static class FrontendConfig {
        /**
         * Enable/disable frontend service auto-start
         */
        private boolean enabled = true;

        /**
         * Relative path to frontend directory from project root
         */
        private String path = "frontend";

        /**
         * Port for the React development server
         */
        private int port = 3000;

        /**
         * Command to start the frontend (npm or yarn)
         */
        private String command = "npm";

        /**
         * Arguments for the start command
         */
        private String args = "start";
    }

    @Data
    public static class ScraperConfig {
        /**
         * Enable/disable scraper service auto-start
         */
        private boolean enabled = true;

        /**
         * Relative path to scraper service directory from project root
         */
        private String path = "product-scraper-service";

        /**
         * Port for the scraper service
         */
        private int port = 8081;

        /**
         * JVM memory allocation for scraper service
         */
        private String jvmMemory = "512m";
    }
}
