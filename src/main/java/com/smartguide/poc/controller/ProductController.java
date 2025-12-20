package com.smartguide.poc.controller;

import com.smartguide.poc.dto.ProductDTO;
import com.smartguide.poc.entity.Product;
import com.smartguide.poc.repository.ProductRepository;
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
                .build();
    }
}
