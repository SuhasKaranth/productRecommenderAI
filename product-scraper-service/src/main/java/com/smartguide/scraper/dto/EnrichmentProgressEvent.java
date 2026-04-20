package com.smartguide.scraper.dto;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents a single progress event emitted during a scrape-and-enrich job.
 *
 * <p>Events are accumulated in {@link ScrapeJob#getEvents()} and returned to the
 * frontend via the {@code GET /api/scraper/jobs/{jobId}/status} polling endpoint.
 *
 * <p>The {@code batchNumber}, {@code totalBatches}, {@code productsProcessed}, and
 * {@code products} fields are only populated during the {@link Phase#ENRICHING} phase;
 * they are {@code null} during {@link Phase#SCRAPING}, {@link Phase#SAVING}, and terminal
 * phases.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnrichmentProgressEvent {

    /** Pipeline phase at the time the event was emitted. */
    public enum Phase { SCRAPING, ENRICHING, SAVING, DONE, FAILED, CANCELLED }

    private LocalDateTime timestamp;
    private Phase phase;
    private String message;

    // Enrichment-specific (null during SCRAPING/SAVING/terminal phases)
    private Integer batchNumber;
    private Integer totalBatches;
    private Integer productsProcessed;

    /** Per-product results for the completed batch (null during non-ENRICHING phases). */
    private List<ProductResult> products;

    /**
     * Summary result for a single product within a batch progress event.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductResult {
        private String productName;
        private String category;
        private Double aiConfidence;
        private Integer keywordsCount;
        private boolean shariaFlag;
        /** Non-null when enrichment failed for this specific product. */
        private String error;
    }
}
