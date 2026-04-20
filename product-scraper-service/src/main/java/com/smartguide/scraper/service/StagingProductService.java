package com.smartguide.scraper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartguide.scraper.dto.ExtractedProduct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service to save extracted products to staging_products table
 * Calls the main app's admin API
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StagingProductService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${app.main-service.url}")
    private String mainServiceUrl;

    @Value("${app.main-service.api-key}")
    private String mainServiceApiKey;

    /**
     * Save list of extracted products to staging table.
     *
     * @param products             products extracted by the LLM (each has sourceUrl already set to the detail URL)
     * @param listingUrl           the listing page URL (used only as fallback label — individual product.sourceUrl is used)
     * @param listingPageRawContent verbatim visible text of the listing page, preserved for provenance
     */
    public int saveExtractedProducts(List<ExtractedProduct> products,
                                     String listingUrl,
                                     String listingPageRawContent) {
        int savedCount = 0;

        for (ExtractedProduct product : products) {
            try {
                saveToStaging(product, listingUrl, listingPageRawContent);
                savedCount++;
            } catch (Exception e) {
                log.error("Failed to save product to staging: {}", product.getProductName(), e);
            }
        }

        return savedCount;
    }

    /**
     * Save single product to staging via main app API
     */
    private void saveToStaging(ExtractedProduct product,
                                String listingUrl,
                                String listingPageRawContent) {
        Map<String, Object> stagingData = buildStagingRequest(product, listingUrl, listingPageRawContent);

        try {
            log.debug("Sending staging data: {}", stagingData);

            webClient.post()
                .uri(mainServiceUrl + "/api/admin/staging")
                .header("X-API-Key", mainServiceApiKey)
                .bodyValue(stagingData)
                .retrieve()
                .bodyToMono(String.class)
                .block();

            log.info("Saved product to staging: {}", product.getProductName());
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            log.error("Error saving to staging - Status: {}, Response: {}, Request: {}",
                e.getStatusCode(),
                e.getResponseBodyAsString(),
                stagingData);
            throw e;
        } catch (Exception e) {
            log.error("Error saving to staging", e);
            throw e;
        }
    }

    /**
     * Build request body for staging API.
     * Uses {@code product.getSourceUrl()} (the per-product detail URL resolved by Phase 1 matching)
     * rather than the raw listing URL argument.
     */
    private Map<String, Object> buildStagingRequest(ExtractedProduct product,
                                                     String listingUrl,
                                                     String listingPageRawContent) {
        Map<String, Object> data = new HashMap<>();

        // Required fields
        data.put("productName", product.getProductName());
        data.put("description", product.getDescription());
        data.put("category", product.getCategory());

        // Optional fields
        if (product.getSubCategory() != null) {
            data.put("subCategory", product.getSubCategory());
        }
        if (product.getIslamicStructure() != null) {
            data.put("islamicStructure", product.getIslamicStructure());
        }
        if (product.getAnnualRate() != null) {
            data.put("annualRate", product.getAnnualRate());
        }
        if (product.getAnnualFee() != null) {
            data.put("annualFee", product.getAnnualFee());
        }
        if (product.getMinIncome() != null) {
            data.put("minIncome", product.getMinIncome());
        }
        if (product.getMinCreditScore() != null) {
            data.put("minCreditScore", product.getMinCreditScore());
        }
        if (product.getKeyBenefits() != null) {
            data.put("keyBenefits", product.getKeyBenefits());
        }
        if (product.getEligibilityCriteria() != null && !product.getEligibilityCriteria().isEmpty()) {
            // Convert List<String> to Map<String, Object> format
            Map<String, Object> eligibilityMap = new HashMap<>();
            eligibilityMap.put("criteria", product.getEligibilityCriteria());
            data.put("eligibilityCriteria", eligibilityMap);
        }
        if (product.getRawPageContent() != null && !product.getRawPageContent().isBlank()) {
            data.put("rawPageContent", product.getRawPageContent());
        }

        // AI metadata
        data.put("aiSuggestedCategory", product.getCategory());
        if (product.getConfidenceScore() != null) {
            data.put("aiConfidence", BigDecimal.valueOf(product.getConfidenceScore()));
        }

        // Review notes (Sharia flags, enrichment failures, invalid category warnings)
        if (product.getReviewNotes() != null && !product.getReviewNotes().isBlank()) {
            data.put("reviewNotes", product.getReviewNotes());
        }

        // Source metadata — use the per-product detail URL set by matchProductUrls() or enrichment.
        // Fall back to listing URL when sourceUrl is null or blank (safety net per spec section 5m).
        String sourceUrl = product.getSourceUrl();
        if (sourceUrl == null || sourceUrl.isBlank()) {
            sourceUrl = listingUrl;
        }
        data.put("sourceUrl", sourceUrl);
        data.put("approvalStatus", "PENDING");

        // Listing page provenance fields
        if (listingPageRawContent != null && !listingPageRawContent.isBlank()) {
            data.put("listingPageRawContent", listingPageRawContent);
        }
        data.put("rawContentSource", "LISTING_PAGE");

        return data;
    }
}
