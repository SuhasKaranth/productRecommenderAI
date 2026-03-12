package com.smartguide.scraper.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import java.util.stream.Collectors;

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

    @Value("${SCRAPER_LLM_MAX_TOKENS:6000}")
    private int maxTokens;

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
     * Discovery mode: extract product names and detail-page URLs from a listing page.
     * <p>
     * Uses a lightweight LLM prompt that asks only for product name, detail URL, and
     * category — avoiding the token-limit issues caused by the full extraction prompt
     * when there are many products on one listing page.
     * <p>
     * Relative URLs (starting with "/") are resolved to absolute using {@code baseUrl}.
     *
     * @param pageContent raw text content of the listing page (truncated to 8000 chars internally)
     * @param baseUrl     canonical base URL of the bank website (e.g. {@code https://dib.ae})
     * @return list of {@link ExtractedProduct} with only {@code productName}, {@code sourceUrl},
     *         and {@code category} populated; all other fields are null
     */
    public List<ExtractedProduct> discoverProductUrls(String pageContent, String baseUrl) {
        // 8000 chars is sufficient for URL discovery on listing pages
        String truncated = pageContent.length() > 8000
                ? pageContent.substring(0, 8000) + "..."
                : pageContent;

        String prompt = """
                You are extracting a product list from a bank website. Find ALL financial products on this page and extract their name and the URL to their individual detail page.

                WEBPAGE CONTENT:
                %s

                RULES:
                1. Find every financial product mentioned (credit cards, financing, savings, etc.)
                2. Extract the URL that links to that specific product's own detail page — not category pages
                3. If no direct product URL exists, use null for detail_url
                4. Category: one of CREDIT_CARD, FINANCING, SAVINGS_ACCOUNT, INVESTMENT, TAKAFUL
                5. Use 'profit rate' not 'interest', 'finance' not 'loan', 'Takaful' not 'insurance'

                Return ONLY a JSON array — no preamble, no markdown fences:
                [{"product_name": "...", "detail_url": "...", "category": "..."}]
                """.formatted(truncated);

        log.info("Discovery mode: calling LLM for product URL extraction, content length={}", truncated.length());
        try {
            String response = callLLM(prompt);
            return parseDiscoveredProducts(response, baseUrl);
        } catch (Exception e) {
            log.error("Error in discovery mode product URL extraction", e);
            return new ArrayList<>();
        }
    }

    /**
     * Parse LLM response from discovery mode into a list of minimal {@link ExtractedProduct} objects.
     * Each object has only productName, sourceUrl, and category populated.
     */
    private List<ExtractedProduct> parseDiscoveredProducts(String response, String baseUrl) {
        ObjectMapper lenient = objectMapper.copy()
                .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true)
                .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
                .configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String json = extractJsonArray(response);
        if (json == null || json.isBlank()) {
            log.warn("Discovery mode: no JSON array found in LLM response (length={}). First 300 chars: {}",
                    response.length(), response.substring(0, Math.min(300, response.length())));
            return new ArrayList<>();
        }

        try {
            List<Map<String, Object>> rawItems = lenient.readValue(
                    json, new TypeReference<List<Map<String, Object>>>() {});
            List<ExtractedProduct> discovered = new ArrayList<>();
            for (Map<String, Object> raw : rawItems) {
                ExtractedProduct product = mapToDiscoveredProduct(raw, baseUrl);
                if (product != null) {
                    discovered.add(product);
                }
            }
            log.info("Discovery mode: found {} product URLs from listing page", discovered.size());
            return discovered;
        } catch (Exception e) {
            log.error("Discovery mode: failed to parse LLM response — {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Map a raw discovery JSON object to a minimal {@link ExtractedProduct}.
     * Resolves relative detail URLs to absolute using the bank's base URL.
     * Returns null if the entry has no product name (skip invalid entries).
     */
    private ExtractedProduct mapToDiscoveredProduct(Map<String, Object> raw, String baseUrl) {
        String productName = getString(raw, "product_name");
        if (productName == null || productName.isBlank()) {
            return null; // Skip entries with no name
        }

        String detailUrl = getString(raw, "detail_url");
        String resolvedUrl = resolveUrl(detailUrl, baseUrl);

        return ExtractedProduct.builder()
                .productName(productName)
                .sourceUrl(resolvedUrl)
                .category(getString(raw, "category"))
                // All other fields intentionally null — this is discovery-only data
                .build();
    }

    /**
     * Resolve a potentially relative URL to an absolute URL using the given base URL.
     * <ul>
     *   <li>If {@code url} is null or blank — returns null.</li>
     *   <li>If {@code url} starts with "http" — used as-is.</li>
     *   <li>If {@code url} starts with "/" — prepend scheme + host extracted from {@code baseUrl}.</li>
     * </ul>
     */
    private String resolveUrl(String url, String baseUrl) {
        if (url == null || url.isBlank()) {
            return null;
        }
        if (url.startsWith("http")) {
            return url;
        }
        if (url.startsWith("/") && baseUrl != null && !baseUrl.isBlank()) {
            // Extract scheme://host from baseUrl (e.g. https://dib.ae from https://dib.ae/cards)
            try {
                java.net.URI uri = java.net.URI.create(baseUrl);
                String schemeHost = uri.getScheme() + "://" + uri.getHost();
                return schemeHost + url;
            } catch (Exception e) {
                log.warn("Could not resolve relative URL '{}' against base '{}': {}", url, baseUrl, e.getMessage());
                return url; // Return as-is if resolution fails
            }
        }
        return url;
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
        // Increased from 8000 to 12000 — more context produces richer structured output
        String truncated = pageContent.length() > 12000
            ? pageContent.substring(0, 12000) + "..."
            : pageContent;

        return String.format(
            "You are an Islamic banking product extraction assistant. Your output feeds directly into an AI-powered " +
            "recommendation engine that generates product summaries and matches customers to products. " +
            "Extract ALL financial products from the webpage below.\n\n" +
            "WEBPAGE CONTENT:\n%s\n\n" +
            "INSTRUCTIONS:\n" +
            "1. Identify all financial products mentioned (credit cards, home finance, auto finance, savings accounts, " +
            "   financing facilities, Takaful, investments, etc.).\n" +
            "2. For each product, write a DETAILED description (4-6 sentences minimum). This description will be used " +
            "   by a downstream AI to generate customer-facing summaries — it must be rich enough to produce a complete, " +
            "   accurate summary without guessing. Include: what the product is, how it works (Islamic structure), " +
            "   who it is for, what problems it solves, and any notable features.\n" +
            "3. Identify the Islamic financing structure (Murabaha, Tawarruq, Musharakah, Ijarah, Wakala, Mudharabah, etc.).\n" +
            "4. Extract ALL numeric values mentioned (profit rates, fees, minimum income, minimum credit score, tenure).\n" +
            "5. Identify the target customer profile: employment type, nationality, income bracket, lifestyle segment.\n" +
            "6. Identify specific use cases: what situations prompt a customer to choose this product.\n" +
            "7. Extract detailed eligibility: minimum income, minimum age, nationality restrictions, employment status, documents needed.\n" +
            "8. Capture a raw_page_content field: copy the most informative and relevant 2000-3000 characters of the " +
            "   page text that describe this product (features, rates, eligibility, benefits). This is the verbatim " +
            "   source text a downstream AI will use as its primary grounding source — include everything factual.\n" +
            "9. Assign confidence score (0.0-1.0) based on data completeness.\n" +
            "10. IMPORTANT TERMINOLOGY: Use 'profit rate' not 'interest', 'finance' not 'loan', " +
            "    'home finance' not 'mortgage', 'Takaful' not 'insurance'. All products are Sharia-compliant.\n\n" +
            "OUTPUT FORMAT (JSON array — return [] if no products found):\n" +
            "[\n" +
            "  {\n" +
            "    \"product_name\": \"Full official product name\",\n" +
            "    \"description\": \"Detailed 4-6 sentence description suitable for AI summary generation\",\n" +
            "    \"target_customer\": \"Who this product is designed for (income level, employment type, lifestyle)\",\n" +
            "    \"use_cases\": [\"Use case 1\", \"Use case 2\"],\n" +
            "    \"category\": \"CREDIT_CARD|FINANCING|SAVINGS_ACCOUNT|INVESTMENT|TAKAFUL\",\n" +
            "    \"sub_category\": \"e.g. HOME_FINANCE, AUTO_FINANCE, PERSONAL_FINANCE (if applicable)\",\n" +
            "    \"islamic_product\": true,\n" +
            "    \"islamic_structure\": \"Murabaha|Tawarruq|Musharakah|Ijarah|Wakala|etc\",\n" +
            "    \"annual_rate\": 12.5,\n" +
            "    \"annual_fee\": 150.0,\n" +
            "    \"min_income\": 5000.0,\n" +
            "    \"min_credit_score\": 600,\n" +
            "    \"key_benefits\": [\"Benefit 1 (verbatim from page)\", \"Benefit 2\"],\n" +
            "    \"eligibility_criteria\": [\"Must be UAE national or resident\", \"Minimum age 21\", \"Minimum monthly income AED 5000\"],\n" +
            "    \"raw_page_content\": \"[Verbatim 2000-3000 character excerpt of the most informative product content from the page]\",\n" +
            "    \"confidence_score\": 0.85,\n" +
            "    \"extraction_reasoning\": \"Brief explanation of what was found and any gaps\"\n" +
            "  }\n" +
            "]\n\n" +
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
                    "num_predict", maxTokens  // Max tokens — configurable via SCRAPER_LLM_MAX_TOKENS
                )
            );

            Map<String, Object> response = webClient.post()
                .uri(ollamaHost + "/api/generate")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

            if (response != null && response.containsKey("response")) {
                String text = response.get("response").toString();
                log.info("LLM response received: {} chars (done={})", text.length(),
                    response.getOrDefault("done", "?"));
                return text;
            }

            log.warn("LLM returned empty/null response body: {}", response);
            return "";
        } catch (Exception e) {
            log.error("Error calling Ollama API at {}", ollamaHost, e);
            throw new RuntimeException("Failed to call Ollama service: " + e.getMessage(), e);
        }
    }

    /**
     * Parse LLM response into ExtractedProduct objects.
     * Uses a lenient ObjectMapper to handle common LLM JSON quirks
     * (single quotes, unquoted fields, trailing commas, comments).
     * Falls back to per-object recovery when the array is truncated.
     */
    private List<ExtractedProduct> parseProductsFromResponse(String response, String sourceUrl, String pageContent) {
        // Lenient mapper — LLM output is often slightly malformed
        ObjectMapper lenient = objectMapper.copy()
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        String json = extractJsonArray(response);
        if (json == null || json.isBlank()) {
            log.warn("No JSON array found in LLM response (response length={}). First 300 chars: {}",
                response.length(), response.substring(0, Math.min(300, response.length())));
            return new ArrayList<>();
        }

        log.info("JSON array extracted: {} chars", json.length());

        // --- Primary parse: full array ---
        try {
            List<Map<String, Object>> rawProducts = lenient.readValue(
                json, new TypeReference<List<Map<String, Object>>>() {});
            List<ExtractedProduct> products = new ArrayList<>();
            for (Map<String, Object> raw : rawProducts) {
                products.add(mapToExtractedProduct(raw, sourceUrl, pageContent));
            }
            log.info("Successfully extracted {} products from page", products.size());
            return products;
        } catch (Exception e) {
            log.warn("Full-array parse failed ({}), attempting per-object fallback recovery", e.getMessage());
        }

        // --- Fallback: salvage individually complete objects from a truncated array ---
        List<ExtractedProduct> salvaged = new ArrayList<>();
        int i = 0;
        while (i < json.length()) {
            int start = json.indexOf('{', i);
            if (start < 0) break;
            int depth = 0, end = -1;
            for (int j = start; j < json.length(); j++) {
                char c = json.charAt(j);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) { end = j; break; }
                }
            }
            if (end < 0) break; // No more complete objects — rest is truncated
            String objStr = json.substring(start, end + 1);
            try {
                Map<String, Object> raw = lenient.readValue(objStr, new TypeReference<Map<String, Object>>() {});
                if (raw.containsKey("product_name")) {
                    salvaged.add(mapToExtractedProduct(raw, sourceUrl, pageContent));
                }
            } catch (Exception ignore) {
                // Malformed single object — skip it
            }
            i = end + 1;
        }

        if (!salvaged.isEmpty()) {
            log.warn("Fallback recovery salvaged {} products from truncated LLM response", salvaged.size());
        } else {
            log.error("Could not parse any products from LLM response. Last 300 chars: {}",
                response.substring(Math.max(0, response.length() - 300)));
        }
        return salvaged;
    }

    /**
     * Extract the outermost JSON array from an LLM response.
     * Handles markdown code fences and surrounding prose.
     */
    private String extractJsonArray(String response) {
        if (response == null) return null;

        // Strip markdown code fences: ```json ... ``` or ``` ... ```
        response = response.replaceAll("(?s)```(?:json)?\\s*", "").replace("```", "").trim();

        int start = response.indexOf('[');
        if (start < 0) return null;

        // Walk forward tracking bracket depth to find the matching ]
        int depth = 0;
        for (int i = start; i < response.length(); i++) {
            char c = response.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return response.substring(start, i + 1);
            }
        }

        // Unbalanced — fall back to last ] in string
        int end = response.lastIndexOf(']');
        return end > start ? response.substring(start, end + 1) : null;
    }

    /**
     * Map raw JSON object to ExtractedProduct.
     * Uses safe helper methods to handle type mismatches from LLM output.
     */
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
            .keyBenefits(getStringList(raw, "key_benefits"))
            .eligibilityCriteria(getStringList(raw, "eligibility_criteria"))
            .confidenceScore(getDouble(raw, "confidence_score"))
            .extractionReasoning(getString(raw, "extraction_reasoning"))
            .sourceUrl(sourceUrl)
            .pageContent(pageContent.substring(0, Math.min(pageContent.length(), 12000)))
            .rawPageContent(buildRawPageContent(raw, pageContent))
            .build();
    }

    /**
     * Build the raw page content field for downstream LLM grounding.
     * Prefers the LLM-extracted verbatim excerpt; falls back to the beginning of the full page text.
     */
    private String buildRawPageContent(Map<String, Object> raw, String pageContent) {
        String llmExtracted = getString(raw, "raw_page_content");
        if (llmExtracted != null && llmExtracted.length() > 200) {
            return llmExtracted.length() > 15000 ? llmExtracted.substring(0, 15000) : llmExtracted;
        }
        // Fallback: use the beginning of the full page text
        return pageContent.substring(0, Math.min(pageContent.length(), 8000));
    }

    /**
     * Safely extract a list of strings from a field that may contain:
     * - List of strings (normal case)
     * - List of objects/maps (LLM puts {"text": "..."} instead of plain strings)
     * - A single string value
     */
    @SuppressWarnings("unchecked")
    private List<String> getStringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            return list.stream()
                .map(item -> {
                    if (item instanceof Map) {
                        // LLM returned an object instead of a string — flatten its values
                        return ((Map<?, ?>) item).values().stream()
                            .map(v -> v != null ? v.toString() : "")
                            .filter(s -> !s.isBlank())
                            .collect(Collectors.joining(", "));
                    }
                    return item != null ? item.toString() : "";
                })
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
        }
        // Single value — wrap in a list
        return List.of(value.toString());
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
