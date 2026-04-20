package com.smartguide.scraper.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO for AI-extracted product information
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedProduct {
    private String productName;
    private String description;
    private String category; // CREDIT_CARD, FINANCING, SAVINGS_ACCOUNT, etc.
    private String subCategory;

    private Boolean islamicProduct;
    private String islamicStructure; // Murabaha, Tawarruq, Musharakah, etc.

    private BigDecimal annualRate;
    private BigDecimal annualFee;
    private BigDecimal minIncome;
    private Integer minCreditScore;

    private List<String> keyBenefits;
    private List<String> eligibilityCriteria;

    private Double confidenceScore; // 0.0 to 1.0
    private String extractionReasoning;

    private String sourceUrl;
    private String pageContent; // Original page text for reference
    private String rawPageContent; // Verbatim page text used as primary LLM grounding source (up to 15000 chars)

    /**
     * Pre-populated review notes for the admin staging review workflow.
     *
     * <p>Populated by {@link com.smartguide.scraper.service.CardEnrichmentService} when:
     * <ul>
     *   <li>Sharia non-compliant terminology is detected in the product text.</li>
     *   <li>The LLM returns an invalid category.</li>
     *   <li>AI enrichment fails for the product's batch.</li>
     *   <li>The product's {@code sourceUrl} was missing and the listing URL was used as fallback.</li>
     * </ul>
     * Multiple notes are joined with {@code " | "}.
     */
    private String reviewNotes;
}
