package com.smartguide.poc.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartguide.poc.config.LLMConfig;
import com.smartguide.poc.entity.Product;
import com.smartguide.poc.entity.StagingProduct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for extracting intent from user input using LLM
 * Supports both Azure OpenAI and Ollama
 */
@Service
@Slf4j
public class LLMService {

    private static final String SYSTEM_PROMPT = """
            You are a banking assistant specializing in Islamic finance products.
            Extract the customer's intent from their input and return a JSON response.

            Valid intents:
            - TRAVEL: Travel-related needs (cards with travel benefits, travel insurance)
            - LOAN: General financing or loan requests (not specific to car/home/education)
            - SAVINGS: Savings accounts or deposit products
            - INVESTMENT: Investment products or wealth management
            - INSURANCE: Insurance or Takaful products
            - CAR: ONLY for auto financing, car loans, or vehicle purchase financing
            - HOME: Home financing or property-related products
            - EDUCATION: Education financing
            - BUSINESS: Business banking or SME products
            - PAYMENT: Credit cards, debit cards, payment solutions (includes cards with rewards, cashback, golf privileges, dining benefits, etc.)
            - GENERAL: General inquiry or unclear intent

            IMPORTANT CLASSIFICATION RULES:
            - If the request mentions "credit card" or "card" with benefits (golf, rewards, cashback, dining, etc.), use PAYMENT intent
            - If the request mentions "car loan" or "auto financing" or "vehicle financing", use CAR intent
            - Card benefits (golf, travel perks, cashback) = PAYMENT intent, NOT the benefit category

            Examples:
            - "I want golf privileges on credit card" -> PAYMENT intent
            - "credit card with cashback" -> PAYMENT intent
            - "I need a car loan" -> CAR intent
            - "financing for buying a car" -> CAR intent

            Response format:
            {
                "intent": "INTENT_NAME",
                "confidence": 0.0-1.0,
                "entities": {
                    "key": "value"
                }
            }

            Extract relevant entities like destination, amount, duration, etc.
            If the intent is unclear, use "GENERAL" with lower confidence.
            """;

    private final LLMConfig llmConfig;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${app.keywords.generation.system-prompt}")
    private String keywordSystemPrompt;

    @Value("${app.keywords.generation.max-keywords:10}")
    private int maxKeywords;

    @Value("${app.keywords.generation.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${app.ranking.llm.timeout-ms:15000}")
    private int rankingTimeoutMs;

    public LLMService(LLMConfig llmConfig, ObjectMapper objectMapper) {
        this.llmConfig = llmConfig;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder().build();
    }

    /**
     * Extract intent from user input
     */
    public Map<String, Object> extractIntent(String userInput, String language) {
        try {
            if ("azure".equalsIgnoreCase(llmConfig.getProvider())) {
                return extractIntentAzure(userInput, language);
            } else if ("ollama".equalsIgnoreCase(llmConfig.getProvider())) {
                return extractIntentOllama(userInput, language);
            } else {
                throw new IllegalArgumentException("Unknown LLM provider: " + llmConfig.getProvider());
            }
        } catch (Exception e) {
            log.error("LLM error: {}, using fallback", e.getMessage());
            return getFallbackIntent(userInput);
        }
    }

