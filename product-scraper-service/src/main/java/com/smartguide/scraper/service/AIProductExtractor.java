package com.smartguide.scraper.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartguide.scraper.dto.ExtractedProduct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI-powered product extraction service using LLM
 * Analyzes webpage content and extracts financial product information
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AIProductExtractor {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${OLLAMA_HOST:http://localhost:11434}")
    private String ollamaHost;

    @Value("${OLLAMA_MODEL:llama3.2}")
    private String ollamaModel;

    /**
     * Stage 1: Check if webpage contains product information
     */
    public boolean isProductPage(String pageContent) {
        String prompt = buildClassificationPrompt(pageContent);

        try {
            String response = callLLM(prompt);
            // Simple check: if response contains "yes" or mentions products
            String lowerResponse = response.toLowerCase();
            return lowerResponse.contains("yes") ||
                   lowerResponse.contains("product") && !lowerResponse.contains("no product");
        } catch (Exception e) {
            log.error("Error in product page classification", e);
            // Default to true to attempt extraction
            return true;
        }
    }

    /**
     * Stage 2: Extract all products from webpage
     */
    public List<ExtractedProduct> extractProducts(String pageContent, String sourceUrl) {
        String prompt = buildExtractionPrompt(pageContent);

        try {
            String response = callLLM(prompt);
            return parseProductsFromResponse(response, sourceUrl, pageContent);
        } catch (Exception e) {
            log.error("Error extracting products from page", e);
            return new ArrayList<>();
        }
    }

    /**
     * Build prompt for page classification (Stage 1)
     */
    private String buildClassificationPrompt(String pageContent) {
        // Truncate content if too long (keep first 3000 chars)
        String truncated = pageContent.length() > 3000 ?
            pageContent.substring(0, 3000) + "..." : pageContent;

        return String.format(
            "Analyze this webpage content and determine if it contains financial product information " +
            "(credit cards, loans, savings accounts, financing, insurance, investments, etc.).\n\n" +
            "Answer with YES or NO, followed by brief reasoning.\n\n" +
            "WEBPAGE CONTENT:\n%s\n\n" +
            "ANSWER:",
            truncated
        );
    }

    /**
     * Build prompt for product extraction (Stage 2)
     */
    private String buildExtractionPrompt(String pageContent) {
        // Truncate if too long (keep first 8000 chars for better product extraction)
        String truncated = pageContent.length() > 8000 ?
            pageContent.substring(0, 8000) + "..." : pageContent;

        return String.format(
            "You are a financial product extraction assistant. Analyze this webpage and extract ALL financial products mentioned.\n\n" +
            "WEBPAGE CONTENT:\n%s\n\n" +
            "INSTRUCTIONS:\n" +
            "1. Identify all financial products (credit cards, loans, savings accounts, financing, insurance, investments, etc.)\n" +
            "2. Extract complete details for each product\n" +
            "3. Determine if it's an Islamic banking product (mentions Shariah, Halal, Murabaha, Tawarruq, Musharakah, Ijarah, etc.)\n" +
            "4. Extract numeric values (rates, fees, income requirements)\n" +
            "5. Provide confidence score (0.0-1.0) based on data completeness\n\n" +
            "OUTPUT FORMAT (JSON array):\n" +
            "[\n" +
            "  {\n" +
            "    \"product_name\": \"Product Name\",\n" +
            "    \"description\": \"Brief description\",\n" +
            "    \"category\": \"CREDIT_CARD|FINANCING|SAVINGS_ACCOUNT|INVESTMENT|INSURANCE\",\n" +
            "    \"islamic_product\": true or false,\n" +
            "    \"islamic_structure\": \"Murabaha|Tawarruq|etc\" (if Islamic product),\n" +
            "    \"annual_rate\": 12.5 (number, if mentioned),\n" +
            "    \"annual_fee\": 150.0 (number, if mentioned),\n" +
            "    \"min_income\": 50000.0 (number, if mentioned),\n" +
            "    \"key_benefits\": [\"Benefit 1\", \"Benefit 2\"],\n" +
            "    \"confidence_score\": 0.85 (0.0 to 1.0),\n" +
            "    \"extraction_reasoning\": \"Brief explanation\"\n" +
            "  }\n" +
            "]\n\n" +
            "If NO products found, return empty array: []\n\n" +
            "JSON OUTPUT:",
            truncated
        );
    }

    /**
     * Call Ollama LLM directly
     */
    private String callLLM(String prompt) {
        try {
            Map<String, Object> request = Map.of(
                "model", ollamaModel,
                "prompt", prompt,
                "stream", false,
                "options", Map.of(
                    "temperature", 0.1,  // Low temperature for consistent extraction
                    "num_predict", 2000  // Max tokens
                )
            );

            Map<String, Object> response = webClient.post()
                .uri(ollamaHost + "/api/generate")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            if (response != null && response.containsKey("response")) {
                return response.get("response").toString();
            }

            return "";
        } catch (Exception e) {
            log.error("Error calling Ollama API at {}", ollamaHost, e);
            throw new RuntimeException("Failed to call Ollama service: " + e.getMessage(), e);
        }
    }

    /**
     * Parse LLM response into ExtractedProduct objects
     */
    private List<ExtractedProduct> parseProductsFromResponse(String response, String sourceUrl, String pageContent) {
        try {
            // Extract JSON array from response (LLM might include extra text)
            String jsonStart = response.indexOf('[') >= 0 ?
                response.substring(response.indexOf('[')) : response;
            String jsonEnd = jsonStart.lastIndexOf(']') >= 0 ?
                jsonStart.substring(0, jsonStart.lastIndexOf(']') + 1) : jsonStart;

            // Parse JSON to List of Maps
            List<Map<String, Object>> rawProducts = objectMapper.readValue(
                jsonEnd,
                new TypeReference<List<Map<String, Object>>>() {}
            );

            // Convert to ExtractedProduct DTOs
            List<ExtractedProduct> products = new ArrayList<>();
            for (Map<String, Object> raw : rawProducts) {
                ExtractedProduct product = mapToExtractedProduct(raw, sourceUrl, pageContent);
                products.add(product);
            }

            log.info("Successfully extracted {} products from page", products.size());
            return products;
        } catch (Exception e) {
            log.error("Error parsing LLM response: {}", response, e);
            return new ArrayList<>();
        }
    }

    /**
     * Map raw JSON object to ExtractedProduct
     */
    @SuppressWarnings("unchecked")
    private ExtractedProduct mapToExtractedProduct(Map<String, Object> raw, String sourceUrl, String pageContent) {
        return ExtractedProduct.builder()
            .productName(getString(raw, "product_name"))
            .description(getString(raw, "description"))
            .category(getString(raw, "category"))
            .subCategory(getString(raw, "sub_category"))
            .islamicProduct(getBoolean(raw, "islamic_product"))
            .islamicStructure(getString(raw, "islamic_structure"))
            .annualRate(getDecimal(raw, "annual_rate"))
            .annualFee(getDecimal(raw, "annual_fee"))
            .minIncome(getDecimal(raw, "min_income"))
            .minCreditScore(getInteger(raw, "min_credit_score"))
            .keyBenefits((List<String>) raw.get("key_benefits"))
            .eligibilityCriteria((List<String>) raw.get("eligibility_criteria"))
            .confidenceScore(getDouble(raw, "confidence_score"))
            .extractionReasoning(getString(raw, "extraction_reasoning"))
            .sourceUrl(sourceUrl)
            .pageContent(pageContent.substring(0, Math.min(pageContent.length(), 5000)))
            .build();
    }

    // Helper methods for safe type conversion
    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private Boolean getBoolean(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value != null) return Boolean.parseBoolean(value.toString());
        return null;
    }

    private java.math.BigDecimal getDecimal(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return java.math.BigDecimal.valueOf(((Number) value).doubleValue());
        }
        if (value != null) {
            try {
                return new java.math.BigDecimal(value.toString());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private Integer getInteger(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private Double getDouble(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value != null) {
            try {
                return Double.parseDouble(value.toString());
            } catch (Exception e) {
                return 0.7; // Default confidence
            }
        }
        return 0.7;
    }
}
