package com.smartguide.poc.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Product Entity - represents banking products in the system
 */
@Entity
@Table(name = "products")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_code", unique = true, nullable = false, length = 50)
    private String productCode;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "category", nullable = false, length = 100)
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

    @Column(name = "keywords", columnDefinition = "text[]")
    private List<String> keywords;

    @Column(name = "sharia_certified")
    private Boolean shariaCertified = true;

    @Column(name = "active")
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // Scraping metadata fields
    @Column(name = "source_website_id", length = 100)
    private String sourceWebsiteId;

    @Column(name = "source_url", columnDefinition = "TEXT")
    private String sourceUrl;

    @Column(name = "scraped_at")
    private LocalDateTime scrapedAt;

    @Column(name = "data_quality_score", precision = 3, scale = 2)
    private BigDecimal dataQualityScore;

    @Column(name = "raw_page_content", columnDefinition = "TEXT")
    private String rawPageContent;
}
