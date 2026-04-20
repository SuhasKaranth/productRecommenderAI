package com.smartguide.poc.admin.dto;

import com.smartguide.poc.entity.StagingProduct;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StagingProductDTO {
    private Long id;
    private String productCode;
    private String productName;
    private String category;
    private String subCategory;
    private String description;
    private String islamicStructure;
    private BigDecimal annualRate;
    private BigDecimal annualFee;
    private BigDecimal minIncome;
    private Integer minCreditScore;
    private Map<String, Object> eligibilityCriteria;
    private List<String> keyBenefits;
    private Boolean shariaCertified;
    private Boolean active;

    // Scraping metadata
    private String sourceWebsiteId;
    private String sourceUrl;
    private LocalDateTime scrapedAt;
    private BigDecimal dataQualityScore;

    // Staging metadata
    private Long scrapeLogId;
    private String approvalStatus;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private String reviewNotes;

    // AI categorization
    private String aiSuggestedCategory;
    private BigDecimal aiConfidence;
    private Map<String, Object> aiCategorizationJson;

    // Keywords
    private List<String> keywords;

    // Raw page content for LLM grounding
    private String rawPageContent;

    // Listing page provenance (Phase 1)
    private String listingPageRawContent;
    private String rawContentSource;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static StagingProductDTO fromEntity(StagingProduct entity) {
        return StagingProductDTO.builder()
                .id(entity.getId())
                .productCode(entity.getProductCode())
                .productName(entity.getProductName())
                .category(entity.getCategory())
                .subCategory(entity.getSubCategory())
                .description(entity.getDescription())
                .islamicStructure(entity.getIslamicStructure())
                .annualRate(entity.getAnnualRate())
                .annualFee(entity.getAnnualFee())
                .minIncome(entity.getMinIncome())
                .minCreditScore(entity.getMinCreditScore())
                .eligibilityCriteria(entity.getEligibilityCriteria())
                .keyBenefits(entity.getKeyBenefits())
                .shariaCertified(entity.getShariaCertified())
                .active(entity.getActive())
                .sourceWebsiteId(entity.getSourceWebsiteId())
                .sourceUrl(entity.getSourceUrl())
                .scrapedAt(entity.getScrapedAt())
                .dataQualityScore(entity.getDataQualityScore())
                .scrapeLogId(entity.getScrapeLog() != null ? entity.getScrapeLog().getId() : null)
                .approvalStatus(entity.getApprovalStatus() != null ? entity.getApprovalStatus().name() : null)
                .reviewedBy(entity.getReviewedBy())
                .reviewedAt(entity.getReviewedAt())
                .reviewNotes(entity.getReviewNotes())
                .aiSuggestedCategory(entity.getAiSuggestedCategory())
                .aiConfidence(entity.getAiConfidence())
                .aiCategorizationJson(entity.getAiCategorizationJson())
                .keywords(entity.getKeywords())
                .rawPageContent(entity.getRawPageContent())
                .listingPageRawContent(entity.getListingPageRawContent())
                .rawContentSource(entity.getRawContentSource())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public static StagingProduct toEntity(StagingProductDTO dto) {
        StagingProduct entity = new StagingProduct();
        entity.setId(dto.getId());
        entity.setProductCode(dto.getProductCode());
        entity.setProductName(dto.getProductName());
        entity.setCategory(dto.getCategory());
        entity.setSubCategory(dto.getSubCategory());
        entity.setDescription(dto.getDescription());
        entity.setIslamicStructure(dto.getIslamicStructure());
        entity.setAnnualRate(dto.getAnnualRate());
        entity.setAnnualFee(dto.getAnnualFee());
        entity.setMinIncome(dto.getMinIncome());
        entity.setMinCreditScore(dto.getMinCreditScore());
        entity.setEligibilityCriteria(dto.getEligibilityCriteria());
        entity.setKeyBenefits(dto.getKeyBenefits());
        entity.setShariaCertified(dto.getShariaCertified());
        entity.setActive(dto.getActive() != null ? dto.getActive() : true);
        entity.setSourceWebsiteId(dto.getSourceWebsiteId());
        entity.setSourceUrl(dto.getSourceUrl());
        entity.setScrapedAt(dto.getScrapedAt() != null ? dto.getScrapedAt() : LocalDateTime.now());
        entity.setDataQualityScore(dto.getDataQualityScore());
        entity.setReviewedBy(dto.getReviewedBy());
        entity.setReviewedAt(dto.getReviewedAt());
        entity.setReviewNotes(dto.getReviewNotes());
        entity.setAiSuggestedCategory(dto.getAiSuggestedCategory());
        entity.setAiConfidence(dto.getAiConfidence());
        entity.setAiCategorizationJson(dto.getAiCategorizationJson());
        entity.setKeywords(dto.getKeywords());
        entity.setRawPageContent(dto.getRawPageContent());
        entity.setListingPageRawContent(dto.getListingPageRawContent());
        entity.setRawContentSource(dto.getRawContentSource());
        return entity;
    }
}
