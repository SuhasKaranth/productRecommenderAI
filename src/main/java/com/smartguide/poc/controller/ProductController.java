package com.smartguide.poc.controller;

import com.smartguide.poc.dto.ProductDTO;
import com.smartguide.poc.entity.Product;
import com.smartguide.poc.repository.ProductRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST Controller for Product management
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Slf4j
public class ProductController {

    private final ProductRepository productRepository;
    private final com.smartguide.poc.service.ProductService productService;

    /**
     * Get all products with optional filters
     */
    @GetMapping
    public ResponseEntity<List<ProductDTO>> getAllProducts(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String search) {

        log.info("Getting products - category: {}, active: {}, search: {}", category, active, search);

        List<Product> products;

        if (category != null && active != null) {
            products = productRepository.findByCategoryAndActiveTrue(category);
        } else if (active != null && active) {
            products = productRepository.findByActiveTrue();
        } else {
            products = productRepository.findAll();
        }

        // Apply search filter if provided
        if (search != null && !search.trim().isEmpty()) {
            String searchLower = search.toLowerCase();
            products = products.stream()
                    .filter(p -> p.getProductName().toLowerCase().contains(searchLower) ||
                            p.getProductCode().toLowerCase().contains(searchLower) ||
                            (p.getCategory() != null && p.getCategory().toLowerCase().contains(searchLower)))
                    .collect(Collectors.toList());
        }

        // Filter by category if specified (when not using repository method)
        if (category != null && active == null) {
            products = products.stream()
                    .filter(p -> category.equalsIgnoreCase(p.getCategory()))
                    .collect(Collectors.toList());
        }

        List<ProductDTO> productDTOs = products.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());

