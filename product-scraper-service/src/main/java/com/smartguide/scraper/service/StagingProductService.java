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

    /**
     * Save list of extracted products to staging table
     */
    public int saveExtractedProducts(List<ExtractedProduct> products, String sourceUrl) {
        int savedCount = 0;

        for (ExtractedProduct product : products) {
            try {
                saveToStaging(product, sourceUrl);
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
    private void saveToStaging(ExtractedProduct product, String sourceUrl) {
        Map<String, Object> stagingData = buildStagingRequest(product, sourceUrl);

        try {
            log.debug("Sending staging data: {}", stagingData);

            webClient.post()
                .uri(mainServiceUrl + "/api/admin/staging")
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
     * Build request body for staging API
     */
    private Map<String, Object> buildStagingRequest(ExtractedProduct product, String sourceUrl) {
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

        // AI metadata
        data.put("aiSuggestedCategory", product.getCategory());
        if (product.getConfidenceScore() != null) {
            data.put("aiConfidence", BigDecimal.valueOf(product.getConfidenceScore()));
        }

        // Source metadata
        data.put("sourceUrl", sourceUrl);
        data.put("approvalStatus", "PENDING");

        return data;
    }
}
