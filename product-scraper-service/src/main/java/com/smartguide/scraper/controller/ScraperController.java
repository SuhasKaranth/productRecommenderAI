package com.smartguide.scraper.controller;

import com.smartguide.scraper.dto.ScrapeJob;
import com.smartguide.scraper.dto.ScrapeJobResponse;
import com.smartguide.scraper.dto.TriggerScrapeRequest;
import com.smartguide.scraper.service.ScraperConfigLoader;
import com.smartguide.scraper.service.ScraperOrchestrationService;
import com.smartguide.scraper.service.ScrapeJobStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * REST API Controller for web scraping operations
 */
@RestController
@RequestMapping("/api/scraper")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Scraper", description = "Web scraping operations")
public class ScraperController {

    private final ScraperOrchestrationService orchestrationService;
    private final ScraperConfigLoader configLoader;
    private final com.smartguide.scraper.service.BasicScraperService basicScraperService;
    private final com.smartguide.scraper.service.EnhancedScraperService enhancedScraperService;
    private final ScrapeJobStore jobStore;

    /**
     * MVP1: Simple synchronous scraping endpoint
     * Accepts any URL and returns scraped text content
     */
    @PostMapping("/scrape-url")
    @Operation(summary = "Scrape any URL and extract text content (MVP1)")
    public ResponseEntity<?> scrapeUrl(@RequestBody com.smartguide.scraper.dto.ScrapeRequest request) {
        log.info("MVP1: Scraping URL: {}", request.getUrl());

        try {
            com.smartguide.scraper.dto.ScrapeResponse response = basicScraperService.scrapeUrl(request.getUrl());

            if ("error".equals(response.getStatus())) {
                return ResponseEntity.badRequest().body(response);
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error in MVP1 scraping", e);
            return ResponseEntity.internalServerError().body(
                com.smartguide.scraper.dto.ScrapeResponse.builder()
                    .url(request.getUrl())
                    .status("error")
                    .message("Internal server error: " + e.getMessage())
                    .build()
            );
        }
    }

    /**
     * MVP2: Enhanced scraping with AI analysis and product extraction
     * Scrapes URL, checks if page has products, extracts details, saves to staging
     */
    @PostMapping("/scrape-url-enhanced")
    @Operation(summary = "Scrape URL with AI analysis and extraction (MVP2)")
    public ResponseEntity<?> scrapeUrlEnhanced(@RequestBody com.smartguide.scraper.dto.ScrapeRequest request) {
        log.info("MVP2: Starting enhanced scraping for URL: {}", request.getUrl());

        try {
            com.smartguide.scraper.dto.ScrapeResponse response = enhancedScraperService.scrapeAndAnalyze(request.getUrl());

            if ("error".equals(response.getStatus())) {
                return ResponseEntity.badRequest().body(response);
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error in MVP2 enhanced scraping", e);
            return ResponseEntity.internalServerError().body(
                com.smartguide.scraper.dto.ScrapeResponse.builder()
                    .url(request.getUrl())
                    .status("error")
                    .message("Internal server error: " + e.getMessage())
                    .build()
            );
        }
    }

    @PostMapping("/trigger/{websiteId}")
    @Operation(summary = "Trigger scraping for a specific website")
    public ResponseEntity<ScrapeJobResponse> triggerScrape(@PathVariable String websiteId) {
        log.info("Received scrape request for website: {}", websiteId);

        // Execute scraping asynchronously
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
                orchestrationService.executeScrapingJob(websiteId)
        );

        // Return immediately with job ID
        try {
            String jobId = future.get();
            return ResponseEntity.ok(ScrapeJobResponse.builder()
                    .jobId(jobId)
                    .websiteId(websiteId)
                    .status("STARTED")
                    .message("Scraping job started successfully")
                    .build());
        } catch (Exception e) {
            log.error("Failed to start scraping job", e);
            return ResponseEntity.internalServerError()
                    .body(ScrapeJobResponse.builder()
                            .websiteId(websiteId)
                            .status("FAILED")
                            .message("Failed to start scraping: " + e.getMessage())
                            .build());
        }
    }

    @PostMapping("/trigger")
    @Operation(summary = "Trigger scraping with custom configuration")
    public ResponseEntity<ScrapeJobResponse> triggerScrapeWithConfig(
            @RequestBody TriggerScrapeRequest request) {
        return triggerScrape(request.getWebsiteId());
    }

    @GetMapping("/status/{jobId}")
    @Operation(summary = "Get status of a scraping job")
    public ResponseEntity<Object> getJobStatus(@PathVariable String jobId) {
        log.info("Fetching status for job: {}", jobId);
        Object status = orchestrationService.getJobStatus(jobId);
        return ResponseEntity.ok(status);
    }

    @GetMapping("/sources")
    @Operation(summary = "Get all configured scrape sources")
    public ResponseEntity<List<Map<String, Object>>> getAllSources() {
        List<Map<String, Object>> sources = orchestrationService.getAllScrapeSources();
        return ResponseEntity.ok(sources);
    }

    @GetMapping("/sources/{websiteId}/configs")
    @Operation(summary = "Get configuration for a specific website")
    public ResponseEntity<Object> getWebsiteConfig(@PathVariable String websiteId) {
        var config = configLoader.getConfig(websiteId);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(config);
    }

    @GetMapping("/history/{websiteId}")
    @Operation(summary = "Get scraping history for a website")
    public ResponseEntity<List<Map<String, Object>>> getWebsiteHistory(@PathVariable String websiteId) {
        List<Map<String, Object>> history = orchestrationService.getWebsiteScrapeHistory(websiteId);
        return ResponseEntity.ok(history);
    }

    @PostMapping("/configs/reload")
    @Operation(summary = "Reload all scraper configurations")
    public ResponseEntity<Map<String, Object>> reloadConfigs() {
        configLoader.reloadConfigs();
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Configurations reloaded successfully");
        response.put("count", configLoader.getAllConfigs().size());
        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // Async scrape-and-enrich endpoints (new — does not affect existing endpoints)
    // -------------------------------------------------------------------------

    /**
     * Starts an asynchronous scrape-and-enrich job.
     *
     * <p>Returns 202 Accepted immediately with a job ID.
     * The client should poll {@code GET /api/scraper/jobs/{jobId}/status} every 2 seconds
     * to observe progress.
     * Returns 409 Conflict when an active job for the same URL already exists.
     */
    @PostMapping("/scrape-and-enrich")
    @Operation(summary = "Start async scrape with AI enrichment (returns job ID immediately)",
               description = "Initiates a scrape-and-enrich job asynchronously. "
                       + "Poll GET /api/scraper/jobs/{jobId}/status for progress.")
    @ApiResponse(responseCode = "202", description = "Job started — jobId returned")
    @ApiResponse(responseCode = "409", description = "A job for this URL is already in progress")
    public ResponseEntity<?> scrapeAndEnrich(
            @RequestBody com.smartguide.scraper.dto.ScrapeRequest request) {

        log.info("Async scrape-and-enrich request for URL: {}", request.getUrl());

        // Reject duplicate in-flight jobs for the same URL
        ScrapeJob existingJob = jobStore.getActiveJobForUrl(request.getUrl());
        if (existingJob != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "jobId", existingJob.getJobId(),
                    "url", existingJob.getUrl(),
                    "status", "IN_PROGRESS",
                    "message", "A scrape job for this URL is already in progress"
            ));
        }

        ScrapeJob job = jobStore.createJob(request.getUrl());

        // Launch enrichment on a background thread — HTTP response returns immediately
        CompletableFuture.runAsync(() -> {
            try {
                enhancedScraperService.scrapeAndEnrichAsync(request.getUrl(), job);
            } catch (Exception e) {
                log.error("Unhandled error in async scrape-and-enrich job '{}': {}",
                        job.getJobId(), e.getMessage(), e);
                job.setError(e.getMessage());
                job.setStatus(ScrapeJob.Status.FAILED);
                job.setCompletedAt(java.time.LocalDateTime.now());
            }
        });

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "jobId", job.getJobId(),
                "url", request.getUrl(),
                "status", "STARTED",
                "message", "Scrape and enrich job started"
        ));
    }

    /**
     * Returns the current state of an async scrape-and-enrich job, including all
     * progress events emitted so far.
     *
     * <p>This is the new status endpoint for async enrichment jobs.
     * The existing {@code GET /api/scraper/status/{jobId}} endpoint is preserved unchanged
     * for the legacy orchestration flow.
     */
    @GetMapping("/jobs/{jobId}/status")
    @Operation(summary = "Get status and progress of a scrape-and-enrich job",
               description = "Poll this endpoint every 2 seconds while the job is IN_PROGRESS.")
    @ApiResponse(responseCode = "200", description = "Job state returned")
    @ApiResponse(responseCode = "404", description = "Job ID not found")
    public ResponseEntity<?> getEnrichmentJobStatus(@PathVariable String jobId) {
        log.debug("Polling status for enrichment job: {}", jobId);
        ScrapeJob job = jobStore.getJob(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(job);
    }

    /**
     * Requests cancellation of an in-progress scrape-and-enrich job.
     *
     * <p>Sets a cancellation flag that the enrichment loop checks before each batch.
     * Products already enriched are saved to staging; unprocessed cards are discarded.
     */
    @DeleteMapping("/jobs/{jobId}")
    @Operation(summary = "Cancel an in-progress scrape-and-enrich job",
               description = "Sets a cancellation flag. Already-enriched products are saved to staging.")
    @ApiResponse(responseCode = "200", description = "Cancellation requested")
    @ApiResponse(responseCode = "400", description = "Job is not active and cannot be cancelled")
    @ApiResponse(responseCode = "404", description = "Job ID not found")
    public ResponseEntity<?> cancelEnrichmentJob(@PathVariable String jobId) {
        log.info("Cancellation requested for enrichment job: {}", jobId);
        ScrapeJob job = jobStore.getJob(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        if (!job.isActive()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "jobId", jobId,
                    "status", job.getStatus().name(),
                    "message", "Job is not active and cannot be cancelled"
            ));
        }
        jobStore.requestCancellation(jobId);
        return ResponseEntity.ok(Map.of(
                "jobId", jobId,
                "status", "CANCELLING",
                "message", "Cancellation requested. Already-processed products will be saved."
        ));
    }

    @GetMapping("/health")
    @Operation(summary = "Health check endpoint")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "Product Scraper Service");
        return ResponseEntity.ok(health);
    }
}