        log.info("Found {} products", productDTOs.size());
        return ResponseEntity.ok(productDTOs);
    }

    /**
     * Get product by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<ProductDTO> getProductById(@PathVariable Long id) {
        log.info("Getting product by id: {}", id);

        return productRepository.findById(id)
                .map(product -> ResponseEntity.ok(convertToDTO(product)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Update product
     */
    @PutMapping("/{id}")
    public ResponseEntity<ProductDTO> updateProduct(
            @PathVariable Long id,
            @RequestBody ProductDTO productDTO) {

        log.info("Updating product id: {}", id);

        return productRepository.findById(id)
                .map(existingProduct -> {
                    // Update fields
                    if (productDTO.getProductName() != null) {
                        existingProduct.setProductName(productDTO.getProductName());
                    }
                    if (productDTO.getCategory() != null) {
                        existingProduct.setCategory(productDTO.getCategory());
                    }
                    if (productDTO.getSubCategory() != null) {
                        existingProduct.setSubCategory(productDTO.getSubCategory());
                    }
                    if (productDTO.getDescription() != null) {
                        existingProduct.setDescription(productDTO.getDescription());
                    }
                    if (productDTO.getIslamicStructure() != null) {
                        existingProduct.setIslamicStructure(productDTO.getIslamicStructure());
                    }
                    if (productDTO.getAnnualFee() != null) {
                        existingProduct.setAnnualFee(productDTO.getAnnualFee());
                    }
                    if (productDTO.getMinIncome() != null) {
                        existingProduct.setMinIncome(productDTO.getMinIncome());
                    }
                    if (productDTO.getActive() != null) {
                        existingProduct.setActive(productDTO.getActive());
                    }
                    if (productDTO.getKeyBenefits() != null) {
                        existingProduct.setKeyBenefits(productDTO.getKeyBenefits());
                    }

                    Product savedProduct = productRepository.save(existingProduct);
                    log.info("Product updated successfully: {}", savedProduct.getProductCode());

                    return ResponseEntity.ok(convertToDTO(savedProduct));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delete single product
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        log.info("Deleting product id: {}", id);

        if (productRepository.existsById(id)) {
            productRepository.deleteById(id);
            log.info("Product deleted successfully: {}", id);
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Bulk delete products
     */
    @PostMapping("/bulk-delete")
    public ResponseEntity<Map<String, Object>> bulkDeleteProducts(
            @RequestBody Map<String, List<Object>> request) {

        List<Object> productIdsRaw = request.get("productIds");
        log.info("Bulk deleting {} products", productIdsRaw.size());

        int deletedCount = 0;
        for (Object idObj : productIdsRaw) {
            Long id = Long.valueOf(idObj.toString());
            if (productRepository.existsById(id)) {
                productRepository.deleteById(id);
                deletedCount++;
            }
        }

        log.info("Bulk delete completed: {} products deleted", deletedCount);

        Map<String, Object> response = new HashMap<>();
        response.put("deletedCount", deletedCount);
        response.put("message", deletedCount + " product(s) deleted successfully");

        return ResponseEntity.ok(response);
    }

    /**
     * Generate keywords for a product
     */
    @PostMapping("/{id}/generate-keywords")
    public ResponseEntity<Map<String, Object>> generateKeywords(@PathVariable Long id) {
        log.info("Generating keywords for product id: {}", id);

        try {
            List<String> keywords = productService.generateKeywords(id);

            Map<String, Object> response = new HashMap<>();
            response.put("keywords", keywords);
            response.put("message", "Keywords generated successfully");

            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            log.error("Failed to generate keywords for product {}: {}", id, e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error generating keywords for product {}: {}", id, e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to generate keywords: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Generate summary for a product
     */
    @PostMapping("/{id}/generate-summary")
    public ResponseEntity<Map<String, Object>> generateSummary(@PathVariable Long id) {
        log.info("Generating summary for product id: {}", id);
        try {
            String summary = productService.generateSummary(id);
            Map<String, Object> response = new HashMap<>();
            response.put("summary", summary);
            response.put("message", "Summary generated successfully");
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            log.error("Product not found when generating summary for id {}: {}", id, e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error generating summary for product {}: {}", id, e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to generate summary: " + e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Save keywords for a product
     */
    @PostMapping("/{id}/keywords")
    public ResponseEntity<ProductDTO> saveKeywords(
            @PathVariable Long id,
            @RequestBody Map<String, List<String>> request) {

        log.info("Saving keywords for product id: {}", id);

        try {
            List<String> keywords = request.get("keywords");
            if (keywords == null) {
                return ResponseEntity.badRequest().build();
            }

            Product updatedProduct = productService.saveKeywords(id, keywords);
            return ResponseEntity.ok(convertToDTO(updatedProduct));
        } catch (RuntimeException e) {
            log.error("Failed to save keywords for product {}: {}", id, e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error saving keywords for product {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Refresh raw page content for a product by re-scraping its configured source URL.
     * <p>
     * Delegates to {@link com.smartguide.poc.service.ProductService#refreshProductContent(Long)}.
     *
     * @param id product ID
     * @return 200 with {@code scrapedAt} and {@code message} on success;
     *         404 if product not found;
     *         400 if product has no source URL configured;
     *         502 if scraper service is unreachable;
     *         500 on other unexpected errors
     */
    @PostMapping("/{id}/refresh-content")
    @Operation(
            summary = "Refresh raw page content for a product",
            description = "Re-scrapes the product's sourceUrl via the scraper service and "
                    + "stores the result in rawPageContent + scrapedAt. Call this before "
                    + "re-generating the AI summary to ensure it uses the latest data.")
    @ApiResponse(responseCode = "200", description = "Content refreshed — scrapedAt timestamp returned")
    @ApiResponse(responseCode = "400", description = "Product has no source URL configured")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @ApiResponse(responseCode = "502", description = "Scraper service unreachable")
    @ApiResponse(responseCode = "500", description = "Unexpected error during refresh")
    public ResponseEntity<Map<String, Object>> refreshContent(@PathVariable Long id) {
        log.info("Refresh content requested for product id: {}", id);
        try {
            Map<String, Object> result = productService.refreshProductContent(id);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            // Product exists but has no sourceUrl configured
            log.warn("Cannot refresh product {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.toLowerCase().contains("not found")) {
                log.warn("Product not found when refreshing content for id {}: {}", id, msg);
                return ResponseEntity.notFound().build();
            }
            if (msg.toLowerCase().contains("unavailable") || msg.toLowerCase().contains("connection")) {
                log.error("Scraper service unreachable while refreshing product {}: {}", id, msg);
                return ResponseEntity.status(502).body(Map.of("error", msg));
            }
            log.error("Unexpected error refreshing content for product {}: {}", id, msg, e);
            return ResponseEntity.internalServerError().body(Map.of("error", msg));
        } catch (Exception e) {
            log.error("Unexpected error refreshing content for product {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Unexpected error during content refresh: " + e.getMessage()));
        }
    }

    /**
     * Convert Product entity to ProductDTO
     */
    private ProductDTO convertToDTO(Product product) {
        return ProductDTO.builder()
                .id(product.getId())
                .productCode(product.getProductCode())
                .productName(product.getProductName())
                .category(product.getCategory())
                .subCategory(product.getSubCategory())
                .description(product.getDescription())
                .islamicStructure(product.getIslamicStructure())
                .annualFee(product.getAnnualFee())
                .minIncome(product.getMinIncome())
                .active(product.getActive())
                .keyBenefits(product.getKeyBenefits())
                .keywords(product.getKeywords())
                .createdAt(product.getCreatedAt())
                .scrapedAt(product.getScrapedAt())
                .sourceUrl(product.getSourceUrl())
                .build();
    }
}
