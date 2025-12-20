package com.smartguide.scraper.service;

import com.smartguide.scraper.dto.ExtractedProduct;
import com.smartguide.scraper.dto.ScrapeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * MVP2: Enhanced scraper with AI analysis
 * - Scrapes webpage
 * - AI checks if page has products
 * - Extracts product details
 * - Saves to staging_products table
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnhancedScraperService {

    private final BasicScraperService basicScraperService;
    private final AIProductExtractor aiProductExtractor;
    private final StagingProductService stagingProductService;

    /**
     * MVP2: Complete workflow - scrape, analyze, extract, save
     */
    public ScrapeResponse scrapeAndAnalyze(String url) {
        log.info("MVP2: Starting enhanced scraping for URL: {}", url);

        // Step 1: Scrape the page (MVP1)
        ScrapeResponse scrapeResult = basicScraperService.scrapeUrl(url);

        if (!"success".equals(scrapeResult.getStatus())) {
            return scrapeResult; // Return error from scraping
        }

        // Step 2: AI Analysis - Check if page has products
        log.info("MVP2: Analyzing if page contains products...");
        boolean hasProducts = aiProductExtractor.isProductPage(scrapeResult.getTextContent());

        if (!hasProducts) {
            log.info("MVP2: No products detected on page");
            scrapeResult.setMessage("Page scraped successfully, but no financial products were detected.");
            return scrapeResult;
        }

        // Step 3: Extract product details using AI
        log.info("MVP2: Products detected! Extracting details...");
        List<ExtractedProduct> products = aiProductExtractor.extractProducts(
            scrapeResult.getTextContent(),
            url
        );

        if (products.isEmpty()) {
            log.warn("MVP2: Product page detected but extraction failed");
            scrapeResult.setMessage("Product page detected, but failed to extract product details.");
            return scrapeResult;
        }

        // Step 4: Save to staging_products table
        log.info("MVP2: Saving {} products to staging...", products.size());
        int savedCount = stagingProductService.saveExtractedProducts(products, url);

        // Update response
        scrapeResult.setMessage(String.format(
            "Successfully extracted %d products and saved %d to staging for review!",
            products.size(), savedCount
        ));

        log.info("MVP2: Complete! Extracted {} products, saved {} to staging", products.size(), savedCount);
        return scrapeResult;
    }
}
