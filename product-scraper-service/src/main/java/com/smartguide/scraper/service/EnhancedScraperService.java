package com.smartguide.scraper.service;

import com.smartguide.scraper.config.BankSourceConfig;
import com.smartguide.scraper.config.EnrichmentConfig;
import com.smartguide.scraper.config.ScraperProperties;
import com.smartguide.scraper.dto.AnchorPair;
import com.smartguide.scraper.dto.EnrichmentProgressEvent;
import com.smartguide.scraper.dto.ExtractedProduct;
import com.smartguide.scraper.dto.ScrapeJob;
import com.smartguide.scraper.dto.ScrapeResponse;
import com.smartguide.scraper.dto.StructuredCard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
    private final ScraperProperties scraperProperties;
    private final CardEnrichmentService cardEnrichmentService;
    private final EnrichmentConfig enrichmentConfig;

    /**
     * Complete workflow: scrape, analyse, extract, and save products to staging.
     *
     * <p>When a {@link BankSourceConfig} matches the URL, the configured CSS-selector flow is used
     * to extract product cards directly from the DOM — bypassing the LLM discovery step entirely.
     * If the configured extraction returns zero cards, the method falls back to the generic
     * LLM-based flow automatically.
     *
     * <p>For URLs with no matching bank config, the existing generic flow runs unchanged.
     */
    public ScrapeResponse scrapeAndAnalyze(String url) {
        log.info("MVP2: Starting enhanced scraping for URL: {}", url);

        // --- Configured-bank flow ---
        Optional<BankSourceConfig> bankConfig = scraperProperties.findConfigForUrl(url);
        if (bankConfig.isPresent()) {
            BankSourceConfig config = bankConfig.get();
            log.info("Found bank-source config for URL '{}' (bank='{}')", url, config.bank());

            ScrapeResponse configuredResult = basicScraperService.scrapeUrlWithConfig(url, config);

            if (!"success".equals(configuredResult.getStatus())) {
                return configuredResult;
            }

            List<StructuredCard> structuredCards = configuredResult.getStructuredCards();
            if (!structuredCards.isEmpty()) {
                // CSS selector extraction succeeded — convert and skip LLM discovery
                List<ExtractedProduct> products = convertStructuredCards(structuredCards);
                List<ExtractedProduct> deduplicated = deduplicateByName(products);

                // AI enrichment before staging save (null callback, never-cancelled for sync path)
                log.info("Enriching {} product(s) with AI for bank='{}'",
                        deduplicated.size(), config.bank());
                cardEnrichmentService.enrich(deduplicated, null, () -> false);

                log.info("Configured extraction: saving {} enriched product(s) to staging for bank='{}'",
                        deduplicated.size(), config.bank());
                int savedCount = stagingProductService.saveExtractedProducts(
                        deduplicated, url, configuredResult.getTextContent());

                configuredResult.setMessage(String.format(
                        "Configured extraction: %d products extracted via CSS selectors, %d saved to staging",
                        deduplicated.size(), savedCount));
                log.info("Configured extraction complete for bank='{}': {} saved to staging",
                        config.bank(), savedCount);
                return configuredResult;
            }

            // Zero cards from configured extraction — fall through to generic flow
            log.warn("Configured extraction returned 0 cards for '{}' — falling back to generic flow", url);
            // Re-use the scrape result (textContent + anchorPairs) already fetched above
            return runGenericFlow(url, configuredResult);
        }

        // --- Generic flow (no matching config) ---
        ScrapeResponse scrapeResult = basicScraperService.scrapeUrl(url);
        return runGenericFlow(url, scrapeResult);
    }

    /**
     * Generic LLM-based flow: check if the page has products, extract them, deduplicate,
     * match detail URLs from DOM anchors, and save to staging.
     *
     * <p>This method is also invoked as the fallback when configured extraction yields zero cards.
     *
     * @param url         the listing URL (used for URL matching and as fallback source URL)
     * @param scrapeResult a completed {@link ScrapeResponse} with {@code textContent} populated
     * @return the same {@link ScrapeResponse} with its {@code message} field updated
     */
    private ScrapeResponse runGenericFlow(String url, ScrapeResponse scrapeResult) {
        if (!"success".equals(scrapeResult.getStatus())) {
            return scrapeResult;
        }

        log.info("MVP2: Analyzing if page contains products...");
        boolean hasProducts = aiProductExtractor.isProductPage(scrapeResult.getTextContent());

        if (!hasProducts) {
            log.info("MVP2: No products detected on page");
            scrapeResult.setMessage("Page scraped successfully, but no financial products were detected.");
            return scrapeResult;
        }

        // DOM hints are computed for URL matching but NOT passed to the LLM.
        // Passing hints caused the LLM to extract products from the site navigation bar.
        List<String> productNameHints = extractProductNameHints(scrapeResult.getAnchorPairs(), url);
        log.info("MVP2: Products detected! Extracting details from page text ({} DOM hints available for URL matching)...",
                productNameHints.size());
        List<ExtractedProduct> products = aiProductExtractor.extractProducts(
                scrapeResult.getTextContent(),
                url
        );

        if (products.isEmpty()) {
            log.warn("MVP2: Product page detected but extraction failed");
            scrapeResult.setMessage("Product page detected, but failed to extract product details.");
            return scrapeResult;
        }

        List<ExtractedProduct> deduplicated = deduplicateByName(products);
        matchProductUrls(deduplicated, scrapeResult.getAnchorPairs(), url);

        log.info("MVP2: Saving {} products to staging...", deduplicated.size());
        int savedCount = stagingProductService.saveExtractedProducts(
                deduplicated, url, scrapeResult.getTextContent());

        scrapeResult.setMessage(String.format(
                "Successfully extracted %d products and saved %d to staging for review!",
                deduplicated.size(), savedCount
        ));
        log.info("MVP2: Complete! Extracted {} products, saved {} to staging", deduplicated.size(), savedCount);
        return scrapeResult;
    }

    /**
     * Convert a list of DOM-extracted {@link StructuredCard} objects into {@link ExtractedProduct}
     * instances ready to be saved to staging.
     *
     * <p>The {@code sourceUrl} is set to the card's {@code href}. Other LLM-derived fields
     * (category, profit rate, eligibility, etc.) are left null and will be populated during the
     * admin review step.
     */
    List<ExtractedProduct> convertStructuredCards(List<StructuredCard> cards) {
        return cards.stream().map(card -> ExtractedProduct.builder()
                .productName(card.name())
                .sourceUrl(card.href())
                .description(card.description() != null ? card.description() : "")
                .keyBenefits(card.benefits())
                .build()
        ).collect(Collectors.toList());
    }

    /**
     * Extract product name hints from DOM anchor pairs whose hrefs are sub-paths of the listing URL.
     * For example, if the listing URL is {@code https://dib.ae/personal/cards}, only anchors whose
     * href starts with {@code https://dib.ae/personal/cards/} are considered product page links.
     * Returns distinct, non-blank anchor texts in encounter order.
     */
    List<String> extractProductNameHints(List<AnchorPair> anchorPairs, String listingUrl) {
        // Strip query string to get the bare listing path
        String basePath = listingUrl.contains("?")
                ? listingUrl.substring(0, listingUrl.indexOf('?'))
                : listingUrl;
        String prefix = basePath.endsWith("/") ? basePath : basePath + "/";

        List<String> hints = anchorPairs.stream()
                .filter(a -> a.href().startsWith(prefix))
                .map(AnchorPair::text)
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .collect(Collectors.toList());

        log.info("Extracted {} product name hint(s) from DOM anchors (prefix={})", hints.size(), prefix);
        return hints;
    }

    /**
     * Remove duplicate products by normalised product name, keeping the first occurrence.
     * Guards against LLM looping behaviour where the same product is emitted multiple times.
     */
    List<ExtractedProduct> deduplicateByName(List<ExtractedProduct> products) {
        Set<String> seen = new LinkedHashSet<>();
        List<ExtractedProduct> unique = new ArrayList<>();
        for (ExtractedProduct p : products) {
            String key = normalize(p.getProductName());
            if (!key.isEmpty() && seen.add(key)) {
                unique.add(p);
            }
        }
        int duplicates = products.size() - unique.size();
        if (duplicates > 0) {
            log.warn("Removed {} duplicate product(s) from LLM response (kept {} unique)",
                    duplicates, unique.size());
        }
        return unique;
    }

    /**
     * For each extracted product, find the best-matching anchor from the DOM list by normalising
     * both the product name and anchor text (lowercase, punctuation stripped, whitespace collapsed)
     * and applying a bidirectional {@code contains} check. Sets {@code product.sourceUrl} to the
     * matched href, or falls back to {@code listingUrl} with a warning log when no match is found.
     */
    void matchProductUrls(List<ExtractedProduct> products,
                                  List<AnchorPair> anchorPairs,
                                  String listingUrl) {
        for (ExtractedProduct product : products) {
            String normalizedName = normalize(product.getProductName());
            String matchedUrl = anchorPairs.stream()
                    .filter(a -> {
                        String normalizedText = normalize(a.text());
                        return !normalizedText.isEmpty()
                                && (normalizedText.contains(normalizedName)
                                    || normalizedName.contains(normalizedText));
                    })
                    .map(AnchorPair::href)
                    .findFirst()
                    .orElse(listingUrl);

            product.setSourceUrl(matchedUrl);

            if (matchedUrl.equals(listingUrl)) {
                log.warn("No detail URL matched for product '{}' — using listing URL as fallback",
                        product.getProductName());
            } else {
                log.info("Matched product '{}' -> {}", product.getProductName(), matchedUrl);
            }
        }
    }

    /** Normalise a string for anchor-to-product-name matching. */
    private String normalize(String input) {
        return input == null ? "" : input.toLowerCase()
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // -------------------------------------------------------------------------
    // Async scrape-and-enrich flow (new endpoint)
    // -------------------------------------------------------------------------

    /**
     * Asynchronous scrape-and-enrich flow with per-batch progress tracking.
     *
     * <p>This method runs on a background thread launched by
     * {@link com.smartguide.scraper.controller.ScraperController}. It follows the same
     * logic as {@link #scrapeAndAnalyze} for the configured-bank path but:
     * <ul>
     *   <li>Emits {@link EnrichmentProgressEvent} objects into the {@link ScrapeJob} event log
     *       after each enrichment batch so the polling client sees live progress.</li>
     *   <li>Checks the job's cancellation flag between batches and stops gracefully.</li>
     *   <li>Updates the job's status, phase, counters, and completion timestamp on exit.</li>
     * </ul>
     *
     * <p>For URLs with no matching bank config, or when the configured extraction returns
     * zero cards, the method delegates to {@link #runGenericFlow} (which already enriches
     * via {@link AIProductExtractor}) and marks the job complete.
     *
     * @param url the listing URL to scrape
     * @param job the {@link ScrapeJob} tracking object (mutated by this method)
     */
    public void scrapeAndEnrichAsync(String url, ScrapeJob job) {
        try {
            Optional<BankSourceConfig> bankConfig = scraperProperties.findConfigForUrl(url);

            if (bankConfig.isPresent()) {
                BankSourceConfig config = bankConfig.get();
                log.info("Async enrichment: found bank config for URL '{}' (bank='{}')",
                        url, config.bank());

                ScrapeResponse configuredResult =
                        basicScraperService.scrapeUrlWithConfig(url, config);

                if (!"success".equals(configuredResult.getStatus())) {
                    failJob(job, configuredResult.getMessage());
                    return;
                }

                List<StructuredCard> structuredCards = configuredResult.getStructuredCards();

                if (structuredCards.isEmpty()) {
                    emitScrapingEvent(job,
                            "Configured extraction returned 0 cards. Falling back to generic LLM flow.");
                    ScrapeResponse fallbackResult = runGenericFlow(url, configuredResult);
                    completeJobFromSyncResult(job, fallbackResult);
                    return;
                }

                emitScrapingEvent(job, "Found " + structuredCards.size()
                        + " product cards via CSS selectors.");
                job.setPhase(EnrichmentProgressEvent.Phase.ENRICHING);

                List<ExtractedProduct> products = convertStructuredCards(structuredCards);
                List<ExtractedProduct> deduplicated = deduplicateByName(products);

                int batchSize = enrichmentConfig.getBatchSize();
                job.setTotalProducts(deduplicated.size());
                job.setTotalBatches((int) Math.ceil((double) deduplicated.size() / batchSize));

                Consumer<EnrichmentProgressEvent> callback = event -> {
                    job.addEvent(event);
                    if (event.getBatchNumber() != null) {
                        job.setCompletedBatches(event.getBatchNumber());
                    }
                    if (event.getProductsProcessed() != null) {
                        job.setProductsProcessed(event.getProductsProcessed());
                    }
                };

                cardEnrichmentService.enrich(deduplicated, callback, job::isCancelled);

                // Save to staging
                job.setPhase(EnrichmentProgressEvent.Phase.SAVING);
                emitSavingEvent(job, "Saving " + deduplicated.size()
                        + " enriched products to staging...");

                int savedCount = stagingProductService.saveExtractedProducts(
                        deduplicated, url, configuredResult.getTextContent());

                job.setSavedCount(savedCount);
                computeSummaryCounts(job, deduplicated);

                emitSavingEvent(job, "Complete.");
                job.setPhase(EnrichmentProgressEvent.Phase.DONE);
                job.setStatus(job.isCancelled()
                        ? ScrapeJob.Status.CANCELLED : ScrapeJob.Status.COMPLETED);
                job.setCompletedAt(LocalDateTime.now());

                log.info("Async enrichment complete for '{}': {} saved, {} total products",
                        url, savedCount, deduplicated.size());

            } else {
                // No configured bank — use generic LLM flow
                log.info("Async enrichment: no bank config for '{}', using generic LLM flow", url);
                emitScrapingEvent(job, "No configured bank selectors for this URL. "
                        + "Using generic LLM extraction flow.");

                ScrapeResponse scrapeResult = basicScraperService.scrapeUrl(url);
                ScrapeResponse result = runGenericFlow(url, scrapeResult);
                completeJobFromSyncResult(job, result);
            }

        } catch (Exception e) {
            log.error("Async enrichment failed for URL '{}': {}", url, e.getMessage(), e);
            failJob(job, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Async job helpers
    // -------------------------------------------------------------------------

    private void failJob(ScrapeJob job, String errorMessage) {
        job.setError(errorMessage);
        job.setStatus(ScrapeJob.Status.FAILED);
        job.setPhase(EnrichmentProgressEvent.Phase.FAILED);
        job.setCompletedAt(LocalDateTime.now());
        job.addEvent(EnrichmentProgressEvent.builder()
                .timestamp(LocalDateTime.now())
                .phase(EnrichmentProgressEvent.Phase.FAILED)
                .message("Job failed: " + errorMessage)
                .build());
    }

    private void completeJobFromSyncResult(ScrapeJob job, ScrapeResponse result) {
        job.setPhase(EnrichmentProgressEvent.Phase.DONE);
        job.setStatus("error".equals(result.getStatus())
                ? ScrapeJob.Status.FAILED : ScrapeJob.Status.COMPLETED);
        if ("error".equals(result.getStatus())) {
            job.setError(result.getMessage());
        }
        job.setCompletedAt(LocalDateTime.now());
    }

    private void emitScrapingEvent(ScrapeJob job, String message) {
        job.addEvent(EnrichmentProgressEvent.builder()
                .timestamp(LocalDateTime.now())
                .phase(EnrichmentProgressEvent.Phase.SCRAPING)
                .message(message)
                .build());
    }

    private void emitSavingEvent(ScrapeJob job, String message) {
        job.addEvent(EnrichmentProgressEvent.builder()
                .timestamp(LocalDateTime.now())
                .phase(EnrichmentProgressEvent.Phase.SAVING)
                .message(message)
                .build());
    }

    /**
     * Computes the high-confidence, needs-review, and enrichment-failure counts
     * from the fully-enriched product list and stores them on the job.
     */
    private void computeSummaryCounts(ScrapeJob job, List<ExtractedProduct> products) {
        int highConfidence = 0;
        int needsReview = 0;
        int failures = 0;

        for (ExtractedProduct p : products) {
            Double score = p.getConfidenceScore();
            if (score == null || (score == 0.0 && p.getCategory() == null)) {
                failures++;
            } else if (score >= 0.85) {
                highConfidence++;
            } else {
                needsReview++;
            }
        }

        job.setHighConfidenceCount(highConfidence);
        job.setNeedsReviewCount(needsReview);
        job.setEnrichmentFailureCount(failures);
    }
}
