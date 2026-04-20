package com.smartguide.scraper.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mutable state object tracking a single asynchronous scrape-and-enrich job.
 *
 * <p>Instances are created by {@link com.smartguide.scraper.service.ScrapeJobStore}
 * and mutated from a background thread while the HTTP layer polls via
 * {@code GET /api/scraper/jobs/{jobId}/status}.
 *
 * <p>Scalar counter fields ({@code totalProducts}, {@code totalBatches}, etc.) are
 * declared {@code volatile} so that reads from the polling thread always see the most
 * recent value written by the enrichment thread, without needing synchronisation blocks.
 * This is a POC-acceptable trade-off; production code would use AtomicInteger or a
 * dedicated progress object protected by a lock.
 *
 * <p>The {@code events} list is backed by {@link Collections#synchronizedList} so that
 * concurrent add (enrichment thread) and iterate (serialisation thread) are safe.
 */
@Data
@Builder
public class ScrapeJob {

    /** Lifecycle state of the job. */
    public enum Status { IN_PROGRESS, COMPLETED, FAILED, CANCELLED }

    private String jobId;
    private String url;
    private volatile Status status;
    private volatile EnrichmentProgressEvent.Phase phase;
    private LocalDateTime startedAt;
    private volatile LocalDateTime completedAt;
    private volatile String error;

    // --- Counters (volatile for cross-thread visibility) ---
    private volatile int totalProducts;
    private volatile int totalBatches;
    private volatile int completedBatches;
    private volatile int productsProcessed;
    private volatile int savedCount;
    private volatile int highConfidenceCount;
    private volatile int needsReviewCount;
    private volatile int enrichmentFailureCount;

    /**
     * Progress event log.
     * Backed by {@link Collections#synchronizedList} so the enrichment thread can
     * {@code add()} concurrently with the HTTP thread iterating for JSON serialisation.
     */
    @Builder.Default
    private List<EnrichmentProgressEvent> events =
            Collections.synchronizedList(new ArrayList<>());

    /** Cancellation flag. Set by the controller; checked by the enrichment loop. */
    private volatile boolean cancelled;

    /**
     * Returns {@code true} while the job is still running (i.e. status is {@link Status#IN_PROGRESS}).
     */
    public boolean isActive() {
        return status == Status.IN_PROGRESS;
    }

    /**
     * Appends a progress event to the event log.
     *
     * <p>Thread-safe: the underlying list is a {@link Collections#synchronizedList}.
     *
     * @param event the event to append; must not be null
     */
    public void addEvent(EnrichmentProgressEvent event) {
        events.add(event);
    }

    /**
     * Builds a human-readable one-line summary of the completed job.
     * Intended for the frontend completion card.
     *
     * @return summary string, e.g.
     *         {@code "21 products scraped. 18 categorised with high confidence. 3 flagged for manual review. 0 enrichment failures."}
     */
    public String buildSummary() {
        return String.format(
                "%d products scraped. %d categorised with high confidence. "
                        + "%d flagged for manual review. %d enrichment failures.",
                totalProducts, highConfidenceCount, needsReviewCount, enrichmentFailureCount);
    }
}