    /**
     * Extract intent using Azure OpenAI
     */
    private Map<String, Object> extractIntentAzure(String userInput, String language) {
        String url = String.format("%s/openai/deployments/%s/chat/completions?api-version=%s",
                llmConfig.getAzure().getEndpoint(),
                llmConfig.getAzure().getDeploymentName(),
                llmConfig.getAzure().getApiVersion());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("messages", Arrays.asList(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", String.format("Extract intent from this %s text: %s", language, userInput))
        ));
        requestBody.put("temperature", 0.3);
        requestBody.put("max_tokens", 200);
        requestBody.put("response_format", Map.of("type", "json_object"));

        try {
            String response = webClient.post()
                    .uri(url)
                    .header("api-key", llmConfig.getAzure().getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();

            return parseAzureResponse(response);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Azure OpenAI JSON parsing error: {}", e.getMessage());
            throw new RuntimeException("Failed to parse Azure response", e);
        } catch (Exception e) {
            log.error("Azure OpenAI error: {}", e.getMessage());
            throw new RuntimeException("Azure OpenAI request failed", e);
        }
    }

    /**
     * Extract intent using Ollama
     */
    private Map<String, Object> extractIntentOllama(String userInput, String language) {
        String url = llmConfig.getOllama().getHost() + "/api/generate";
        String prompt = String.format("%s\n\nExtract intent from this %s text: %s",
                SYSTEM_PROMPT, language, userInput);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", llmConfig.getOllama().getModel());
        requestBody.put("prompt", prompt);
        requestBody.put("format", "json");
        requestBody.put("stream", false);
        requestBody.put("options", Map.of(
                "temperature", 0.3,
                "num_predict", 200
        ));

        try {
            String response = webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(llmConfig.getOllama().getTimeout()))
                    .block();

            return parseOllamaResponse(response);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Ollama JSON parsing error: {}", e.getMessage());
            throw new RuntimeException("Failed to parse Ollama response", e);
        } catch (Exception e) {
            log.error("Ollama error: {}", e.getMessage());
            throw new RuntimeException("Ollama request failed", e);
        }
    }

