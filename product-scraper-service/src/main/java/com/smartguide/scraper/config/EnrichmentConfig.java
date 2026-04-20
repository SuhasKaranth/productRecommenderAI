package com.smartguide.scraper.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * YAML-driven configuration for the AI enrichment pipeline.
 *
 * <p>Bound from the {@code scraper.enrichment.*} properties in {@code application.yml}.
 * Validates all values at startup and logs a warning + applies a safe default for each
 * out-of-range value.
 *
 * <p>All properties can be overridden via environment variables using Spring's
 * relaxed-binding convention (e.g. {@code SCRAPER_ENRICHMENT_BATCH_SIZE=5}).
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "scraper.enrichment")
public class EnrichmentConfig {

    /** Selects the enrichment strategy: batch (N products per LLM call) or sequential (1 per call). */
    public enum EnrichmentMode { BATCH, SEQUENTIAL }

    /** BATCH or SEQUENTIAL. Default: BATCH. */
    private EnrichmentMode mode = EnrichmentMode.BATCH;

    /** Number of products per LLM call in BATCH mode. Ignored in SEQUENTIAL mode. Default: 7. */
    private int batchSize = 7;

    /** Timeout in seconds for a single LLM API call (round-trip including inference). Default: 120. */
    private int llmTimeoutSeconds = 120;

    /** Whether to retry a failed LLM call once before marking the batch as failed. Default: true. */
    private boolean retryOnFailure = true;

    /** Maximum number of retry attempts per failed LLM call (only when retryOnFailure=true). Default: 1. */
    private int maxRetries = 1;

    /**
     * Validates the loaded configuration at startup.
     * Resets invalid values to safe defaults and logs a warning for each.
     */
    @PostConstruct
    void validate() {
        if (batchSize <= 0) {
            log.warn("scraper.enrichment.batch-size={} is invalid, defaulting to 7", batchSize);
            batchSize = 7;
        }
        if (llmTimeoutSeconds <= 0) {
            log.warn("scraper.enrichment.llm-timeout-seconds={} is invalid, defaulting to 120",
                    llmTimeoutSeconds);
            llmTimeoutSeconds = 120;
        }
        if (maxRetries < 0) {
            log.warn("scraper.enrichment.max-retries={} is invalid, defaulting to 1", maxRetries);
            maxRetries = 1;
        }
        log.info("Enrichment config: mode={}, batchSize={}, llmTimeoutSeconds={}, "
                        + "retryOnFailure={}, maxRetries={}",
                mode, batchSize, llmTimeoutSeconds, retryOnFailure, maxRetries);
    }
}
