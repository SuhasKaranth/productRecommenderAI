package com.smartguide.poc.controller;

import com.smartguide.poc.dto.*;
import com.smartguide.poc.entity.Product;
import com.smartguide.poc.service.LLMService;
import com.smartguide.poc.service.ProductService;
import com.smartguide.poc.service.RulesEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST Controller for product recommendations
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Recommendations", description = "Product recommendation endpoints")
public class RecommendationController {

    private final LLMService llmService;
    private final RulesEngine rulesEngine;
    private final ProductService productService;

    @PostMapping("/recommend")
    @Operation(summary = "Get product recommendations", description = "Process user input and return recommended banking products")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Successful recommendation",
                    content = @Content(schema = @Schema(implementation = RecommendationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Internal server error",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<RecommendationResponse> recommendProducts(
            @Valid @RequestBody RecommendationRequest request) {

        long startTime = System.currentTimeMillis();

        try {
            int inputLen = request.getUserInput().length();
            log.info("Processing recommendation request: [REDACTED - length: {} chars]", inputLen);
            log.debug("Recommendation input preview: {}...",
                    request.getUserInput().substring(0, Math.min(50, inputLen)));

            // Step 1: Extract intent using LLM
            Map<String, Object> intentData = llmService.extractIntent(
                    request.getUserInput(),
                    request.getLanguage()
            );
            log.info("Extracted intent: {}", intentData);

            // Step 2: Get product categories from rules engine
            Map<String, Object> categories = rulesEngine.getProductCategories(
                    (String) intentData.get("intent"),
                    (Double) intentData.get("confidence")
            );
            log.info("Mapped categories: {}", categories);

            // Step 3: Build filters
            Map<String, Object> filters = rulesEngine.buildProductFilters(
                    categories,
                    request.getUserContext()
            );

            // Step 4: Get and rank products (pass user input for keyword matching)
            List<Map<String, Object>> rankedProducts = productService.getRecommendations(
                    filters,
                    intentData,
                    categories,
                    request.getUserInput()
            );

            // Step 5: Build response
            List<ProductRecommendation> recommendations = new ArrayList<>();
            for (int i = 0; i < rankedProducts.size(); i++) {
                Map<String, Object> item = rankedProducts.get(i);
                Product product = (Product) item.get("product");

                ProductRecommendation recommendation = ProductRecommendation.builder()
                        .rank(i + 1)
                        .productId(product.getId())
                        .productCode(product.getProductCode())
                        .productName(product.getProductName())
                        .category(product.getCategory())
                        .islamicStructure(product.getIslamicStructure() != null
                                ? product.getIslamicStructure()
                                : "Sharia-compliant")
                        .relevanceScore(Math.round((Double) item.get("score") * 100.0) / 100.0)
                        .reason((String) item.get("reason"))
                        .keyBenefits(product.getKeyBenefits() != null
                                ? product.getKeyBenefits()
                                : List.of())
                        .annualFee(product.getAnnualFee())
                        .minIncome(product.getMinIncome())
                        .build();

                recommendations.add(recommendation);
            }

            long processingTimeMs = System.currentTimeMillis() - startTime;

            // Determine if we need to add a message
            String message = null;
            if (recommendations.isEmpty()) {
                message = "No specific products found. Please try rephrasing your request.";
            } else if ((Double) intentData.get("confidence") < 0.5) {
                message = "I've shown you some general recommendations. Could you provide more details about what you're looking for?";
            }

            IntentData intent = new IntentData(
                    (String) intentData.get("intent"),
                    (Double) intentData.get("confidence"),
                    (Map<String, Object>) intentData.get("entities")
            );

            RecommendationResponse response = RecommendationResponse.builder()
                    .status("success")
                    .intent(intent)
                    .recommendations(recommendations)
                    .processingTimeMs(processingTimeMs)
                    .message(message)
                    .build();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            // Log message only at ERROR; full stack trace at DEBUG to avoid leaking user data in prod logs.
            log.error("Error processing recommendation request (see DEBUG for stack trace): {}",
                    e.getClass().getSimpleName());
            log.debug("Recommendation error detail", e);

            long processingTimeMs = System.currentTimeMillis() - startTime;

            // Return error response with empty recommendations
            RecommendationResponse errorResponse = RecommendationResponse.builder()
                    .status("error")
                    .intent(new IntentData("GENERAL", 0.0, Map.of()))
                    .recommendations(List.of())
                    .processingTimeMs(processingTimeMs)
                    .message("Failed to process recommendation request: " + e.getMessage())
                    .build();

            return ResponseEntity.status(500).body(errorResponse);
        }
    }
}