    /**
     * Parse Azure OpenAI response
     */
    private Map<String, Object> parseAzureResponse(String response) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(response);
        String content = root.path("choices").get(0).path("message").path("content").asText();
        JsonNode intentData = objectMapper.readTree(content);
        return validateIntentResponse(intentData);
    }

    /**
     * Parse Ollama response
     */
    private Map<String, Object> parseOllamaResponse(String response) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(response);
        String content = root.path("response").asText();
        JsonNode intentData = objectMapper.readTree(content);
        return validateIntentResponse(intentData);
    }

    /**
     * Validate and normalize LLM response
     */
    private Map<String, Object> validateIntentResponse(JsonNode response) {
        Set<String> validIntents = Set.of(
                "TRAVEL", "LOAN", "SAVINGS", "INVESTMENT", "INSURANCE",
                "CAR", "HOME", "EDUCATION", "BUSINESS", "PAYMENT", "GENERAL"
        );

        String intent = response.path("intent").asText("GENERAL").toUpperCase();
        double confidence = response.path("confidence").asDouble(0.5);
        Map<String, Object> entities = new HashMap<>();

        // Parse entities if present
        if (response.has("entities")) {
            JsonNode entitiesNode = response.get("entities");
            entitiesNode.fields().forEachRemaining(entry ->
                entities.put(entry.getKey(), entry.getValue().asText())
            );
        }

        // Validate intent
        if (!validIntents.contains(intent)) {
            intent = "GENERAL";
            confidence = Math.min(confidence, 0.5);
        }

        // Clamp confidence between 0 and 1
        confidence = Math.max(0.0, Math.min(1.0, confidence));

        Map<String, Object> result = new HashMap<>();
        result.put("intent", intent);
        result.put("confidence", confidence);
        result.put("entities", entities);

        return result;
    }

    /**
     * Get fallback intent when LLM fails
     */
    private Map<String, Object> getFallbackIntent(String userInput) {
        String inputLower = userInput.toLowerCase();

        Map<String, List<String>> intentKeywords = Map.of(
                "TRAVEL", Arrays.asList("travel", "trip", "vacation", "flight", "hotel"),
                "LOAN", Arrays.asList("loan", "finance", "borrow", "credit"),
                "SAVINGS", Arrays.asList("save", "savings", "account", "deposit"),
                "INVESTMENT", Arrays.asList("invest", "fund", "portfolio", "wealth"),
                "INSURANCE", Arrays.asList("insurance", "takaful", "protect", "coverage"),
                "CAR", Arrays.asList("car", "auto", "vehicle", "drive"),
                "HOME", Arrays.asList("home", "house", "property", "mortgage"),
                "EDUCATION", Arrays.asList("education", "study", "university", "school"),
                "BUSINESS", Arrays.asList("business", "company", "sme", "corporate"),
                "PAYMENT", Arrays.asList("payment", "card", "pay", "transaction")
        );

        for (Map.Entry<String, List<String>> entry : intentKeywords.entrySet()) {
            if (entry.getValue().stream().anyMatch(inputLower::contains)) {
                Map<String, Object> result = new HashMap<>();
                result.put("intent", entry.getKey());
                result.put("confidence", 0.6);
                result.put("entities", new HashMap<>());
                return result;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("intent", "GENERAL");
        result.put("confidence", 0.3);
        result.put("entities", new HashMap<>());
        return result;
    }

    /**
     * Generate keywords for a staging product using LLM
     */
    public List<String> generateKeywords(StagingProduct product) {
        try {
            String userPrompt = buildKeywordPrompt(product);

            List<String> keywords;
            if ("azure".equalsIgnoreCase(llmConfig.getProvider())) {
                keywords = generateKeywordsAzure(userPrompt);
            } else if ("ollama".equalsIgnoreCase(llmConfig.getProvider())) {
                keywords = generateKeywordsOllama(userPrompt);
            } else {
                throw new IllegalArgumentException("Unknown LLM provider: " + llmConfig.getProvider());
            }

            // Quality check: if we got very few keywords or they're poor quality, use fallback
            if (keywords == null || keywords.isEmpty() || keywords.size() < 3) {
                log.warn("LLM returned {} keywords, using fallback instead",
                        keywords == null ? 0 : keywords.size());
                return getFallbackKeywords(product);
            }

            return keywords;
        } catch (Exception e) {
            log.error("Failed to generate keywords: {}", e.getMessage(), e);
            return getFallbackKeywords(product);
        }
    }

    /**
     * Build user prompt for keyword generation
     */
    private String buildKeywordPrompt(StagingProduct product) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze this financial product and generate search keywords.\n\n");
        prompt.append("Product Information:\n");
        prompt.append("- Name: ").append(product.getProductName()).append("\n");

        if (product.getCategory() != null) {
            prompt.append("- Category: ").append(product.getCategory()).append("\n");
        }
        if (product.getSubCategory() != null) {
            prompt.append("- Sub-category: ").append(product.getSubCategory()).append("\n");
        }
        if (product.getDescription() != null) {
            prompt.append("- Description: ").append(product.getDescription()).append("\n");
        }
        if (product.getIslamicStructure() != null) {
            prompt.append("- Islamic Structure: ").append(product.getIslamicStructure()).append("\n");
        }
        if (product.getAnnualRate() != null) {
            prompt.append("- Annual Rate: ").append(product.getAnnualRate()).append("%\n");
        }
        if (product.getAnnualFee() != null) {
            prompt.append("- Annual Fee: ").append(product.getAnnualFee()).append("\n");
        }
        if (product.getKeyBenefits() != null && !product.getKeyBenefits().isEmpty()) {
            prompt.append("- Key Benefits: ").append(String.join(", ", product.getKeyBenefits())).append("\n");
        }
        if (product.getShariaCertified() != null) {
            prompt.append("- Sharia Certified: ").append(product.getShariaCertified()).append("\n");
        }

        prompt.append("\nGenerate ").append(maxKeywords).append(" relevant keywords for this product.");

        return prompt.toString();
    }

    /**
     * Generate keywords using Azure OpenAI
     */
    private List<String> generateKeywordsAzure(String userPrompt) throws JsonProcessingException {
        String url = String.format("%s/openai/deployments/%s/chat/completions?api-version=%s",
                llmConfig.getAzure().getEndpoint(),
                llmConfig.getAzure().getDeploymentName(),
                llmConfig.getAzure().getApiVersion());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("messages", Arrays.asList(
                Map.of("role", "system", "content", keywordSystemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        requestBody.put("temperature", 0.3);
        requestBody.put("max_tokens", 200);

        String response = webClient.post()
                .uri(url)
                .header("api-key", llmConfig.getAzure().getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();

        return parseKeywordsFromAzureResponse(response);
    }

    /**
     * Generate keywords using Ollama
     */
    private List<String> generateKeywordsOllama(String userPrompt) throws JsonProcessingException {
        String url = llmConfig.getOllama().getHost() + "/api/generate";
        String fullPrompt = keywordSystemPrompt + "\n\n" + userPrompt;

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", llmConfig.getOllama().getModel());
        requestBody.put("prompt", fullPrompt);
        requestBody.put("format", "json");
        requestBody.put("stream", false);
        requestBody.put("options", Map.of(
                "temperature", 0.3,
                "num_predict", 200
        ));

        String response = webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();

        return parseKeywordsFromOllamaResponse(response);
    }

    /**
     * Parse keywords from Azure OpenAI response
     */
    private List<String> parseKeywordsFromAzureResponse(String response) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(response);
        String content = root.path("choices").get(0).path("message").path("content").asText();

        // Try to parse as JSON array
        try {
            List<String> keywords = objectMapper.readValue(content, new TypeReference<List<String>>() {});
            return keywords.stream()
                    .map(this::cleanKeyword)
                    .filter(k -> !k.isEmpty() && k.length() > 2)
                    .limit(maxKeywords)
                    .collect(Collectors.toList());
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse keywords as JSON array, trying to extract from text");
            return extractKeywordsFromText(content);
        }
    }

    /**
     * Parse keywords from Ollama response
     */
    private List<String> parseKeywordsFromOllamaResponse(String response) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(response);
        String content = root.path("response").asText();

        // Try to parse as JSON array
        try {
            List<String> keywords = objectMapper.readValue(content, new TypeReference<List<String>>() {});
            return keywords.stream()
                    .map(this::cleanKeyword)
                    .filter(k -> !k.isEmpty() && k.length() > 2)
                    .limit(maxKeywords)
                    .collect(Collectors.toList());
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse keywords as JSON array, trying to extract from text");
            return extractKeywordsFromText(content);
        }
    }

    /**
     * Extract keywords from text if JSON parsing fails
     */
    private List<String> extractKeywordsFromText(String text) {
        // Remove JSON formatting characters (brackets, braces, quotes, colons)
        text = text.replaceAll("[\\[\\]{}\"':]", "");

        // Split by comma and clean up
        return Arrays.stream(text.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .map(this::cleanKeyword)
                .filter(k -> !k.isEmpty() && k.length() > 2)
                .limit(maxKeywords)
                .collect(Collectors.toList());
    }

    /**
     * Clean keyword by removing unwanted suffixes like -1, -2, etc.
     * Also handles overly long concatenated keywords by rejecting them
     */
    private String cleanKeyword(String keyword) {
        // Remove trailing number suffixes like -1, -2, -3, etc.
        keyword = keyword.replaceAll("-\\d+$", "");

        // If keyword is too long (more than 30 characters), it's likely concatenated
        // Properly formatted keywords should be 1-3 words, max ~25 chars
        if (keyword.length() > 30) {
            log.warn("Keyword rejected - too long ({} chars): {}...",
                    keyword.length(),
                    keyword.length() > 50 ? keyword.substring(0, 50) : keyword);
            // Return empty to filter it out
            return "";
        }

        return keyword;
    }

    /**
     * Get fallback keywords when LLM fails
     */
    private List<String> getFallbackKeywords(StagingProduct product) {
        List<String> keywords = new ArrayList<>();

        // Add product name based keywords (first few words)
        if (product.getProductName() != null) {
            String[] nameWords = product.getProductName().toLowerCase()
                    .replaceAll("[^a-z0-9\\s-]", "")
                    .split("\\s+");
            for (int i = 0; i < Math.min(2, nameWords.length); i++) {
                if (nameWords[i].length() > 2) {
                    keywords.add(nameWords[i]);
                }
            }
        }

        // Add category-based keywords
        if (product.getCategory() != null) {
            keywords.add(product.getCategory().toLowerCase().replace("_", "-"));
        }

        // Add structure-based keywords
        if (product.getIslamicStructure() != null) {
            keywords.add(product.getIslamicStructure().toLowerCase());
        }

        // Always add generic Islamic banking keywords
        keywords.add("islamic-banking");
        keywords.add("sharia-compliant");

        // Add fee-based keywords
        if (product.getAnnualFee() != null && product.getAnnualFee().compareTo(java.math.BigDecimal.ZERO) == 0) {
            keywords.add("zero-fee");
        }

        // Add benefit-based keywords (extract meaningful words from benefits)
        if (product.getKeyBenefits() != null && !product.getKeyBenefits().isEmpty()) {
            product.getKeyBenefits().stream()
                    .flatMap(benefit -> Arrays.stream(benefit.toLowerCase()
                            .replaceAll("[^a-z0-9\\s-]", "")
                            .split("\\s+")))
                    .filter(word -> word.length() > 3 && word.length() <= 15)
                    .distinct()
                    .limit(3)
                    .forEach(keywords::add);
        }

        List<String> result = keywords.stream()
                .distinct()
                .filter(k -> k.length() <= 30) // Safety check
                .limit(maxKeywords)
                .collect(Collectors.toList());

        log.info("Using fallback keywords ({}): {}", result.size(), result);
        return result;
    }

    /**
     * Generate keywords from product data map (for production products)
     */
    public List<String> generateKeywordsFromMap(Map<String, Object> productData) {
        try {
            String userPrompt = buildKeywordPromptFromMap(productData);

            List<String> keywords;
            if ("azure".equalsIgnoreCase(llmConfig.getProvider())) {
                keywords = generateKeywordsAzure(userPrompt);
            } else if ("ollama".equalsIgnoreCase(llmConfig.getProvider())) {
                keywords = generateKeywordsOllama(userPrompt);
            } else {
                throw new IllegalArgumentException("Unknown LLM provider: " + llmConfig.getProvider());
            }

            // Quality check
            if (keywords == null || keywords.isEmpty() || keywords.size() < 3) {
                log.warn("LLM returned {} keywords, using fallback instead",
                        keywords == null ? 0 : keywords.size());
                return getFallbackKeywordsFromMap(productData);
            }

            return keywords;
        } catch (Exception e) {
            log.error("Failed to generate keywords: {}", e.getMessage(), e);
            return getFallbackKeywordsFromMap(productData);
        }
    }

    /**
     * Build user prompt for keyword generation from map
     */
    private String buildKeywordPromptFromMap(Map<String, Object> productData) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze this financial product and generate search keywords.\n\n");
        prompt.append("Product Information:\n");
        prompt.append("- Name: ").append(productData.get("productName")).append("\n");

        if (productData.get("category") != null) {
            prompt.append("- Category: ").append(productData.get("category")).append("\n");
        }
        if (productData.get("description") != null) {
            prompt.append("- Description: ").append(productData.get("description")).append("\n");
        }
        if (productData.get("islamicStructure") != null) {
            prompt.append("- Islamic Structure: ").append(productData.get("islamicStructure")).append("\n");
        }
        if (productData.get("keyBenefits") != null) {
            prompt.append("- Key Benefits: ").append(productData.get("keyBenefits")).append("\n");
        }

        return prompt.toString();
    }

    /**
     * Get fallback keywords from map when LLM fails
     */
    @SuppressWarnings("unchecked")
    private List<String> getFallbackKeywordsFromMap(Map<String, Object> productData) {
        List<String> keywords = new ArrayList<>();

        // Add product name based keywords
        if (productData.get("productName") != null) {
            String[] nameWords = productData.get("productName").toString().toLowerCase()
                    .replaceAll("[^a-z0-9\\s-]", "")
                    .split("\\s+");
            for (int i = 0; i < Math.min(2, nameWords.length); i++) {
                if (nameWords[i].length() > 2) {
                    keywords.add(nameWords[i]);
                }
            }
        }

        // Add category-based keywords
        if (productData.get("category") != null) {
            keywords.add(productData.get("category").toString().toLowerCase().replace("_", "-"));
        }

        // Add structure-based keywords
        if (productData.get("islamicStructure") != null) {
            keywords.add(productData.get("islamicStructure").toString().toLowerCase());
        }

        // Extract from benefits
        if (productData.get("keyBenefits") != null) {
            List<String> benefits = (List<String>) productData.get("keyBenefits");
            benefits.stream()
                    .flatMap(b -> Arrays.stream(b.toLowerCase().split("\\s+")))
                    .filter(word -> word.length() >= 4 && word.length() <= 15)
                    .distinct()
                    .limit(5)
                    .forEach(keywords::add);
        }

        List<String> result = keywords.stream()
                .distinct()
                .filter(k -> k.length() <= 30)
                .limit(maxKeywords)
                .collect(Collectors.toList());

        log.info("Using fallback keywords from map ({}): {}", result.size(), result);
        return result;
    }

    /**
     * Rank products using LLM based on user query and intent
     */
    public List<Map<String, Object>> rankProductsWithLLM(
            List<Product> products,
            String userInput,
            Map<String, Object> intentData) {

        log.info("LLM re-ranking {} products (timeout: {}ms)", products.size(), rankingTimeoutMs);

        try {
            String prompt = buildRankingPrompt(products, userInput, intentData);

            String response;
            if ("azure".equalsIgnoreCase(llmConfig.getProvider())) {
                response = rankProductsAzure(prompt);
            } else if ("ollama".equalsIgnoreCase(llmConfig.getProvider())) {
                response = rankProductsOllama(prompt);
            } else {
                throw new IllegalArgumentException("Unknown LLM provider: " + llmConfig.getProvider());
            }

            return parseRankingResponse(response, products);

        } catch (Exception e) {
            log.error("LLM ranking failed: {}", e.getMessage(), e);
            throw new RuntimeException("LLM ranking failed", e);
        }
    }

    /**
     * Build prompt for LLM ranking
     */
    private String buildRankingPrompt(List<Product> products, String userInput, Map<String, Object> intentData) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are a financial product recommendation expert for an Islamic banking system.\n");
        prompt.append("Analyze the user's query and rank the provided products based on relevance.\n\n");

        prompt.append("EVALUATION CRITERIA:\n");
        prompt.append("1. How well the product matches the user's specific needs and keywords\n");
        prompt.append("2. Islamic structure appropriateness for the use case\n");
        prompt.append("3. Product benefits alignment with user intent\n");
        prompt.append("4. Eligibility and practical suitability\n");
        prompt.append("5. Value proposition (fees, benefits, features)\n\n");

        prompt.append("USER QUERY: ").append(userInput).append("\n");
        prompt.append("DETECTED INTENT: ").append(intentData.get("intent"));
        prompt.append(" (confidence: ").append(String.format("%.0f%%", (Double) intentData.get("confidence") * 100)).append(")\n\n");

        prompt.append("PRODUCTS TO RANK:\n");
        for (int i = 0; i < products.size(); i++) {
            Product p = products.get(i);
            prompt.append("\n[Product ").append(i + 1).append("]\n");
            prompt.append("- Code: ").append(p.getProductCode()).append("\n");
            prompt.append("- Name: ").append(p.getProductName()).append("\n");
            prompt.append("- Category: ").append(p.getCategory()).append("\n");
            prompt.append("- Islamic Structure: ").append(p.getIslamicStructure() != null ? p.getIslamicStructure() : "Sharia-compliant").append("\n");

            if (p.getKeyBenefits() != null && !p.getKeyBenefits().isEmpty()) {
                prompt.append("- Key Benefits: ");
                List<String> topBenefits = p.getKeyBenefits().stream().limit(3).collect(Collectors.toList());
                prompt.append(String.join(", ", topBenefits)).append("\n");
            }

            if (p.getKeywords() != null && !p.getKeywords().isEmpty()) {
                prompt.append("- Keywords: ");
                List<String> topKeywords = p.getKeywords().stream().limit(5).collect(Collectors.toList());
                prompt.append(String.join(", ", topKeywords)).append("\n");
            }

            if (p.getAnnualFee() != null) {
                prompt.append("- Annual Fee: ").append(p.getAnnualFee()).append("\n");
            }
            if (p.getMinIncome() != null) {
                prompt.append("- Min Income: ").append(p.getMinIncome()).append("\n");
            }
        }

        prompt.append("\n\nOUTPUT REQUIREMENTS:\n");
        prompt.append("Return ONLY a valid JSON array with this exact format:\n");
        prompt.append("[\n");
        prompt.append("  {\n");
        prompt.append("    \"product_code\": \"CC_TRAVEL_01\",\n");
        prompt.append("    \"relevance_score\": 0.95,\n");
        prompt.append("    \"reason\": \"Excellent match - specific explanation of why this product suits the user's needs\"\n");
        prompt.append("  }\n");
        prompt.append("]\n\n");
        prompt.append("- relevance_score: 0.0 to 1.0 (how well it matches the query)\n");
        prompt.append("- reason: Clear, personalized explanation (1-2 sentences)\n");
        prompt.append("- Sort by relevance_score descending (best match first)\n");
        prompt.append("- Include ALL products in the response\n");

        return prompt.toString();
    }

    /**
     * Rank products using Azure OpenAI
     */
    private String rankProductsAzure(String prompt) {
        String url = String.format("%s/openai/deployments/%s/chat/completions?api-version=%s",
                llmConfig.getAzure().getEndpoint(),
                llmConfig.getAzure().getDeploymentName(),
                llmConfig.getAzure().getApiVersion());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("messages", Arrays.asList(
                Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("temperature", 0.3);
        requestBody.put("max_tokens", 1000);
        requestBody.put("response_format", Map.of("type", "json_object"));

        return webClient.post()
                .uri(url)
                .header("api-key", llmConfig.getAzure().getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(rankingTimeoutMs))
                .block();
    }

    /**
     * Rank products using Ollama
     */
    private String rankProductsOllama(String prompt) {
        String url = llmConfig.getOllama().getHost() + "/api/generate";

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", llmConfig.getOllama().getModel());
        requestBody.put("prompt", prompt);
        requestBody.put("format", "json");
        requestBody.put("stream", false);
        requestBody.put("options", Map.of(
                "temperature", 0.3,
                "num_predict", 1000
        ));

        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(rankingTimeoutMs))
                .block();
    }

    /**
     * Parse LLM ranking response
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseRankingResponse(String response, List<Product> products) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(response);

        // Extract content based on provider
        String content;
        if (root.has("choices")) {
            // Azure OpenAI format
            content = root.path("choices").get(0).path("message").path("content").asText();
        } else if (root.has("response")) {
            // Ollama format
            content = root.path("response").asText();
        } else {
            throw new RuntimeException("Unknown response format");
        }

        // Parse the ranking array
        List<Map<String, Object>> rankings;
        try {
            // Try to parse directly as array
            rankings = objectMapper.readValue(content, new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException e) {
            // If that fails, try to extract array from object
            JsonNode contentNode = objectMapper.readTree(content);
            if (contentNode.isArray()) {
                rankings = objectMapper.convertValue(contentNode, new TypeReference<List<Map<String, Object>>>() {});
            } else if (contentNode.has("rankings")) {
                rankings = objectMapper.convertValue(contentNode.get("rankings"), new TypeReference<List<Map<String, Object>>>() {});
            } else {
                throw new RuntimeException("Could not parse ranking response: " + content);
            }
        }

        // Map rankings back to products
        Map<String, Product> productMap = products.stream()
                .collect(Collectors.toMap(Product::getProductCode, p -> p));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> ranking : rankings) {
            String productCode = (String) ranking.get("product_code");
            Product product = productMap.get(productCode);

            if (product != null) {
                Map<String, Object> scoredProduct = new HashMap<>();
                scoredProduct.put("product", product);

                // Handle both Double and Integer scores
                Object scoreObj = ranking.get("relevance_score");
                double score = scoreObj instanceof Number ? ((Number) scoreObj).doubleValue() : 0.5;
                scoredProduct.put("score", score);

                scoredProduct.put("reason", ranking.get("reason"));
                result.add(scoredProduct);
            } else {
                log.warn("Product code not found in original list: {}", productCode);
            }
        }

        log.info("Successfully parsed {} ranked products from LLM response", result.size());
        return result;
    }
}
