package com.smartguide.scraper.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM enrichment result for a single product card.
 *
 * <p>Produced by {@link com.smartguide.scraper.service.CardEnrichmentService} when
 * parsing the LLM response. Each instance is then merged into the corresponding
 * {@link ExtractedProduct} via {@code mergeEnrichment()}.
 *
 * <p>Note: keywords are intentionally excluded from enrichment. Keyword generation
 * remains a manual admin operation via the existing
 * {@code POST /api/admin/staging/{id}/generate-keywords} endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnrichedCard {

    /** Echoed back from the LLM for matching to the input product by name. */
    private String productName;

    /**
     * One of the 10 valid Islamic banking categories:
     * COVERED_CARDS, DEBIT_CARDS, CHARGE_CARDS, HOME_FINANCE, PERSONAL_FINANCE,
     * AUTO_FINANCE, TAKAFUL, SAVINGS, CURRENT_ACCOUNTS, INVESTMENTS.
     */
    private String category;

    /** LLM confidence in the category assignment, in the range 0.0–1.0. */
    private Double confidence;

    /** Islamic financing structure identified (Murabaha, Ijarah, Tawarruq, Wakala, Musharakah, etc.). Nullable. */
    private String islamicStructure;

    /** One-sentence description of the target customer segment. Nullable. */
    private String targetCustomer;

    /**
     * Non-compliant Sharia terms found in the product text by the LLM, as reported
     * in the {@code sharia_violations} array of the LLM response.
     * Empty list when the product text is Sharia-compliant.
     */
    private List<String> shariaViolations;
}
