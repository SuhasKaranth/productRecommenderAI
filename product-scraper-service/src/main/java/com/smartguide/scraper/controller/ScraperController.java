package com.smartguide.scraper.controller;

import com.smartguide.scraper.dto.ScrapeJobResponse;
import com.smartguide.scraper.dto.TriggerScrapeRequest;
import com.smartguide.scraper.service.ScraperConfigLoader;
import com.smartguide.scraper.service.ScraperOrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @GetMapping("/health")
    @Operation(summary = "Health check endpoint")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "Product Scraper Service");
        return ResponseEntity.ok(health);
    }
}
