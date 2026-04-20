package com.smartguide.scraper.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartguide.scraper.config.EnrichmentConfig;
import com.smartguide.scraper.config.EnrichmentConfig.EnrichmentMode;
import com.smartguide.scraper.dto.EnrichedCard;
import com.smartguide.scraper.dto.EnrichmentProgressEvent;
import com.smartguide.scraper.dto.EnrichmentProgressEvent.Phase;
import com.smartguide.scraper.dto.EnrichmentProgressEvent.ProductResult;
import com.smartguide.scraper.dto.ExtractedProduct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * AI enrichment service for CSS-extracted product cards.
 *
 * <p>Runs after {@code EnhancedScraperService.deduplicateByName()} and before
 * {@code StagingProductService.saveExtractedProducts()} in the configured-bank flow.
 * Sends product cards to Ollama in batches (default: 7 per call) or one-by-one
 * (SEQUENTIAL mode) and merges the LLM's category, confidence, and Sharia compliance
 * flags back into each {@link ExtractedProduct}.
 *
 * <p><strong>Keywords are intentionally NOT generated here.</strong>
 * Keyword generation remains a manual admin operation via
 * {@code POST /api/admin/staging/{id}/generate-keywords}.
 *
 * <p>All LLM calls use the same Ollama host and model as {@link AIProductExtractor},
 * configured via {@code OLLAMA_HOST} and {@code OLLAMA_MODEL} environment variables.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CardEnrichmentService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final EnrichmentConfig config;

    @Value("${OLLAMA_HOST:http://localhost:11434}")
    private String ollamaHost;

    @Value("${OLLAMA_MODEL:llama3.2}")
    private String ollamaModel;

    @Value("${SCRAPER_LLM_MAX_TOKENS:6000}")
    private int maxTokens;

    /** Valid Islamic banking categories aligned with the main app's RulesEngine taxonomy. */
    static final Set<String> VALID_CATEGORIES = Set.of(
            "COVERED_CARDS", "DEBIT_CARDS", "CHARGE_CARDS",
            "HOME_FINANCE", "PERSONAL_FINANCE", "AUTO_FINANCE",
            "TAKAFUL", "SAVINGS", "CURRENT_ACCOUNTS", "INVESTMENTS"
    );

    /**
     * Sharia non-compliant terms and their correct Islamic finance equivalents.
     * Used both in prompt instructions and in the post-processing compliance scan.
     */
    static final Map<String, String> SHARIA_VIOLATIONS = Map.of(
            "interest", "profit rate",
            "loan", "finance",
            "mortgage", "home finance",
            "insurance", "Takaful"
    );

    /**
     * Enriches a list of extracted products with AI-generated metadata.
     *
     * <p>Products are modified in place. The method delegates to
     * {@link #enrichBatch} or {@link #enrichSequential} depending on
     * {@link EnrichmentConfig#getMode()}.
     *
     * @param products         the products to enrich; modified in place
     * @param progressCallback invoked after each batch/product completes; may be {@code null}
     * @param cancellationFlag checked before each batch; return {@code true} to stop early;
     *                         may be {@code null} (treated as never-cancelled)
     * @return the same list with enrichment fields populated
     */
    public List<ExtractedProduct> enrich(
            List<ExtractedProduct> products,
            Consumer<EnrichmentProgressEvent> progressCallback,
            java.util.function.BooleanSupplier cancellationFlag) {

        if (products == null || products.isEmpty()) {
            return products != null ? products : new ArrayList<>();
        }

        if (config.getMode() == EnrichmentMode.BATCH) {
            return enrichBatch(products, progressCallback, cancellationFlag);
        } else {
            return enrichSequential(products, progressCallback, cancellationFlag);
        }
    }

    // -------------------------------------------------------------------------
    // BATCH mode
    // -------------------------------------------------------------------------

    private List<ExtractedProduct> enrichBatch(
            List<ExtractedProduct> products,
            Consumer<EnrichmentProgressEvent> progressCallback,
            java.util.function.BooleanSupplier cancellationFlag) {

        List<List<ExtractedProduct>> batches = partition(products, config.getBatchSize());
        int totalBatches = batches.size();
        int productsProcessedSoFar = 0;

        for (int i = 0; i < totalBatches; i++) {
            if (cancellationFlag != null && cancellationFlag.getAsBoolean()) {
                log.info("Enrichment cancelled after {} of {} batches", i, totalBatches);
                break;
            }

            List<ExtractedProduct> batch = batches.get(i);
            int batchNum = i + 1;
            log.info("Enriching batch {}/{} ({} products)", batchNum, totalBatches, batch.size());

            try {
                String prompt = buildBatchPrompt(batch);
                String response = callLLM(prompt);
                List<EnrichedCard> enriched = parseBatchResponse(response);
                mergeBatchResults(batch, enriched);
            } catch (Exception e) {
                log.error("Batch {}/{} enrichment failed: {}", batchNum, totalBatches, e.getMessage());
                markBatchFailed(batch);
            }

            for (ExtractedProduct product : batch) {
                validateCategory(product);
                checkShariaCompliance(product);
            }

            productsProcessedSoFar += batch.size();

            if (progressCallback != null) {
                emitBatchEvent(progressCallback, batch, batchNum, totalBatches, productsProcessedSoFar);
            }
        }

        return products;
    }

    // -------------------------------------------------------------------------
    // SEQUENTIAL mode
    // -------------------------------------------------------------------------

    private List<ExtractedProduct> enrichSequential(
            List<ExtractedProduct> products,
            Consumer<EnrichmentProgressEvent> progressCallback,
            java.util.function.BooleanSupplier cancellationFlag) {

        int total = products.size();

        for (int i = 0; i < total; i++) {
            if (cancellationFlag != null && cancellationFlag.getAsBoolean()) {
                log.info("Enrichment cancelled after {} of {} products", i, total);
                break;
            }

            ExtractedProduct product = products.get(i);

            try {
                String prompt = buildSequentialPrompt(product);
                String response = callLLM(prompt);
                EnrichedCard enriched = parseSequentialResponse(response);
                if (enriched != null) {
                    mergeEnrichment(product, enriched);
                } else {
                    markProductFailed(product);
                }
            } catch (Exception e) {
                log.error("Sequential enrichment failed for '{}': {}",
                        product.getProductName(), e.getMessage());
                markProductFailed(product);
            }

            validateCategory(product);
            checkShariaCompliance(product);

            if (progressCallback != null) {
                emitSequentialEvent(progressCallback, product, i + 1, total);
            }
        }

        return products;
    }

    // -------------------------------------------------------------------------
    // Prompt builders — NO keywords in any prompt
    // -------------------------------------------------------------------------

    /**
     * Builds the BATCH enrichment prompt for N products.
     *
     * <p>The prompt instructs the LLM to classify each product into one of the 10
     * valid Islamic banking categories, assign a confidence score, flag Sharia
     * non-compliant terminology, and identify the Islamic financing structure.
     * Keywords are intentionally excluded.
     *
     * @param batch the products to include in this prompt
     * @return the complete prompt string ready to send to Ollama
     */
    String buildBatchPrompt(List<ExtractedProduct> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are an Islamic banking product classification assistant.

                For each product below, determine:
                1. The Islamic banking category (EXACTLY one of: COVERED_CARDS, DEBIT_CARDS, CHARGE_CARDS, HOME_FINANCE, PERSONAL_FINANCE, AUTO_FINANCE, TAKAFUL, SAVINGS, CURRENT_ACCOUNTS, INVESTMENTS)
                2. Your confidence in the category assignment (0.0 to 1.0)
                3. The Islamic financing structure if identifiable (Murabaha, Ijarah, Tawarruq, Wakala, Musharakah, etc.) — null if not mentioned
                4. The target customer segment (1 sentence) — null if unclear
                5. Any Sharia non-compliant terminology found in the product text:
                   - "interest" should be "profit rate"
                   - "loan" should be "finance"
                   - "mortgage" should be "home finance"
                   - "insurance" should be "Takaful"
                   List each violation found. If none, return an empty array.

                IMPORTANT TERMINOLOGY: Always use "profit rate" not "interest", "finance" not "loan",
                "home finance" not "mortgage", "Takaful" not "insurance". All products are Sharia-compliant.

                PRODUCTS TO CLASSIFY:

                """);

        for (int i = 0; i < batch.size(); i++) {
            ExtractedProduct p = batch.get(i);
            sb.append(i + 1).append(". Name: \"").append(escapeJson(p.getProductName())).append("\"\n");
            if (p.getDescription() != null && !p.getDescription().isBlank()) {
                sb.append("   Description: \"").append(escapeJson(p.getDescription())).append("\"\n");
            }
            if (p.getKeyBenefits() != null && !p.getKeyBenefits().isEmpty()) {
                String benefits = p.getKeyBenefits().stream()
                        .map(this::escapeJson)
                        .map(b -> "\"" + b + "\"")
                        .collect(Collectors.joining(", "));
                sb.append("   Benefits: [").append(benefits).append("]\n");
            }
            sb.append("\n");
        }

        sb.append("""
                Return ONLY a JSON array with one object per product, in the SAME ORDER as the input:
                [
                  {
                    "product_name": "exact name from input",
                    "category": "CATEGORY_NAME",
                    "confidence": 0.85,
                    "islamic_structure": "Murabaha",
                    "target_customer": "Premium customers seeking daily spending rewards",
                    "sharia_violations": []
                  }
                ]

                JSON OUTPUT:
                """);

        return sb.toString();
    }

    /**
     * Builds the SEQUENTIAL enrichment prompt for a single product.
     *
     * <p>Identical structure to the batch prompt but for one product.
     * Keywords are intentionally excluded.
     *
     * @param product the single product to classify
     * @return the complete prompt string ready to send to Ollama
     */
    String buildSequentialPrompt(ExtractedProduct product) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are an Islamic banking product classification assistant.

                Classify this product:

                """);

        sb.append("Name: \"").append(escapeJson(product.getProductName())).append("\"\n");
        if (product.getDescription() != null && !product.getDescription().isBlank()) {
            sb.append("Description: \"").append(escapeJson(product.getDescription())).append("\"\n");
        }
        if (product.getKeyBenefits() != null && !product.getKeyBenefits().isEmpty()) {
            String benefits = product.getKeyBenefits().stream()
                    .map(this::escapeJson)
                    .map(b -> "\"" + b + "\"")
                    .collect(Collectors.joining(", "));
            sb.append("Benefits: [").append(benefits).append("]\n");
        }

        sb.append("""

                Determine:
                1. Category: EXACTLY one of COVERED_CARDS, DEBIT_CARDS, CHARGE_CARDS, HOME_FINANCE, PERSONAL_FINANCE, AUTO_FINANCE, TAKAFUL, SAVINGS, CURRENT_ACCOUNTS, INVESTMENTS
                2. Confidence (0.0 to 1.0)
                3. Islamic financing structure if identifiable (Murabaha, Ijarah, Tawarruq, Wakala, etc.) — null if not mentioned
                4. Target customer (1 sentence) — null if unclear
                5. Sharia non-compliant terms found:
                   "interest" -> "profit rate", "loan" -> "finance",
                   "mortgage" -> "home finance", "insurance" -> "Takaful"
                   Empty array if none found.

                IMPORTANT TERMINOLOGY: Use "profit rate" not "interest", "finance" not "loan",
                "home finance" not "mortgage", "Takaful" not "insurance".

                Return ONLY a JSON object:
                {
                  "product_name": "exact name from input",
                  "category": "CATEGORY_NAME",
                  "confidence": 0.85,
                  "islamic_structure": null,
                  "target_customer": "...",
                  "sharia_violations": []
                }

                JSON OUTPUT:
                """);

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // LLM call with retry
    // -------------------------------------------------------------------------

    /**
     * Calls the Ollama LLM API with the given prompt.
     *
     * <p>Implements the retry policy from {@link EnrichmentConfig}: if
     * {@code retryOnFailure} is true, each failure is retried up to {@code maxRetries}
     * times with a 2-second pause between attempts. The timeout is applied per attempt
     * via {@link reactor.core.publisher.Mono#timeout}.
     *
     * @param prompt the prompt to send
     * @return the LLM response text
     * @throws RuntimeException if all attempts fail
     */
    String callLLM(String prompt) {
        int attempts = config.isRetryOnFailure() ? config.getMaxRetries() + 1 : 1;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                Map<String, Object> request = Map.of(
                        "model", ollamaModel,
                        "prompt", prompt,
                        "stream", false,
                        "options", Map.of(
                                "temperature", 0.1,
                                "num_predict", maxTokens
                        )
                );

                @SuppressWarnings("unchecked")
                Map<String, Object> response = webClient.post()
                        .uri(ollamaHost + "/api/generate")
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .timeout(Duration.ofSeconds(config.getLlmTimeoutSeconds()))
                        .block();

                if (response != null && response.containsKey("response")) {
                    String text = response.get("response").toString();
                    log.debug("LLM response received for enrichment: {} chars (attempt {}/{})",
                            text.length(), attempt, attempts);
                    return text;
                }
                log.warn("LLM returned empty response on attempt {}/{}", attempt, attempts);

            } catch (Exception e) {
                log.error("LLM enrichment call failed on attempt {}/{}: {}",
                        attempt, attempts, e.getMessage());
                if (attempt < attempts) {
                    log.info("Retrying LLM enrichment call in 2 seconds...");
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during LLM retry", ie);
                    }
                } else {
                    throw new RuntimeException(
                            "LLM enrichment failed after " + attempts + " attempt(s): " + e.getMessage(), e);
                }
            }
        }
        throw new RuntimeException("LLM enrichment failed: no response received");
    }

    // -------------------------------------------------------------------------
    // Response parsing
    // -------------------------------------------------------------------------

    /**
     * Parses the LLM response for a BATCH prompt into a list of {@link EnrichedCard} objects.
     *
     * <p>Parsing strategy:
     * <ol>
     *   <li>Strip markdown code fences.</li>
     *   <li>Extract the outermost JSON array using bracket-depth matching.</li>
     *   <li>Attempt a full-array parse via a lenient {@link ObjectMapper}.</li>
     *   <li>On failure, fall back to per-object salvage (recover individually complete
     *       {@code {...}} blocks from a truncated array).</li>
     * </ol>
     *
     * @param response the raw LLM output string
     * @return list of parsed {@link EnrichedCard}; empty if parsing fails completely
     */
    List<EnrichedCard> parseBatchResponse(String response) {
        if (response == null || response.isBlank()) {
            log.warn("Batch enrichment: LLM returned null or empty response");
            return new ArrayList<>();
        }

        ObjectMapper lenient = lenientMapper();

        // Strip markdown code fences first, then search for array content
        String cleaned = response.replaceAll("(?s)```(?:json)?\\s*", "").replace("```", "").trim();

        // Find the start of the JSON array
        int arrayStart = cleaned.indexOf('[');
        if (arrayStart < 0) {
            log.warn("Batch enrichment: no JSON array found in LLM response (length={}). "
                    + "First 300 chars: {}",
                    response.length(),
                    response.substring(0, Math.min(300, response.length())));
            return new ArrayList<>();
        }

        // Extract the complete JSON array (if balanced) for primary parse attempt
        String completeJson = extractJsonArray(response);
        if (completeJson != null && !completeJson.isBlank()) {
            // Primary parse: try the complete array
            try {
                List<Map<String, Object>> rawList = lenient.readValue(
                        completeJson, new TypeReference<List<Map<String, Object>>>() {});
                List<EnrichedCard> cards = new ArrayList<>();
                for (Map<String, Object> raw : rawList) {
                    EnrichedCard card = mapToEnrichedCard(raw);
                    if (card != null) {
                        cards.add(card);
                    }
                }
                log.debug("Batch enrichment: parsed {} enriched cards from LLM response", cards.size());
                return cards;
            } catch (Exception e) {
                log.warn("Batch enrichment: full-array parse failed ({}), attempting per-object salvage",
                        e.getMessage());
            }
        } else {
            // Array is truncated (no closing ']') — go straight to salvage
            log.warn("Batch enrichment: JSON array appears truncated — attempting per-object salvage");
        }

        // Fallback: salvage individually complete objects from the cleaned content
        return salvageObjects(cleaned.substring(arrayStart), lenient);
    }

    /**
     * Parses the LLM response for a SEQUENTIAL prompt into a single {@link EnrichedCard}.
     *
     * <p>Extracts the outermost JSON object using {@code {}/{}} bracket-depth matching,
     * then maps it to an {@link EnrichedCard} via safe type-conversion helpers.
     *
     * @param response the raw LLM output string
     * @return the parsed {@link EnrichedCard}, or {@code null} if parsing fails
     */
    EnrichedCard parseSequentialResponse(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        ObjectMapper lenient = lenientMapper();
        String json = extractJsonObject(response);
        if (json == null || json.isBlank()) {
            log.warn("Sequential enrichment: no JSON object found in LLM response");
            return null;
        }
        try {
            Map<String, Object> raw = lenient.readValue(
                    json, new TypeReference<Map<String, Object>>() {});
            return mapToEnrichedCard(raw);
        } catch (Exception e) {
            log.warn("Sequential enrichment: failed to parse LLM response — {}", e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Merging enrichment into ExtractedProduct
    // -------------------------------------------------------------------------

    private void mergeBatchResults(List<ExtractedProduct> batch, List<EnrichedCard> enriched) {
        for (ExtractedProduct product : batch) {
            String normalizedName = normalize(product.getProductName());
            EnrichedCard match = enriched.stream()
                    .filter(c -> c.getProductName() != null
                            && normalize(c.getProductName()).equals(normalizedName))
                    .findFirst()
                    .orElse(null);

            if (match != null) {
                mergeEnrichment(product, match);
            } else {
                log.warn("No LLM enrichment match for product '{}' — marking as failed",
                        product.getProductName());
                markProductFailed(product);
            }
        }
    }

    /**
     * Copies enrichment fields from an {@link EnrichedCard} to an {@link ExtractedProduct}.
     *
     * <p>Confidence is clamped to [0.0, 1.0]. The {@code aiSuggestedCategory} is set to the
     * same value as {@code category} so the admin can see the original AI suggestion even after
     * manual override in staging.
     */
    private void mergeEnrichment(ExtractedProduct product, EnrichedCard enriched) {
        product.setCategory(enriched.getCategory());

        double confidence = enriched.getConfidence() != null ? enriched.getConfidence() : 0.0;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        product.setConfidenceScore(confidence);

        if (enriched.getIslamicStructure() != null) {
            product.setIslamicStructure(enriched.getIslamicStructure());
        }

        // Append LLM-reported Sharia violations to review notes
        if (enriched.getShariaViolations() != null && !enriched.getShariaViolations().isEmpty()) {
            String note = "LLM-reported Sharia issues: "
                    + String.join("; ", enriched.getShariaViolations());
            appendReviewNote(product, note);
        }
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    /**
     * Validates that the product's category is one of the 10 allowed Islamic banking categories.
     *
     * <p>If the category is invalid or null (after a failed LLM call), the category is set
     * to {@code null}, confidence to {@code 0.0}, and a note is appended to {@code reviewNotes}.
     *
     * @param product the product to validate
     */
    void validateCategory(ExtractedProduct product) {
        String category = product.getCategory();
        if (category == null) {
            // Already handled by markProductFailed — don't double-annotate
            return;
        }
        String upper = category.toUpperCase();
        if (!VALID_CATEGORIES.contains(upper)) {
            String note = String.format(
                    "AI returned invalid category '%s' -- manual categorisation required", category);
            product.setCategory(null);
            product.setConfidenceScore(0.0);
            appendReviewNote(product, note);
            log.warn("Invalid category '{}' for product '{}' — cleared", category,
                    product.getProductName());
        } else {
            // Normalise to uppercase to match the taxonomy
            product.setCategory(upper);
        }
    }

    /**
     * Scans the product's description and key benefits for Sharia non-compliant terminology.
     *
     * <p>Uses word-boundary matching to avoid false positives (e.g., "interesting" does
     * not match "interest"). When violations are found, a compliance warning is appended to
     * {@code reviewNotes} so the admin can correct the text before approving.
     *
     * @param product the product to scan
     */
    void checkShariaCompliance(ExtractedProduct product) {
        List<String> violations = new ArrayList<>();

        String description = product.getDescription();
        if (description != null) {
            for (Map.Entry<String, String> entry : SHARIA_VIOLATIONS.entrySet()) {
                if (description.toLowerCase().matches(".*\\b" + entry.getKey() + "\\b.*")) {
                    violations.add("'" + entry.getKey() + "' found in description (should be '"
                            + entry.getValue() + "')");
                }
            }
        }

        if (product.getKeyBenefits() != null) {
            for (String benefit : product.getKeyBenefits()) {
                for (Map.Entry<String, String> entry : SHARIA_VIOLATIONS.entrySet()) {
                    if (benefit.toLowerCase().matches(".*\\b" + entry.getKey() + "\\b.*")) {
                        violations.add("'" + entry.getKey() + "' found in benefits (should be '"
                                + entry.getValue() + "')");
                    }
                }
            }
        }

        if (!violations.isEmpty()) {
            String note = "SHARIA COMPLIANCE: " + String.join("; ", violations);
            appendReviewNote(product, note);
            log.warn("Sharia compliance issues found for '{}': {}", product.getProductName(), note);
        }
    }

    // -------------------------------------------------------------------------
    // Failure marking
    // -------------------------------------------------------------------------

    private void markBatchFailed(List<ExtractedProduct> batch) {
        for (ExtractedProduct product : batch) {
            markProductFailed(product);
        }
    }

    private void markProductFailed(ExtractedProduct product) {
        product.setConfidenceScore(0.0);
        appendReviewNote(product, "AI enrichment failed -- manual categorisation required");
    }

    // -------------------------------------------------------------------------
    // Progress event emission
    // -------------------------------------------------------------------------

    private void emitBatchEvent(Consumer<EnrichmentProgressEvent> callback,
                                List<ExtractedProduct> batch,
                                int batchNum, int totalBatches, int productsProcessedSoFar) {
        List<ProductResult> results = batch.stream()
                .map(p -> ProductResult.builder()
                        .productName(p.getProductName())
                        .category(p.getCategory())
                        .aiConfidence(p.getConfidenceScore())
                        .keywordsCount(0) // keywords not generated during enrichment
                        .shariaFlag(p.getReviewNotes() != null
                                && p.getReviewNotes().contains("SHARIA COMPLIANCE"))
                        .error(p.getConfidenceScore() != null && p.getConfidenceScore() == 0.0
                                && p.getCategory() == null ? "AI enrichment failed" : null)
                        .build())
                .collect(Collectors.toList());

        long highConfidence = batch.stream()
                .filter(p -> p.getConfidenceScore() != null && p.getConfidenceScore() >= 0.85)
                .count();
        long needsReview = batch.stream()
                .filter(p -> p.getConfidenceScore() != null && p.getConfidenceScore() < 0.85
                        && p.getCategory() != null)
                .count();

        EnrichmentProgressEvent event = EnrichmentProgressEvent.builder()
                .timestamp(LocalDateTime.now())
                .phase(Phase.ENRICHING)
                .batchNumber(batchNum)
                .totalBatches(totalBatches)
                .productsProcessed(productsProcessedSoFar)
                .message(String.format("Batch %d of %d complete. %d high confidence, %d needs review.",
                        batchNum, totalBatches, highConfidence, needsReview))
                .products(results)
                .build();

        callback.accept(event);
    }

    private void emitSequentialEvent(Consumer<EnrichmentProgressEvent> callback,
                                     ExtractedProduct product,
                                     int processed, int total) {
        ProductResult result = ProductResult.builder()
                .productName(product.getProductName())
                .category(product.getCategory())
                .aiConfidence(product.getConfidenceScore())
                .keywordsCount(0) // keywords not generated during enrichment
                .shariaFlag(product.getReviewNotes() != null
                        && product.getReviewNotes().contains("SHARIA COMPLIANCE"))
                .error(product.getConfidenceScore() != null && product.getConfidenceScore() == 0.0
                        && product.getCategory() == null ? "AI enrichment failed" : null)
                .build();

        EnrichmentProgressEvent event = EnrichmentProgressEvent.builder()
                .timestamp(LocalDateTime.now())
                .phase(Phase.ENRICHING)
                .batchNumber(processed)
                .totalBatches(total)
                .productsProcessed(processed)
                .message(String.format("Processed product %d of %d: %s",
                        processed, total, product.getProductName()))
                .products(List.of(result))
                .build();

        callback.accept(event);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Appends a note to the product's {@code reviewNotes} field.
     * Multiple notes are separated by {@code " | "}.
     */
    private void appendReviewNote(ExtractedProduct product, String note) {
        String existing = product.getReviewNotes();
        if (existing == null || existing.isBlank()) {
            product.setReviewNotes(note);
        } else {
            product.setReviewNotes(existing + " | " + note);
        }
    }

    /**
     * Partitions a list into sublists of at most {@code size} elements.
     * The last partition may be smaller than {@code size}.
     */
    <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    /**
     * Extracts the outermost JSON array from an LLM response string.
     * Strips markdown code fences before searching.
     */
    private String extractJsonArray(String response) {
        if (response == null) return null;
        response = response.replaceAll("(?s)```(?:json)?\\s*", "").replace("```", "").trim();

        int start = response.indexOf('[');
        if (start < 0) return null;

        int depth = 0;
        for (int i = start; i < response.length(); i++) {
            char c = response.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return response.substring(start, i + 1);
            }
        }
        int end = response.lastIndexOf(']');
        return end > start ? response.substring(start, end + 1) : null;
    }

    /**
     * Extracts the outermost JSON object from an LLM response string.
     * Strips markdown code fences before searching.
     */
    private String extractJsonObject(String response) {
        if (response == null) return null;
        response = response.replaceAll("(?s)```(?:json)?\\s*", "").replace("```", "").trim();

        int start = response.indexOf('{');
        if (start < 0) return null;

        int depth = 0;
        for (int i = start; i < response.length(); i++) {
            char c = response.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return response.substring(start, i + 1);
            }
        }
        return null;
    }

    /**
     * Salvages individually complete JSON objects from a (possibly truncated) JSON array string.
     * Used as a fallback when the full-array parse fails.
     */
    private List<EnrichedCard> salvageObjects(String json, ObjectMapper lenient) {
        List<EnrichedCard> salvaged = new ArrayList<>();
        int i = 0;
        while (i < json.length()) {
            int start = json.indexOf('{', i);
            if (start < 0) break;

            int depth = 0;
            int end = -1;
            for (int j = start; j < json.length(); j++) {
                char c = json.charAt(j);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        end = j;
                        break;
                    }
                }
            }
            if (end < 0) break;

            String objStr = json.substring(start, end + 1);
            try {
                Map<String, Object> raw = lenient.readValue(
                        objStr, new TypeReference<Map<String, Object>>() {});
                if (raw.containsKey("product_name")) {
                    EnrichedCard card = mapToEnrichedCard(raw);
                    if (card != null) {
                        salvaged.add(card);
                    }
                }
            } catch (Exception ignore) {
                // Skip malformed single object
            }
            i = end + 1;
        }

        if (!salvaged.isEmpty()) {
            log.warn("Batch enrichment: salvaged {} objects from truncated LLM response",
                    salvaged.size());
        } else {
            log.error("Batch enrichment: could not parse any products from LLM response");
        }
        return salvaged;
    }

    /**
     * Maps a raw JSON map (from the LLM response) to an {@link EnrichedCard}.
     * Uses safe type-conversion helpers to handle LLM output quirks.
     */
    private EnrichedCard mapToEnrichedCard(Map<String, Object> raw) {
        String productName = getString(raw, "product_name");
        if (productName == null || productName.isBlank()) {
            return null;
        }
        return EnrichedCard.builder()
                .productName(productName)
                .category(getString(raw, "category"))
                .confidence(getDouble(raw, "confidence"))
                .islamicStructure(getString(raw, "islamic_structure"))
                .targetCustomer(getString(raw, "target_customer"))
                .shariaViolations(getStringList(raw, "sharia_violations"))
                .build();
    }

    private ObjectMapper lenientMapper() {
        return objectMapper.copy()
                .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
                .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
                .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private String normalize(String input) {
        return input == null ? "" : input.toLowerCase()
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Minimal JSON escaping for string values embedded in prompt text. */
    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private Double getDouble(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value != null) {
            try {
                return Double.parseDouble(value.toString());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return new ArrayList<>();
        if (value instanceof List) {
            return ((List<?>) value).stream()
                    .filter(item -> item != null && !item.toString().isBlank())
                    .map(Object::toString)
                    .collect(Collectors.toList());
        }
        // Single string — wrap in a list
        String str = value.toString().trim();
        if (str.isBlank()) return new ArrayList<>();
        return List.of(str);
    }
}
