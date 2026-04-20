package com.smartguide.scraper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartguide.scraper.config.EnrichmentConfig;
import com.smartguide.scraper.config.EnrichmentConfig.EnrichmentMode;
import com.smartguide.scraper.dto.EnrichedCard;
import com.smartguide.scraper.dto.EnrichmentProgressEvent;
import com.smartguide.scraper.dto.ExtractedProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CardEnrichmentService}.
 *
 * <p>All tests mock {@code callLLM()} to avoid real network calls to Ollama.
 * The service is constructed as a real object (not mocked) so its enrichment logic
 * is exercised end-to-end.
 *
 * <p>Keywords are deliberately absent from all assertions — keyword generation is
 * a manual admin operation and must not appear in any enrichment output.
 */
@ExtendWith(MockitoExtension.class)
class CardEnrichmentServiceTest {

    @Mock
    private WebClient webClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EnrichmentConfig config;
    private CardEnrichmentService service;

    @BeforeEach
    void setUp() {
        config = new EnrichmentConfig();
        config.setMode(EnrichmentMode.BATCH);
        config.setBatchSize(7);
        config.setLlmTimeoutSeconds(30);
        config.setRetryOnFailure(false); // disable retry by default for faster tests
        config.setMaxRetries(0);

        // Use a spy so we can stub callLLM() without needing a real Ollama
        service = spy(new CardEnrichmentService(webClient, objectMapper, config));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ExtractedProduct product(String name) {
        return ExtractedProduct.builder()
                .productName(name)
                .description("A Sharia-compliant banking product.")
                .keyBenefits(List.of("Benefit 1", "Benefit 2"))
                .sourceUrl("https://dib.ae/personal/cards/" + name.toLowerCase().replace(" ", "-"))
                .build();
    }

    private ExtractedProduct productWithDescription(String name, String description) {
        return ExtractedProduct.builder()
                .productName(name)
                .description(description)
                .keyBenefits(List.of())
                .sourceUrl("https://dib.ae/personal/cards/" + name.toLowerCase().replace(" ", "-"))
                .build();
    }

    private ExtractedProduct productWithBenefits(String name, List<String> benefits) {
        return ExtractedProduct.builder()
                .productName(name)
                .description("A banking product.")
                .keyBenefits(benefits)
                .sourceUrl("https://dib.ae/personal/cards/" + name.toLowerCase().replace(" ", "-"))
                .build();
    }

    private String batchJsonFor(List<ExtractedProduct> products, String category) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < products.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format(
                    "{\"product_name\":\"%s\",\"category\":\"%s\","
                            + "\"confidence\":0.9,\"islamic_structure\":\"Murabaha\","
                            + "\"target_customer\":\"UAE nationals\",\"sharia_violations\":[]}",
                    products.get(i).getProductName().replace("\"", "\\\""), category));
        }
        sb.append("]");
        return sb.toString();
    }

    private String singleJson(String name, String category, double confidence) {
        return String.format(
                "{\"product_name\":\"%s\",\"category\":\"%s\","
                        + "\"confidence\":%.2f,\"islamic_structure\":null,"
                        + "\"target_customer\":null,\"sharia_violations\":[]}",
                name.replace("\"", "\\\""), category, confidence);
    }

    // -------------------------------------------------------------------------
    // enrich — batch mode
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enrich_batchMode_enrichesAllProducts — 14 products, batchSize=7 -> 2 LLM calls, all enriched")
    void enrich_batchMode_enrichesAllProducts() throws Exception {
        List<ExtractedProduct> products = new ArrayList<>();
        for (int i = 1; i <= 14; i++) {
            products.add(product("Card Product " + i));
        }

        // Stub callLLM to return a valid batch response for each of the two batches
        doReturn(batchJsonFor(products.subList(0, 7), "COVERED_CARDS"))
                .doReturn(batchJsonFor(products.subList(7, 14), "DEBIT_CARDS"))
                .when(service).callLLM(anyString());

        service.enrich(products, null, null);

        verify(service, times(2)).callLLM(anyString());
        assertThat(products.subList(0, 7)).allSatisfy(p -> {
            assertThat(p.getCategory()).isEqualTo("COVERED_CARDS");
            assertThat(p.getConfidenceScore()).isEqualTo(0.9);
        });
        assertThat(products.subList(7, 14)).allSatisfy(p -> {
            assertThat(p.getCategory()).isEqualTo("DEBIT_CARDS");
            assertThat(p.getConfidenceScore()).isEqualTo(0.9);
        });
    }

    @Test
    @DisplayName("enrich_emptyList_returnsEmpty — empty input list returns empty list without LLM call")
    void enrich_emptyList_returnsEmpty() throws Exception {
        List<ExtractedProduct> result = service.enrich(new ArrayList<>(), null, null);

        assertThat(result).isEmpty();
        verify(service, never()).callLLM(anyString());
    }

    @Test
    @DisplayName("enrich_nullList_returnsEmpty — null input returns empty list without NPE")
    void enrich_nullList_returnsEmpty() throws Exception {
        List<ExtractedProduct> result = service.enrich(null, null, null);

        assertThat(result).isEmpty();
        verify(service, never()).callLLM(anyString());
    }

    @Test
    @DisplayName("enrich_batchMode_oneBatchFails_otherBatchesSucceed — batch 2 fails; batches 1 and 3 enriched")
    void enrich_batchMode_oneBatchFails_otherBatchesSucceed() throws Exception {
        config.setBatchSize(3);
        List<ExtractedProduct> products = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            products.add(product("Card " + i));
        }

        String goodResponse = batchJsonFor(products.subList(0, 3), "CHARGE_CARDS");

        doReturn(goodResponse)
                .doThrow(new RuntimeException("LLM timeout"))
                .doReturn(batchJsonFor(products.subList(6, 9), "SAVINGS"))
                .when(service).callLLM(anyString());

        service.enrich(products, null, null);

        // Batch 1 (cards 1-3): enriched
        assertThat(products.get(0).getCategory()).isEqualTo("CHARGE_CARDS");
        assertThat(products.get(1).getCategory()).isEqualTo("CHARGE_CARDS");
        assertThat(products.get(2).getCategory()).isEqualTo("CHARGE_CARDS");

        // Batch 2 (cards 4-6): failed
        assertThat(products.get(3).getCategory()).isNull();
        assertThat(products.get(3).getConfidenceScore()).isEqualTo(0.0);
        assertThat(products.get(3).getReviewNotes())
                .contains("AI enrichment failed");

        // Batch 3 (cards 7-9): enriched
        assertThat(products.get(6).getCategory()).isEqualTo("SAVINGS");
    }

    @Test
    @DisplayName("enrich_allBatchesFail_productsMarkedFailed — all products have confidence=0 and review note")
    void enrich_allBatchesFail_productsMarkedFailed() throws Exception {
        List<ExtractedProduct> products = List.of(product("Card A"), product("Card B"));
        doThrow(new RuntimeException("LLM unavailable")).when(service).callLLM(anyString());

        service.enrich(new ArrayList<>(products), null, null);

        for (ExtractedProduct p : products) {
            assertThat(p.getConfidenceScore()).isEqualTo(0.0);
            assertThat(p.getReviewNotes()).isNotBlank().contains("AI enrichment failed");
        }
    }

    // -------------------------------------------------------------------------
    // enrich — sequential mode
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enrich_sequentialMode_enrichesAllProducts — 3 products, 3 LLM calls each enriched independently")
    void enrich_sequentialMode_enrichesAllProducts() throws Exception {
        config.setMode(EnrichmentMode.SEQUENTIAL);
        List<ExtractedProduct> products = List.of(
                product("Home Finance Plan"),
                product("Auto Finance Ijarah"),
                product("Personal Finance")
        );

        doReturn(singleJson("Home Finance Plan", "HOME_FINANCE", 0.95))
                .doReturn(singleJson("Auto Finance Ijarah", "AUTO_FINANCE", 0.88))
                .doReturn(singleJson("Personal Finance", "PERSONAL_FINANCE", 0.80))
                .when(service).callLLM(anyString());

        service.enrich(new ArrayList<>(products), null, null);

        verify(service, times(3)).callLLM(anyString());
        assertThat(products.get(0).getCategory()).isEqualTo("HOME_FINANCE");
        assertThat(products.get(1).getCategory()).isEqualTo("AUTO_FINANCE");
        assertThat(products.get(2).getCategory()).isEqualTo("PERSONAL_FINANCE");
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("parseBatchResponse_validJson_returnsAllCards — 3 well-formed objects -> 3 EnrichedCards")
    void parseBatchResponse_validJson_returnsAllCards() {
        String json = "[{\"product_name\":\"Card A\",\"category\":\"COVERED_CARDS\","
                + "\"confidence\":0.9,\"sharia_violations\":[]},"
                + "{\"product_name\":\"Card B\",\"category\":\"DEBIT_CARDS\","
                + "\"confidence\":0.85,\"sharia_violations\":[]},"
                + "{\"product_name\":\"Card C\",\"category\":\"CHARGE_CARDS\","
                + "\"confidence\":0.7,\"sharia_violations\":[]}]";

        List<EnrichedCard> cards = service.parseBatchResponse(json);

        assertThat(cards).hasSize(3);
        assertThat(cards.get(0).getProductName()).isEqualTo("Card A");
        assertThat(cards.get(0).getCategory()).isEqualTo("COVERED_CARDS");
        assertThat(cards.get(1).getProductName()).isEqualTo("Card B");
        assertThat(cards.get(2).getConfidence()).isEqualTo(0.7);
    }

    @Test
    @DisplayName("parseBatchResponse_markdownWrapped_strippedAndParsed — ```json [...] ``` is stripped and parsed")
    void parseBatchResponse_markdownWrapped_strippedAndParsed() {
        String json = "```json\n[{\"product_name\":\"Card A\",\"category\":\"COVERED_CARDS\","
                + "\"confidence\":0.9,\"sharia_violations\":[]}]\n```";

        List<EnrichedCard> cards = service.parseBatchResponse(json);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getProductName()).isEqualTo("Card A");
    }

    @Test
    @DisplayName("parseBatchResponse_truncatedArray_salvagesComplete — first complete object is salvaged from truncated array")
    void parseBatchResponse_truncatedArray_salvagesComplete() {
        // Array is truncated after the first object
        String json = "[{\"product_name\":\"Card A\",\"category\":\"COVERED_CARDS\","
                + "\"confidence\":0.9,\"sharia_violations\":[]},{\"product_name\":\"Card B\",\"categ";

        List<EnrichedCard> cards = service.parseBatchResponse(json);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getProductName()).isEqualTo("Card A");
    }

    @Test
    @DisplayName("parseBatchResponse_emptyResponse_returnsEmpty — empty string returns empty list")
    void parseBatchResponse_emptyResponse_returnsEmpty() {
        List<EnrichedCard> cards = service.parseBatchResponse("");

        assertThat(cards).isEmpty();
    }

    @Test
    @DisplayName("parseBatchResponse_noJsonArray_returnsEmpty — prose with no '[' returns empty list")
    void parseBatchResponse_noJsonArray_returnsEmpty() {
        List<EnrichedCard> cards = service.parseBatchResponse(
                "Sorry, I cannot classify these products.");

        assertThat(cards).isEmpty();
    }

    @Test
    @DisplayName("parseBatchResponse_singleQuotes_parsed — JSON with single quotes parsed by lenient mapper")
    void parseBatchResponse_singleQuotes_parsed() {
        String json = "[{'product_name':'Card A','category':'COVERED_CARDS',"
                + "'confidence':0.9,'sharia_violations':[]}]";

        List<EnrichedCard> cards = service.parseBatchResponse(json);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getCategory()).isEqualTo("COVERED_CARDS");
    }

    @Test
    @DisplayName("parseSequentialResponse_validJson_returnsCard — single JSON object -> single EnrichedCard")
    void parseSequentialResponse_validJson_returnsCard() {
        String json = "{\"product_name\":\"Home Finance Plan\",\"category\":\"HOME_FINANCE\","
                + "\"confidence\":0.95,\"sharia_violations\":[]}";

        EnrichedCard card = service.parseSequentialResponse(json);

        assertThat(card).isNotNull();
        assertThat(card.getProductName()).isEqualTo("Home Finance Plan");
        assertThat(card.getCategory()).isEqualTo("HOME_FINANCE");
        assertThat(card.getConfidence()).isEqualTo(0.95);
    }

    @Test
    @DisplayName("parseSequentialResponse_extraFields_ignored — unknown fields do not cause an exception")
    void parseSequentialResponse_extraFields_ignored() {
        String json = "{\"product_name\":\"Card A\",\"category\":\"COVERED_CARDS\","
                + "\"confidence\":0.9,\"sharia_violations\":[],"
                + "\"unknown_field\":\"some value\",\"another_unknown\":42}";

        EnrichedCard card = service.parseSequentialResponse(json);

        assertThat(card).isNotNull();
        assertThat(card.getProductName()).isEqualTo("Card A");
    }

    // -------------------------------------------------------------------------
    // Category validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enrich_invalidCategory_clearedWithNote — LLM returns 'MORTGAGE' -> category null, review note set")
    void enrich_invalidCategory_clearedWithNote() throws Exception {
        List<ExtractedProduct> products = new ArrayList<>();
        products.add(product("Home Product"));

        String invalidCategoryJson = "[{\"product_name\":\"Home Product\","
                + "\"category\":\"MORTGAGE\",\"confidence\":0.85,\"sharia_violations\":[]}]";
        doReturn(invalidCategoryJson).when(service).callLLM(anyString());

        service.enrich(products, null, null);

        assertThat(products.get(0).getCategory()).isNull();
        assertThat(products.get(0).getConfidenceScore()).isEqualTo(0.0);
        assertThat(products.get(0).getReviewNotes())
                .contains("MORTGAGE")
                .contains("manual categorisation required");
    }

    @Test
    @DisplayName("validateCategory_validCategory_unchanged — COVERED_CARDS passes validation unchanged")
    void validateCategory_validCategory_unchanged() {
        ExtractedProduct p = product("Card A");
        p.setCategory("COVERED_CARDS");
        p.setConfidenceScore(0.9);

        service.validateCategory(p);

        assertThat(p.getCategory()).isEqualTo("COVERED_CARDS");
        assertThat(p.getReviewNotes()).isNull();
    }

    @Test
    @DisplayName("validateCategory_nullCategory_noError — null category is allowed without throwing")
    void validateCategory_nullCategory_noError() {
        ExtractedProduct p = product("Card A");
        p.setCategory(null);

        // Should not throw
        service.validateCategory(p);

        assertThat(p.getReviewNotes()).isNull();
    }

    // -------------------------------------------------------------------------
    // Confidence clamping
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enrich_confidenceOutOfRange_clamped — confidence=1.5 is clamped to 1.0")
    void enrich_confidenceOutOfRange_clamped() throws Exception {
        List<ExtractedProduct> products = new ArrayList<>();
        products.add(product("Card A"));

        String json = "[{\"product_name\":\"Card A\",\"category\":\"COVERED_CARDS\","
                + "\"confidence\":1.5,\"sharia_violations\":[]}]";
        doReturn(json).when(service).callLLM(anyString());

        service.enrich(products, null, null);

        assertThat(products.get(0).getConfidenceScore()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("enrich_confidenceNegative_clamped — confidence=-0.3 is clamped to 0.0")
    void enrich_confidenceNegative_clamped() throws Exception {
        List<ExtractedProduct> products = new ArrayList<>();
        products.add(product("Card A"));

        String json = "[{\"product_name\":\"Card A\",\"category\":\"COVERED_CARDS\","
                + "\"confidence\":-0.3,\"sharia_violations\":[]}]";
        doReturn(json).when(service).callLLM(anyString());

        service.enrich(products, null, null);

        assertThat(products.get(0).getConfidenceScore()).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // Sharia compliance
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enrich_shariaViolationDetected_flaggedInReviewNotes — description with 'interest rate' -> review note set")
    void enrich_shariaViolationDetected_flaggedInReviewNotes() throws Exception {
        List<ExtractedProduct> products = new ArrayList<>();
        products.add(productWithDescription("Finance Product",
                "This product offers competitive interest rates for UAE residents."));

        String json = "[{\"product_name\":\"Finance Product\",\"category\":\"PERSONAL_FINANCE\","
                + "\"confidence\":0.85,\"sharia_violations\":[]}]";
        doReturn(json).when(service).callLLM(anyString());

        service.enrich(products, null, null);

        assertThat(products.get(0).getReviewNotes())
                .isNotNull()
                .contains("SHARIA COMPLIANCE")
                .contains("interest")
                .contains("profit rate");
    }

    @Test
    @DisplayName("enrich_shariaViolationInBenefits_flaggedInReviewNotes — benefit with 'insurance included' -> review note mentions Takaful")
    void enrich_shariaViolationInBenefits_flaggedInReviewNotes() throws Exception {
        List<ExtractedProduct> products = new ArrayList<>();
        products.add(productWithBenefits("Auto Finance",
                List.of("Quick approval", "Insurance included", "Low profit rate")));

        String json = "[{\"product_name\":\"Auto Finance\",\"category\":\"AUTO_FINANCE\","
                + "\"confidence\":0.90,\"sharia_violations\":[]}]";
        doReturn(json).when(service).callLLM(anyString());

        service.enrich(products, null, null);

        assertThat(products.get(0).getReviewNotes())
                .isNotNull()
                .contains("SHARIA COMPLIANCE")
                .contains("insurance")
                .contains("Takaful");
    }

    @Test
    @DisplayName("enrich_noShariaViolation_noReviewNotes — clean product has null reviewNotes")
    void enrich_noShariaViolation_noReviewNotes() throws Exception {
        List<ExtractedProduct> products = new ArrayList<>();
        products.add(productWithDescription("Savings Account",
                "A Sharia-compliant savings account with competitive profit rate returns."));

        String json = "[{\"product_name\":\"Savings Account\",\"category\":\"SAVINGS\","
                + "\"confidence\":0.92,\"sharia_violations\":[]}]";
        doReturn(json).when(service).callLLM(anyString());

        service.enrich(products, null, null);

        assertThat(products.get(0).getReviewNotes()).isNull();
    }

    // -------------------------------------------------------------------------
    // Retry behaviour
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enrich_llmTimeout_retriedOnce_thenFailed — with retryOnFailure, two failures -> product marked failed")
    void enrich_llmTimeout_retriedOnce_thenFailed() throws Exception {
        config.setRetryOnFailure(true);
        config.setMaxRetries(1);
        List<ExtractedProduct> products = new ArrayList<>();
        products.add(product("Card A"));

        doThrow(new RuntimeException("timeout"))
                .doThrow(new RuntimeException("timeout again"))
                .when(service).callLLM(anyString());

        service.enrich(products, null, null);

        verify(service, times(1)).callLLM(anyString()); // CardEnrichmentService itself retries internally
        assertThat(products.get(0).getConfidenceScore()).isEqualTo(0.0);
        assertThat(products.get(0).getReviewNotes()).contains("AI enrichment failed");
    }

    // -------------------------------------------------------------------------
    // Cancellation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enrich_cancellation_stopsAfterCurrentBatch — cancellation after batch 1 -> batches 2-3 not processed")
    void enrich_cancellation_stopsAfterCurrentBatch() throws Exception {
        config.setBatchSize(3);
        List<ExtractedProduct> products = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            products.add(product("Card " + i));
        }

        // Batch 1 succeeds, then cancellation flag becomes true
        AtomicInteger callCount = new AtomicInteger(0);
        doReturn(batchJsonFor(products.subList(0, 3), "COVERED_CARDS"))
                .when(service).callLLM(anyString());

        // Cancel after first batch call
        AtomicInteger batchCheck = new AtomicInteger(0);
        java.util.function.BooleanSupplier cancel = () -> batchCheck.incrementAndGet() > 1;

        service.enrich(products, null, cancel);

        // First batch enriched
        assertThat(products.get(0).getCategory()).isEqualTo("COVERED_CARDS");
        // Batches 2 and 3 not processed (category remains null)
        assertThat(products.get(3).getCategory()).isNull();
        assertThat(products.get(6).getCategory()).isNull();

        // Only 1 LLM call was made (for batch 1)
        verify(service, times(1)).callLLM(anyString());
    }

    // -------------------------------------------------------------------------
    // Progress callback
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enrich_progressCallbackInvoked — callback receives one event per batch with correct fields")
    void enrich_progressCallbackInvoked() throws Exception {
        config.setBatchSize(5);
        List<ExtractedProduct> products = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            products.add(product("Card " + i));
        }

        doReturn(batchJsonFor(products.subList(0, 5), "COVERED_CARDS"))
                .doReturn(batchJsonFor(products.subList(5, 10), "DEBIT_CARDS"))
                .when(service).callLLM(anyString());

        List<EnrichmentProgressEvent> received = new ArrayList<>();
        service.enrich(products, received::add, null);

        assertThat(received).hasSize(2);
        assertThat(received.get(0).getBatchNumber()).isEqualTo(1);
        assertThat(received.get(0).getTotalBatches()).isEqualTo(2);
        assertThat(received.get(0).getProductsProcessed()).isEqualTo(5);
        assertThat(received.get(0).getPhase()).isEqualTo(EnrichmentProgressEvent.Phase.ENRICHING);
        assertThat(received.get(1).getBatchNumber()).isEqualTo(2);
        assertThat(received.get(1).getProductsProcessed()).isEqualTo(10);
    }

    // -------------------------------------------------------------------------
    // Prompt builders — no keywords
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("buildBatchPrompt_includesAllProductNames — prompt contains all product names from batch")
    void buildBatchPrompt_includesAllProductNames() {
        List<ExtractedProduct> batch = List.of(
                product("Al Islami Classic Charge Card"),
                product("Cashback Covered Card"),
                product("Premium Debit Card")
        );

        String prompt = service.buildBatchPrompt(batch);

        assertThat(prompt).contains("Al Islami Classic Charge Card");
        assertThat(prompt).contains("Cashback Covered Card");
        assertThat(prompt).contains("Premium Debit Card");
        // Must not ask for keywords
        assertThat(prompt.toLowerCase()).doesNotContain("keyword");
    }

    @Test
    @DisplayName("buildBatchPrompt_containsValidCategoryList — all 10 categories listed in prompt")
    void buildBatchPrompt_containsValidCategoryList() {
        String prompt = service.buildBatchPrompt(List.of(product("Test Card")));

        assertThat(prompt).contains("COVERED_CARDS");
        assertThat(prompt).contains("HOME_FINANCE");
        assertThat(prompt).contains("TAKAFUL");
        assertThat(prompt).contains("INVESTMENTS");
        assertThat(prompt).contains("CURRENT_ACCOUNTS");
    }

    @Test
    @DisplayName("buildBatchPrompt_containsShariaTerminology — prompt instructs profit rate not interest")
    void buildBatchPrompt_containsShariaTerminology() {
        String prompt = service.buildBatchPrompt(List.of(product("Test Card")));

        assertThat(prompt).contains("profit rate");
        assertThat(prompt).contains("Takaful");
        assertThat(prompt).contains("home finance");
    }

    @Test
    @DisplayName("buildSequentialPrompt_includesSingleProduct — prompt contains the product's name, description, and benefits")
    void buildSequentialPrompt_includesSingleProduct() {
        ExtractedProduct p = ExtractedProduct.builder()
                .productName("DIB Home Finance")
                .description("Sharia-compliant home financing with profit rate payments.")
                .keyBenefits(List.of("Up to 25 years", "Fixed profit rate"))
                .sourceUrl("https://dib.ae/home-finance")
                .build();

        String prompt = service.buildSequentialPrompt(p);

        assertThat(prompt).contains("DIB Home Finance");
        assertThat(prompt).contains("Sharia-compliant home financing");
        assertThat(prompt).contains("Up to 25 years");
        assertThat(prompt).contains("Fixed profit rate");
        // Must not ask for keywords
        assertThat(prompt.toLowerCase()).doesNotContain("keyword");
    }

    // -------------------------------------------------------------------------
    // Malformed JSON fallback
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("enrich_malformedJson_fallbackSalvage — truncated array, first object salvaged, remainder marked failed")
    void enrich_malformedJson_fallbackSalvage() throws Exception {
        config.setBatchSize(3);
        List<ExtractedProduct> products = new ArrayList<>();
        products.add(product("Card A"));
        products.get(0).setProductName("Card A");
        products.add(product("Card B"));
        products.add(product("Card C"));

        // First object complete, rest truncated
        String truncated =
                "[{\"product_name\":\"Card A\",\"category\":\"COVERED_CARDS\","
                + "\"confidence\":0.88,\"sharia_violations\":[]},"
                + "{\"product_name\":\"Card B\",\"categ";

        doReturn(truncated).when(service).callLLM(anyString());

        service.enrich(products, null, null);

        // Card A was salvaged from the truncated response
        assertThat(products.get(0).getCategory()).isEqualTo("COVERED_CARDS");
        assertThat(products.get(0).getConfidenceScore()).isEqualTo(0.88);

        // Card B and C had no match in salvaged output
        assertThat(products.get(1).getReviewNotes()).contains("AI enrichment failed");
        assertThat(products.get(2).getReviewNotes()).contains("AI enrichment failed");
    }
}
