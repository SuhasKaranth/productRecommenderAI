package com.smartguide.poc.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * StagingProduct Entity - Products pending review before production
 */
@Entity
@Table(name = "staging_products")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StagingProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Product data fields (product_code nullable until approval)
    @Column(name = "product_code", length = 50)
    private String productCode;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "category", length = 100)
    private String category;

    @Column(name = "sub_category", length = 100)
    private String subCategory;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "islamic_structure", length = 50)
    private String islamicStructure;

    @Column(name = "annual_rate", precision = 5, scale = 2)
    private BigDecimal annualRate;

    @Column(name = "annual_fee", precision = 10, scale = 2)
    private BigDecimal annualFee;

    @Column(name = "min_income", precision = 12, scale = 2)
    private BigDecimal minIncome;

    @Column(name = "min_credit_score")
    private Integer minCreditScore;

    @Column(name = "eligibility_criteria", columnDefinition = "jsonb")
    @Type(JsonType.class)
    private Map<String, Object> eligibilityCriteria;

    @Column(name = "key_benefits", columnDefinition = "jsonb")
    @Type(JsonType.class)
    private List<String> keyBenefits;

    @Column(name = "sharia_certified")
    private Boolean shariaCertified = true;

    @Column(name = "active")
    private Boolean active = true;

    // Scraping metadata
    @Column(name = "source_website_id", length = 100)
    private String sourceWebsiteId;

    @Column(name = "source_url", columnDefinition = "TEXT")
    private String sourceUrl;

    @Column(name = "scraped_at")
    private LocalDateTime scrapedAt;

    @Column(name = "data_quality_score", precision = 3, scale = 2)
    private BigDecimal dataQualityScore;

    @Column(name = "raw_html", columnDefinition = "TEXT")
    private String rawHtml;

    @Column(name = "raw_page_content", columnDefinition = "TEXT")
    private String rawPageContent;

    @Column(name = "listing_page_raw_content", columnDefinition = "TEXT")
    private String listingPageRawContent;

    @Column(name = "raw_content_source", length = 20)
    private String rawContentSource;

    // Staging/approval metadata
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scrape_log_id")
    private ScrapeLog scrapeLog;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", length = 20)
    private ApprovalStatus approvalStatus = ApprovalStatus.PENDING;

    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_notes", columnDefinition = "TEXT")
    private String reviewNotes;

    // AI categorization
    @Column(name = "ai_suggested_category", length = 100)
    private String aiSuggestedCategory;

    @Column(name = "ai_confidence", precision = 3, scale = 2)
    private BigDecimal aiConfidence;

    @Column(name = "ai_categorization_json", columnDefinition = "jsonb")
    @Type(JsonType.class)
    private Map<String, Object> aiCategorizationJson;

    // Keywords for search optimization
    @Column(name = "keywords", columnDefinition = "text[]")
    private List<String> keywords;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum ApprovalStatus {
        PENDING,
        APPROVED,
        REJECTED
    }
}
