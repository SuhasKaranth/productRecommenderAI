package com.smartguide.poc.admin.service;

import com.smartguide.poc.admin.dto.StagingProductDTO;
import com.smartguide.poc.entity.Product;
import com.smartguide.poc.entity.StagingProduct;
import com.smartguide.poc.repository.ProductRepository;
import com.smartguide.poc.repository.StagingProductRepository;
import com.smartguide.poc.service.LLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for managing staging products and approval workflow
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StagingProductService {

    private final StagingProductRepository stagingProductRepository;
    private final ProductRepository productRepository;
    private final LLMService llmService;

    /**
     * Get all pending staging products
     */
    public List<StagingProductDTO> getAllPendingProducts() {
        return stagingProductRepository.findAllPendingOrderByCreatedAtDesc()
                .stream()
                .map(StagingProductDTO::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Get all staging products
     */
    public List<StagingProductDTO> getAllStagingProducts() {
        return stagingProductRepository.findAllOrderByCreatedAtDesc()
                .stream()
                .map(StagingProductDTO::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Create new staging product
     */
    @Transactional
    public StagingProductDTO createStagingProduct(StagingProductDTO dto) {
        StagingProduct stagingProduct = StagingProductDTO.toEntity(dto);
        stagingProduct.setApprovalStatus(StagingProduct.ApprovalStatus.PENDING);
        stagingProduct.setCreatedAt(LocalDateTime.now());

        StagingProduct saved = stagingProductRepository.save(stagingProduct);
        log.info("Created new staging product: {} - {}", saved.getId(), saved.getProductName());
        return StagingProductDTO.fromEntity(saved);
    }

    /**
     * Get staging product by ID
     */
    public StagingProductDTO getStagingProductById(Long id) {
        return stagingProductRepository.findById(id)
                .map(StagingProductDTO::fromEntity)
                .orElseThrow(() -> new RuntimeException("Staging product not found: " + id));
    }

    /**
     * Update staging product
     */
    @Transactional
    public StagingProductDTO updateStagingProduct(Long id, StagingProductDTO dto) {
        StagingProduct product = stagingProductRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Staging product not found: " + id));

        // Update fields
        product.setProductCode(dto.getProductCode());
        product.setProductName(dto.getProductName());
        product.setCategory(dto.getCategory());
        product.setSubCategory(dto.getSubCategory());
        product.setDescription(dto.getDescription());
        product.setIslamicStructure(dto.getIslamicStructure());
        product.setAnnualRate(dto.getAnnualRate());
        product.setAnnualFee(dto.getAnnualFee());
        product.setMinIncome(dto.getMinIncome());
        product.setMinCreditScore(dto.getMinCreditScore());
        product.setEligibilityCriteria(dto.getEligibilityCriteria());
        product.setKeyBenefits(dto.getKeyBenefits());
        product.setShariaCertified(dto.getShariaCertified());
        product.setActive(dto.getActive());

        StagingProduct saved = stagingProductRepository.save(product);
        log.info("Updated staging product: {}", id);
        return StagingProductDTO.fromEntity(saved);
    }

    /**
     * Approve single staging product and move to production
     */
    @Transactional
    public void approveStagingProduct(Long id, String reviewedBy, String reviewNotes) {
        StagingProduct stagingProduct = stagingProductRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Staging product not found: " + id));

        // Generate product code if not present
        if (stagingProduct.getProductCode() == null || stagingProduct.getProductCode().trim().isEmpty()) {
            stagingProduct.setProductCode(generateProductCode(stagingProduct));
        }

        // Create or update product in production table
        Product product = productRepository.findByProductCode(stagingProduct.getProductCode())
                .orElse(new Product());

        copyToProduct(stagingProduct, product);

        productRepository.save(product);

        // Update staging product status
        stagingProduct.setApprovalStatus(StagingProduct.ApprovalStatus.APPROVED);
        stagingProduct.setReviewedBy(reviewedBy);
        stagingProduct.setReviewedAt(LocalDateTime.now());
        stagingProduct.setReviewNotes(reviewNotes);
        stagingProductRepository.save(stagingProduct);

        log.info("Approved staging product {} and moved to production", id);
    }

    /**
     * Bulk approve multiple staging products
     */
    @Transactional
    public void bulkApproveProducts(List<Long> productIds, String reviewedBy, String reviewNotes) {
        for (Long id : productIds) {
            try {
                approveStagingProduct(id, reviewedBy, reviewNotes);
            } catch (Exception e) {
                log.error("Failed to approve product {}: {}", id, e.getMessage());
            }
        }
    }

    /**
     * Reject/delete staging product
     */
    @Transactional
    public void rejectStagingProduct(Long id, String reviewedBy, String reviewNotes) {
        StagingProduct stagingProduct = stagingProductRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Staging product not found: " + id));

        stagingProduct.setApprovalStatus(StagingProduct.ApprovalStatus.REJECTED);
        stagingProduct.setReviewedBy(reviewedBy);
        stagingProduct.setReviewedAt(LocalDateTime.now());
        stagingProduct.setReviewNotes(reviewNotes);
        stagingProductRepository.save(stagingProduct);

        log.info("Rejected staging product: {}", id);
    }

    /**
     * Delete staging product
     */
    @Transactional
    public void deleteStagingProduct(Long id) {
        stagingProductRepository.deleteById(id);
        log.info("Deleted staging product: {}", id);
    }

    /**
     * Bulk reject multiple staging products
     */
    @Transactional
    public void bulkRejectProducts(List<Long> productIds, String reviewedBy, String reviewNotes) {
        for (Long id : productIds) {
            try {
                rejectStagingProduct(id, reviewedBy, reviewNotes);
            } catch (Exception e) {
                log.error("Failed to reject product {}: {}", id, e.getMessage());
            }
        }
    }

    /**
     * Bulk delete multiple staging products
     */
    @Transactional
    public void bulkDeleteProducts(List<Long> productIds) {
        for (Long id : productIds) {
            try {
                deleteStagingProduct(id);
            } catch (Exception e) {
                log.error("Failed to delete product {}: {}", id, e.getMessage());
            }
        }
    }

    /**
     * Get counts by status
     */
    public long getPendingCount() {
        return stagingProductRepository.countByApprovalStatus(StagingProduct.ApprovalStatus.PENDING);
    }

    public long getApprovedCount() {
        return stagingProductRepository.countByApprovalStatus(StagingProduct.ApprovalStatus.APPROVED);
    }

    public long getRejectedCount() {
        return stagingProductRepository.countByApprovalStatus(StagingProduct.ApprovalStatus.REJECTED);
    }

    /**
     * Generate keywords for a staging product using LLM
     */
    public List<String> generateKeywords(Long id) {
        StagingProduct product = stagingProductRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Staging product not found: " + id));

        log.info("Generating keywords for product: {}", product.getProductName());
        List<String> keywords = llmService.generateKeywords(product);
        log.info("Generated {} keywords: {}", keywords.size(), keywords);

        return keywords;
    }

    /**
     * Save keywords for a staging product
     */
    @Transactional
    public StagingProductDTO saveKeywords(Long id, List<String> keywords) {
        StagingProduct product = stagingProductRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Staging product not found: " + id));

        product.setKeywords(keywords);
        StagingProduct saved = stagingProductRepository.save(product);

        log.info("Saved {} keywords for product: {}", keywords.size(), product.getProductName());
        return StagingProductDTO.fromEntity(saved);
    }

    /**
     * Copy data from staging product to production product
     */
    private void copyToProduct(StagingProduct staging, Product product) {
        product.setProductCode(staging.getProductCode());
        product.setProductName(staging.getProductName());
        product.setCategory(staging.getCategory());
        product.setSubCategory(staging.getSubCategory());
        product.setDescription(staging.getDescription());
        product.setIslamicStructure(staging.getIslamicStructure());
        product.setAnnualRate(staging.getAnnualRate());
        product.setAnnualFee(staging.getAnnualFee());
        product.setMinIncome(staging.getMinIncome());
        product.setMinCreditScore(staging.getMinCreditScore());
        product.setEligibilityCriteria(staging.getEligibilityCriteria());
        product.setKeyBenefits(staging.getKeyBenefits());
        product.setKeywords(staging.getKeywords());
        product.setShariaCertified(staging.getShariaCertified());
        product.setActive(staging.getActive());
        product.setSourceWebsiteId(staging.getSourceWebsiteId());
        product.setSourceUrl(staging.getSourceUrl());
        product.setScrapedAt(staging.getScrapedAt());
        product.setDataQualityScore(staging.getDataQualityScore());
    }

    /**
     * Generate a unique product code based on product details
     * Max length: 50 characters (database constraint)
     */
    private String generateProductCode(StagingProduct product) {
        // Create base code from category and product name
        String category = product.getCategory() != null ? product.getCategory() : "PRODUCT";
        String productName = product.getProductName();

        // Clean product name - remove special chars, keep alphanumeric
        String cleanName = productName
                .replaceAll("[^a-zA-Z0-9\\s]", "")
                .trim()
                .toUpperCase()
                .replaceAll("\\s+", "-");

        // Timestamp for uniqueness (4 digits)
        String timestamp = String.valueOf(System.currentTimeMillis() % 10000);

        // Calculate available space for name: 50 - category.length - 2 hyphens - 4 digit timestamp
        int maxNameLength = 50 - category.length() - 2 - timestamp.length();

        // Ensure we have at least 5 chars for the name
        if (maxNameLength < 5) {
            maxNameLength = 5;
        }

        // Truncate name if needed
        String nameAbbr = cleanName.length() > maxNameLength
            ? cleanName.substring(0, maxNameLength)
            : cleanName;

        // Build product code (guaranteed <= 50 chars)
        String productCode = category + "-" + nameAbbr + "-" + timestamp;

        // Safety check - truncate if still too long
        if (productCode.length() > 50) {
            productCode = productCode.substring(0, 50);
        }

        log.info("Generated product code: {} (length: {})", productCode, productCode.length());
        return productCode;
    }
}
