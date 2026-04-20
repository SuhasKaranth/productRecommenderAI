package com.smartguide.scraper.service;

import com.smartguide.scraper.dto.EnrichmentProgressEvent;
import com.smartguide.scraper.dto.ScrapeJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for tracking active and recently completed scrape-and-enrich jobs.
 *
 * <p>Uses two {@link ConcurrentHashMap} instances:
 * <ul>
 *   <li>{@code jobs} — maps job ID to its {@link ScrapeJob} state object.</li>
 *   <li>{@code urlToActiveJobId} — maps a normalised URL to the job ID of the currently
 *       active job for that URL, enabling duplicate-in-flight detection.</li>
 * </ul>
 *
 * <p>Completed jobs are retained for 2 hours before eviction. The store never holds more
 * than {@link #MAX_COMPLETED_JOBS} + 10 completed jobs at any one time.
 */
@Slf4j
@Component
public class ScrapeJobStore {

    private final Map<String, ScrapeJob> jobs = new ConcurrentHashMap<>();

    /** Maps normalised URL -> active job ID. Cleared when a job completes or is cancelled. */
    private final Map<String, String> urlToActiveJobId = new ConcurrentHashMap<>();

    /** Maximum number of completed jobs to retain in memory (FIFO eviction). */
    private static final int MAX_COMPLETED_JOBS = 50;

    /**
     * Creates a new job for the given URL.
     *
     * <p>Returns {@code null} when an active job for the same URL already exists
     * (duplicate detection). The caller (controller) must respond with 409 Conflict in that case.
     *
     * @param url the URL to scrape; used for duplicate detection after normalisation
     * @return the newly created {@link ScrapeJob}, or {@code null} if a job for this URL
     *         is already in progress
     */
    public ScrapeJob createJob(String url) {
        String normalized = normalizeUrl(url);
        String existingJobId = urlToActiveJobId.get(normalized);
        if (existingJobId != null) {
            ScrapeJob existing = jobs.get(existingJobId);
            if (existing != null && existing.isActive()) {
                log.warn("Duplicate scrape request for URL '{}' — job '{}' already in progress",
                        url, existingJobId);
                return null;
            }
        }

        String jobId = generateJobId();
        ScrapeJob job = ScrapeJob.builder()
                .jobId(jobId)
                .url(url)
                .status(ScrapeJob.Status.IN_PROGRESS)
                .phase(EnrichmentProgressEvent.Phase.SCRAPING)
                .startedAt(LocalDateTime.now())
                .events(Collections.synchronizedList(new ArrayList<>()))
                .cancelled(false)
                .build();

        jobs.put(jobId, job);
        urlToActiveJobId.put(normalized, jobId);
        evictOldJobs();

        log.info("Created scrape job '{}' for URL '{}'", jobId, url);
        return job;
    }

    /**
     * Returns the job with the given ID, or {@code null} if no such job exists.
     *
     * @param jobId the job identifier returned by {@link #createJob}
     */
    public ScrapeJob getJob(String jobId) {
        return jobs.get(jobId);
    }

    /**
     * Returns the currently active job for the given URL, or {@code null} if none exists.
     *
     * @param url the URL whose active job is requested
     */
    public ScrapeJob getActiveJobForUrl(String url) {
        String jobId = urlToActiveJobId.get(normalizeUrl(url));
        if (jobId == null) {
            return null;
        }
        ScrapeJob job = jobs.get(jobId);
        return (job != null && job.isActive()) ? job : null;
    }

    /**
     * Appends a progress event to the specified job's event log.
     *
     * @param jobId the target job ID
     * @param event the event to append
     */
    public void addEvent(String jobId, EnrichmentProgressEvent event) {
        ScrapeJob job = jobs.get(jobId);
        if (job != null) {
            job.addEvent(event);
        }
    }

    /**
     * Marks a job as completed (or failed/cancelled) and removes it from the active URL index
     * so a new job can be started for the same URL.
     *
     * @param jobId       the job to complete
     * @param finalStatus the terminal status to set
     */
    public void completeJob(String jobId, ScrapeJob.Status finalStatus) {
        ScrapeJob job = jobs.get(jobId);
        if (job != null) {
            job.setStatus(finalStatus);
            job.setCompletedAt(LocalDateTime.now());
            urlToActiveJobId.remove(normalizeUrl(job.getUrl()));
            log.info("Job '{}' completed with status={}", jobId, finalStatus);
        }
    }

    /**
     * Sets the cancellation flag on an active job.
     * The enrichment loop checks this flag before processing each batch.
     *
     * @param jobId the job to cancel
     */
    public void requestCancellation(String jobId) {
        ScrapeJob job = jobs.get(jobId);
        if (job != null && job.isActive()) {
            job.setCancelled(true);
            log.info("Cancellation requested for job '{}'", jobId);
        }
    }

    // --- private helpers ---

    private String generateJobId() {
        return "enrich-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().substring(0, 6);
    }

    /** Strips trailing slashes and lowercases for reliable URL deduplication. */
    private String normalizeUrl(String url) {
        return url != null ? url.replaceAll("/+$", "").toLowerCase() : "";
    }

    /**
     * Evicts completed jobs that are older than 2 hours when the total job count
     * exceeds the retention threshold. This prevents unbounded memory growth for
     * long-running instances.
     */
    private void evictOldJobs() {
        if (jobs.size() > MAX_COMPLETED_JOBS + 10) {
            jobs.entrySet().removeIf(e ->
                    !e.getValue().isActive()
                    && e.getValue().getCompletedAt() != null
                    && e.getValue().getCompletedAt()
                            .isBefore(LocalDateTime.now().minusHours(2)));
        }
    }
}
