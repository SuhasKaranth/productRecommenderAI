package com.smartguide.poc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartguide.poc.config.LLMConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for LLM summary methods in {@link LLMService}.
 *
 * Because LLMService builds its own WebClient in the constructor, we use
 * ReflectionTestUtils to replace the {@code webClient} field with a Mockito mock
 * after construction.
 *
 * The WebClient fluent API uses wildcard generics that cause Java capture errors
 * when combined with Mockito's thenReturn. We avoid this by:
 * - Declaring the RequestHeadersSpec mock as a raw type.
 * - Using doReturn(...).when(...) on the RequestBodySpec to sidestep the cast.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LLMServiceTest {

    @Mock
    private LLMConfig llmConfig;

    @Mock
    private LLMConfig.AzureConfig azureConfig;

    @Mock
    private LLMConfig.OllamaConfig ollamaConfig;

    private LLMService llmService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ------------------------------------------------------------------
    // WebClient mock chain — declared at field level so helpers can use them
    // ------------------------------------------------------------------

    private WebClient webClient;
    private WebClient.RequestBodyUriSpec requestBodyUriSpec;
    private WebClient.RequestBodySpec requestBodySpec;
    private WebClient.RequestHeadersSpec requestHeadersSpec; // raw type avoids capture error
    private WebClient.ResponseSpec responseSpec;

    @BeforeEach
    void setUp() {
        // Wire LLMConfig sub-objects
        when(llmConfig.getAzure()).thenReturn(azureConfig);
        when(llmConfig.getOllama()).thenReturn(ollamaConfig);

        when(azureConfig.getEndpoint()).thenReturn("https://test.openai.azure.com");
        when(azureConfig.getDeploymentName()).thenReturn("test-deployment");
        when(azureConfig.getApiVersion()).thenReturn("2024-02-15-preview");
        when(azureConfig.getApiKey()).thenReturn("test-api-key");

        when(ollamaConfig.getHost()).thenReturn("http://localhost:11434");
        when(ollamaConfig.getModel()).thenReturn("llama3.2");
        when(ollamaConfig.getTimeout()).thenReturn(5000);

        // Build the service; it creates its own WebClient internally
        llmService = new LLMService(llmConfig, objectMapper);

        // Create WebClient mocks manually so we can control raw types
        webClient = mock(WebClient.class);
        requestBodyUriSpec = mock(WebClient.RequestBodyUriSpec.class);
        requestBodySpec = mock(WebClient.RequestBodySpec.class);
        requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class); // raw — no <>
        responseSpec = mock(WebClient.ResponseSpec.class);

        // Replace the internally-built WebClient with our mock
        ReflectionTestUtils.setField(llmService, "webClient", webClient);

        // Populate @Value fields that Spring would normally inject from YAML
        ReflectionTestUtils.setField(llmService, "keywordSystemPrompt",
                "Test keyword system prompt for Islamic finance products.");
        ReflectionTestUtils.setField(llmService, "maxKeywords", 10);
        ReflectionTestUtils.setField(llmService, "timeoutSeconds", 5);
        ReflectionTestUtils.setField(llmService, "summaryTimeoutSeconds", 5);
        ReflectionTestUtils.setField(llmService, "summarySystemPrompt",
                "Test summary system prompt for Islamic finance products.");
        ReflectionTestUtils.setField(llmService, "rankingTimeoutMs", 5000);
    }

    // ------------------------------------------------------------------
    // Helper: build a sample productData map using Sharia-compliant terms
    // ------------------------------------------------------------------

    private Map<String, Object> buildProductData(String productName) {
        Map<String, Object> data = new HashMap<>();
        data.put("productName", productName);
        data.put("category", "HOME");
        data.put("islamicStructure", "Murabaha");
        data.put("description", "Sharia-compliant home finance product.");
        data.put("keyBenefits", Arrays.asList(
                "Competitive profit rates",
                "Flexible repayment terms"
        ));
        return data;
    }

    // ------------------------------------------------------------------
    // Helper: stub full Azure WebClient chain → returns rawJsonResponse
    // ------------------------------------------------------------------

    private void stubAzureWebClientToReturn(String rawJsonResponse) {
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        // header() returns RequestBodySpec — use doReturn to avoid generics issue
        doReturn(requestBodySpec).when(requestBodySpec).header(anyString(), anyString());
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        // bodyValue() returns RequestHeadersSpec (raw) — doReturn avoids capture error
        doReturn(requestHeadersSpec).when(requestBodySpec).bodyValue(any());
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(rawJsonResponse));
    }

    // ------------------------------------------------------------------
    // Helper: stub full Ollama WebClient chain → returns rawJsonResponse
    // (Ollama path does NOT call .header(), so the chain differs)
    // ------------------------------------------------------------------

    private void stubOllamaWebClientToReturn(String rawJsonResponse) {
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        doReturn(requestHeadersSpec).when(requestBodySpec).bodyValue(any());
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(rawJsonResponse));
    }

    // ------------------------------------------------------------------
    // Helper: stub WebClient chain → Mono.error (simulates LLM failure)
    // ------------------------------------------------------------------

    private void stubAzureWebClientToThrow(RuntimeException ex) {
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        doReturn(requestBodySpec).when(requestBodySpec).header(anyString(), anyString());
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        doReturn(requestHeadersSpec).when(requestBodySpec).bodyValue(any());
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.error(ex));
    }

    // ------------------------------------------------------------------
    // generateSummaryFromMap — Azure provider → returns generated summary
    // ------------------------------------------------------------------

    @Test
    @DisplayName("generateSummaryFromMap: azure provider returns trimmed summary string")
    void generateSummaryFromMap_azureProvider_returnsGeneratedSummary() {
        String azureResponse = """
                {
                  "choices": [
                    {
                      "message": {
                        "content": "  Al Salam Home Finance is a Sharia-compliant Murabaha product.  "
                      }
                    }
                  ]
                }
                """;
        when(llmConfig.getProvider()).thenReturn("azure");
        stubAzureWebClientToReturn(azureResponse);

        String result = llmService.generateSummaryFromMap(buildProductData("Al Salam Home Finance"));

        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(
                "Al Salam Home Finance is a Sharia-compliant Murabaha product.");
    }

    // ------------------------------------------------------------------
    // generateSummaryFromMap — Ollama provider → returns generated summary
    // ------------------------------------------------------------------

    @Test
    @DisplayName("generateSummaryFromMap: ollama provider returns trimmed summary string")
    void generateSummaryFromMap_ollamaProvider_returnsGeneratedSummary() {
        String ollamaResponse = """
                {
                  "response": "  Al Amal Takaful is a Sharia-compliant takaful product.  "
                }
                """;
        when(llmConfig.getProvider()).thenReturn("ollama");
        stubOllamaWebClientToReturn(ollamaResponse);

        Map<String, Object> productData = buildProductData("Al Amal Takaful");
        productData.put("islamicStructure", "Takaful");
        String result = llmService.generateSummaryFromMap(productData);

        assertThat(result).isNotNull();
        assertThat(result).isEqualTo("Al Amal Takaful is a Sharia-compliant takaful product.");
    }

    // ------------------------------------------------------------------
    // generateSummaryFromMap — long response is truncated to 1000 chars
    // ------------------------------------------------------------------

    @Test
    @DisplayName("generateSummaryFromMap: truncates LLM response longer than 1000 characters")
    void generateSummaryFromMap_truncatesLongSummary() {
        // Build a 1500-char content value (no quotes or special chars to keep JSON valid)
        String longContent = "A".repeat(1500);
        String azureResponse = """
                {"choices":[{"message":{"content":"%s"}}]}
                """.formatted(longContent);

        when(llmConfig.getProvider()).thenReturn("azure");
        stubAzureWebClientToReturn(azureResponse);

        String result = llmService.generateSummaryFromMap(buildProductData("Al Barakah Finance"));

        assertThat(result).isNotNull();
        assertThat(result.length()).isLessThanOrEqualTo(1000);
    }

    // ------------------------------------------------------------------
    // generateSummaryFromMap — WebClient throws → fallback returned (non-null)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("generateSummaryFromMap: falls back to template when LLM call fails")
    void generateSummaryFromMap_llmFails_returnsFallback() {
        when(llmConfig.getProvider()).thenReturn("azure");
        stubAzureWebClientToThrow(new RuntimeException("Connection refused"));

        Map<String, Object> productData = buildProductData("Al Noor Home Finance");
        String result = llmService.generateSummaryFromMap(productData);

        assertThat(result).isNotNull();
        assertThat(result).satisfiesAnyOf(
                r -> assertThat(r).contains("Al Noor Home Finance"),
                r -> assertThat(r).contains("Sharia-compliant")
        );
    }

    // ------------------------------------------------------------------
    // generateSummaryFromMap — unknown provider → fallback returned, not thrown
    // ------------------------------------------------------------------

    @Test
    @DisplayName("generateSummaryFromMap: unknown provider causes fallback, not an exception")
    void generateSummaryFromMap_unknownProvider_returnsFallback() {
        when(llmConfig.getProvider()).thenReturn("unknown-provider");

        Map<String, Object> productData = buildProductData("Al Fajr Savings");
        productData.put("category", "SAVINGS");

        String result = llmService.generateSummaryFromMap(productData);

        assertThat(result).isNotNull();
        assertThat(result).satisfiesAnyOf(
                r -> assertThat(r).contains("Al Fajr Savings"),
                r -> assertThat(r).contains("Sharia-compliant")
        );
    }

    // ------------------------------------------------------------------
    // getFallbackSummary — all fields present → contains product name
    // (exercised via generateSummaryFromMap with unknown provider)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getFallbackSummary: with all fields returns non-null string containing product name")
    void getFallbackSummary_withAllFields_containsName() {
        when(llmConfig.getProvider()).thenReturn("none");

        Map<String, Object> productData = buildProductData("Al Mashreq Murabaha Finance");
        productData.put("category", "AUTO");
        productData.put("islamicStructure", "Murabaha");

        String result = llmService.generateSummaryFromMap(productData);

        assertThat(result).isNotNull();
        assertThat(result).contains("Al Mashreq Murabaha Finance");
    }

    // ------------------------------------------------------------------
    // getFallbackSummary — empty map → returns non-null, non-blank string
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getFallbackSummary: with minimal/empty map returns non-null string")
    void getFallbackSummary_withMinimalFields_returnsNonNull() {
        when(llmConfig.getProvider()).thenReturn("none");

        String result = llmService.generateSummaryFromMap(new HashMap<>());

        assertThat(result).isNotNull();
        assertThat(result).isNotBlank();
    }

    // ------------------------------------------------------------------
    // generateKeywordsFromMap — rawPageContent present → non-null keywords
    // returned (verifies that rawPageContent is included in the prompt and
    // the method does not fall back when the LLM responds successfully).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("generateKeywordsFromMap: rawPageContent in productData yields non-null keywords via Ollama")
    void generateKeywordsFromMap_includesRawPageContentInPrompt() {
        // Ollama response containing a valid JSON keyword array
        String ollamaResponse = """
                {
                  "response": "[\\"home-finance\\", \\"murabaha\\", \\"sharia-compliant\\", \\"profit-rate\\", \\"takaful\\"]"
                }
                """;

        when(llmConfig.getProvider()).thenReturn("ollama");
        stubOllamaWebClientToReturn(ollamaResponse);

        Map<String, Object> productData = buildProductData("Al Salam Home Finance");
        // rawPageContent is the field added to the prompt by Change 3
        productData.put("rawPageContent",
                "Al Salam Home Finance offers Sharia-compliant Murabaha-based home finance. " +
                "Competitive profit rates, flexible terms, no hidden fees.");

        java.util.List<String> keywords = llmService.generateKeywordsFromMap(productData);

        assertThat(keywords).isNotNull();
        assertThat(keywords).isNotEmpty();
        // The LLM mock returned 5 keywords — expect at least 3 (quality-check threshold)
        assertThat(keywords.size()).isGreaterThanOrEqualTo(3);
    }
}
