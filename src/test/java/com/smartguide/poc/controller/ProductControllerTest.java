package com.smartguide.poc.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartguide.poc.repository.ProductRepository;
import com.smartguide.poc.security.ApiKeyAuthenticationFilter;
import com.smartguide.poc.security.ApiKeyService;
import com.smartguide.poc.service.ProductService;
import com.smartguide.poc.service.ScraperServiceClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc unit tests for {@link ProductController#generateSummary(Long)}.
 *
 * Security is satisfied via two complementary mechanisms:
 *  1. {@code @MockBean} on the security infrastructure beans so that
 *     {@link com.smartguide.poc.security.SecurityConfig} can be instantiated
 *     inside the @WebMvcTest slice without the full application context.
 *  2. {@code @WithMockUser} on each test method so that the Spring Security
 *     filter chain sees an authenticated principal and does not return 401/403.
 *
 * The custom {@link ApiKeyAuthenticationFilter} is mocked out entirely so it
 * does not intercept test requests.
 */
@WebMvcTest(ProductController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
        "security.api-key.enabled=false",
        "security.api-key.header-name=X-API-Key",
        "security.api-key.admin-keys=",
        "security.api-key.user-keys=",
        "security.api-key.scoped-keys=",
        "app.llm.provider=ollama",
        "app.llm.ollama.host=http://localhost:11434",
        "app.llm.ollama.model=llama3.2",
        "app.llm.ollama.timeout=5000",
        "app.keywords.generation.system-prompt=test-prompt",
        "app.keywords.generation.max-keywords=10",
        "app.keywords.generation.timeout-seconds=5",
        "app.ranking.llm.enabled=false",
        "app.ranking.llm.timeout-ms=5000",
        "app.scraper-service.url=http://localhost:8081"
})
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ------ Production beans that the controller and slice need ------
    @MockBean
    private ProductService productService;

    @MockBean
    private ProductRepository productRepository;

    @MockBean
    private ScraperServiceClient scraperServiceClient;

    // ------ Security infrastructure required by SecurityConfig ------
    @MockBean
    private ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;

    @MockBean
    private ApiKeyService apiKeyService;

    // -----------------------------------------------------------------------
    // generateSummary — success → 200 with summary and message
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser
    @DisplayName("POST /api/products/{id}/generate-summary: returns 200 with summary and message")
    void generateSummary_success_returns200WithSummary() throws Exception {
        String expectedSummary =
                "Al Salam Home Finance is a Sharia-compliant Murabaha product "
                        + "offering competitive profit rates.";
        when(productService.generateSummary(1L)).thenReturn(expectedSummary);

        mockMvc.perform(post("/api/products/1/generate-summary")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.summary", is(expectedSummary)))
                .andExpect(jsonPath("$.message", is("Summary generated successfully")));
    }

    // -----------------------------------------------------------------------
    // generateSummary — product not found → 404
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser
    @DisplayName("POST /api/products/{id}/generate-summary: returns 404 when product is not found")
    void generateSummary_productNotFound_returns404() throws Exception {
        when(productService.generateSummary(999L))
                .thenThrow(new RuntimeException("Product not found: 999"));

        mockMvc.perform(post("/api/products/999/generate-summary")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------------
    // generateSummary — unexpected LLM error → 500 with error key
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser
    @DisplayName("POST /api/products/{id}/generate-summary: returns 500 with error key on unexpected failure")
    void generateSummary_llmError_returns500() throws Exception {
        // The controller catches:
        //   catch (RuntimeException e) -> 404
        //   catch (Exception e)        -> 500
        //
        // To reach the 500 path we throw a checked Exception from the mocked
        // service. Mockito's thenAnswer lets us throw any Throwable, including
        // checked exceptions, from a non-void method.
        when(productService.generateSummary(42L))
                .thenAnswer(inv -> { throw new Exception("LLM provider unreachable"); });

        mockMvc.perform(post("/api/products/42/generate-summary")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error", notNullValue()))
                .andExpect(jsonPath("$.error", containsString("Failed to generate summary")));
    }

    // -----------------------------------------------------------------------
    // refreshContent — 200 success
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser
    @DisplayName("POST /api/products/{id}/refresh-content: returns 200 with scrapedAt and message")
    void refreshContent_returns200() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(productService.refreshProductContent(1L))
                .thenReturn(Map.of("scrapedAt", now, "message", "Content refreshed successfully"));

        mockMvc.perform(post("/api/products/1/refresh-content")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", is("Content refreshed successfully")))
                .andExpect(jsonPath("$.scrapedAt", notNullValue()));
    }

    // -----------------------------------------------------------------------
    // refreshContent — product not found → 404
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser
    @DisplayName("POST /api/products/{id}/refresh-content: returns 404 when product is not found")
    void refreshContent_returns404() throws Exception {
        when(productService.refreshProductContent(999L))
                .thenThrow(new RuntimeException("Product not found: 999"));

        mockMvc.perform(post("/api/products/999/refresh-content")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------------
    // refreshContent — no source URL → 400
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser
    @DisplayName("POST /api/products/{id}/refresh-content: returns 400 when product has no source URL")
    void refreshContent_returns400() throws Exception {
        when(productService.refreshProductContent(2L))
                .thenThrow(new IllegalStateException("No source URL configured for product: 2"));

        mockMvc.perform(post("/api/products/2/refresh-content")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("No source URL")));
    }

    // -----------------------------------------------------------------------
    // refreshContent — scraper unavailable → 502
    // -----------------------------------------------------------------------

    @Test
    @WithMockUser
    @DisplayName("POST /api/products/{id}/refresh-content: returns 502 when scraper service is unavailable")
    void refreshContent_returns502() throws Exception {
        when(productService.refreshProductContent(3L))
                .thenThrow(new RuntimeException("Scraper service unavailable: Connection refused"));

        mockMvc.perform(post("/api/products/3/refresh-content")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error", containsString("unavailable")));
    }
}
