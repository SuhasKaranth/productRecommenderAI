package com.smartguide.scraper.service;

import com.smartguide.scraper.model.ScraperConfig;
import com.smartguide.scraper.model.ScrapedProduct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Main orchestration service for web scraping workflow
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ScraperOrchestrationService {

    private final ScraperConfigLoader configLoader;
    private final PlaywrightScraperEngine scraperEngine;
    private final LLMDataEnricher llmEnricher;
    private final ScraperDatabaseService databaseService;

    /**
     * Execute scraping job for a specific website
     */
    public String executeScrapingJob(String websiteId) {
        String jobId = UUID.randomUUID().toString();
        log.info("Starting scraping job {} for website: {}", jobId, websiteId);

        try {
            // Load configuration
            ScraperConfig config = configLoader.getConfig(websiteId);
            if (config == null) {
                throw new IllegalArgumentException("No configuration found for website: " + websiteId);
            }

            // Initialize scrape log
            databaseService.initializeScrapeLog(jobId, websiteId);

            // Scrape website
            log.info("Starting web scraping for: {}", config.getWebsiteName());
            List<ScrapedProduct> scrapedProducts = scraperEngine.scrapeWebsite(config);

            log.info("Scraped {} products, starting enrichment", scrapedProducts.size());

            // Enrich with AI if enabled
            if (config.getOptions().getAiEnrichment() != null && config.getOptions().getAiEnrichment()) {
                for (ScrapedProduct product : scrapedProducts) {
                    try {
                        llmEnricher.enrichProduct(product);
                    } catch (Exception e) {
                        log.error("Failed to enrich product: {}", product.getProductName(), e);
                    }
                }
            } else {
                // Calculate quality scores even without AI enrichment
                for (ScrapedProduct product : scrapedProducts) {
                    product.setDataQualityScore(llmEnricher.calculateQualityScore(product));
                }
            }

            // Save products to staging database
            log.info("Saving products to staging table");
            int savedCount = databaseService.saveProducts(scrapedProducts, LocalDateTime.now(), jobId);

            // Update scrape log
            databaseService.completeScrapeLog(jobId, scrapedProducts.size(), savedCount, null);
            databaseService.updateLastScrapedTime(websiteId, LocalDateTime.now());

            log.info("Scraping job {} completed successfully. Saved {}/{} products",
                    jobId, savedCount, scrapedProducts.size());

            return jobId;

        } catch (Exception e) {
            log.error("Scraping job {} failed", jobId, e);
            databaseService.completeScrapeLog(jobId, 0, 0, e.getMessage());
            throw new RuntimeException("Scraping job failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get scraping job status
     */
    public Object getJobStatus(String jobId) {
        return databaseService.getScrapeLog(jobId);
    }

    /**
     * Get all configured scrape sources
     */
    public List<Map<String, Object>> getAllScrapeSources() {
        return databaseService.getAllScrapeSources();
    }

    /**
     * Get scrape history for a website
     */
    public List<Map<String, Object>> getWebsiteScrapeHistory(String websiteId) {
        return databaseService.getScrapeHistory(websiteId);
    }
}
