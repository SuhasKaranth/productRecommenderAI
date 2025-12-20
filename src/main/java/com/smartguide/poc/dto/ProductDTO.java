package com.smartguide.poc.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for Product data transfer
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductDTO {
    private Long id;
    private String productCode;
    private String productName;
    private String category;
    private String subCategory;
    private String description;
    private String islamicStructure;
    private BigDecimal annualFee;
    private BigDecimal minIncome;
    private Boolean active;
    private List<String> keyBenefits;
    private List<String> keywords;
    private LocalDateTime createdAt;
}
