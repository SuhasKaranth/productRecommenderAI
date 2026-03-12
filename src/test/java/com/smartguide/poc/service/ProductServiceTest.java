package com.smartguide.poc.service;

import com.smartguide.poc.entity.Product;
import com.smartguide.poc.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductService#generateSummary(Long)}.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private LLMService llmService;

    @Mock
    private ScraperServiceClient scraperServiceClient;

    @InjectMocks
    private ProductService productService;

    private Product sampleProduct;

    @BeforeEach
    void setUp() {
        sampleProduct = new Product();
        sampleProduct.setId(1L);
        sampleProduct.setProductCode("FIN_HOME_01");
        sampleProduct.setProductName("Al Salam Home Finance");
        sampleProduct.setCategory("HOME");
        sampleProduct.setIslamicStructure("Murabaha");
        sampleProduct.setDescription("Sharia-compliant home finance product.");
        sampleProduct.setKeyBenefits(Arrays.asList(
                "Competitive profit rates",
                "Flexible repayment terms",
                "No hidden fees"
        ));
        sampleProduct.setActive(true);
        sampleProduct.setShariaCertified(true);
    }

    // -----------------------------------------------------------------------
    // generateSummary — product found, LLM returns summary
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("generateSummary: returns LLM-generated summary when product is found")
    void generateSummary_returnsLlmSummary() {
        // Given
        String expectedSummary = "Al Salam Home Finance is a Sharia-compliant product "
                + "structured under Murabaha offering competitive profit rates.";
        when(productRepository.findById(1L)).thenReturn(Optional.of(sampleProduct));
        when(llmService.generateSummaryFromMap(any())).thenReturn(expectedSummary);

        // When
        String actualSummary = productService.generateSummary(1L);

        // Then
        assertThat(actualSummary).isEqualTo(expectedSummary);
    }

    // -----------------------------------------------------------------------
    // generateSummary — product not found → RuntimeException
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("generateSummary: throws RuntimeException when product is not found")
    void generateSummary_productNotFound_throwsRuntimeException() {
        // Given
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> productService.generateSummary(999L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Product not found");
    }

    // -----------------------------------------------------------------------
    // generateSummary — verifies productData map keys passed to LLMService
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("generateSummary: passes correct productData map keys to LLMService")
    void generateSummary_buildsCorrectProductDataMap() {
        // Given
        when(productRepository.findById(1L)).thenReturn(Optional.of(sampleProduct));
        when(llmService.generateSummaryFromMap(any())).thenReturn("Any summary");

        // Capture the argument passed to llmService
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);

        // When
        productService.generateSummary(1L);

        // Then
        verify(llmService).generateSummaryFromMap(captor.capture());
        Map<String, Object> capturedMap = captor.getValue();

        assertThat(capturedMap).containsKey("productName");
        assertThat(capturedMap).containsKey("category");
        assertThat(capturedMap).containsKey("islamicStructure");
        assertThat(capturedMap).containsKey("description");
        assertThat(capturedMap).containsKey("keyBenefits");

        assertThat(capturedMap.get("productName")).isEqualTo("Al Salam Home Finance");
        assertThat(capturedMap.get("category")).isEqualTo("HOME");
        assertThat(capturedMap.get("islamicStructure")).isEqualTo("Murabaha");
        assertThat(capturedMap.get("description")).isEqualTo("Sharia-compliant home finance product.");

        @SuppressWarnings("unchecked")
        List<String> benefits = (List<String>) capturedMap.get("keyBenefits");
        assertThat(benefits).containsExactly(
                "Competitive profit rates",
                "Flexible repayment terms",
                "No hidden fees"
        );
    }

    // -----------------------------------------------------------------------
    // refreshProductContent — success: rawPageContent and scrapedAt updated
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("refreshProductContent: updates rawPageContent and scrapedAt on success")
    void refreshProductContent_success() {
        // Given
        sampleProduct.setSourceUrl("https://dib.ae/home-finance");
        when(productRepository.findById(1L)).thenReturn(Optional.of(sampleProduct));
        when(scraperServiceClient.scrapePageContent("https://dib.ae/home-finance"))
                .thenReturn("Rich page content from detail page...");
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        Map<String, Object> result = productService.refreshProductContent(1L);

        // Then
        assertThat(result).containsKey("scrapedAt");
        assertThat(result).containsEntry("message", "Content refreshed successfully");

        ArgumentCaptor<Product> savedProduct = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(savedProduct.capture());
        assertThat(savedProduct.getValue().getRawPageContent())
                .isEqualTo("Rich page content from detail page...");
        assertThat(savedProduct.getValue().getScrapedAt()).isNotNull();
    }

    // -----------------------------------------------------------------------
    // refreshProductContent — product not found → RuntimeException
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("refreshProductContent: throws RuntimeException when product is not found")
    void refreshProductContent_productNotFound() {
        // Given
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> productService.refreshProductContent(999L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Product not found");

        verify(scraperServiceClient, never()).scrapePageContent(any());
    }

    // -----------------------------------------------------------------------
    // refreshProductContent — no sourceUrl → IllegalStateException
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("refreshProductContent: throws IllegalStateException when sourceUrl is null")
    void refreshProductContent_noSourceUrl() {
        // Given — sampleProduct has no sourceUrl (null by default in setUp)
        sampleProduct.setSourceUrl(null);
        when(productRepository.findById(1L)).thenReturn(Optional.of(sampleProduct));

        // When / Then
        assertThatThrownBy(() -> productService.refreshProductContent(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No source URL configured");

        verify(scraperServiceClient, never()).scrapePageContent(any());
    }

    // -----------------------------------------------------------------------
    // refreshProductContent — scraper returns blank → RuntimeException
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("refreshProductContent: throws RuntimeException when scraper returns blank content")
    void refreshProductContent_emptyContent() {
        // Given
        sampleProduct.setSourceUrl("https://dib.ae/home-finance");
        when(productRepository.findById(1L)).thenReturn(Optional.of(sampleProduct));
        when(scraperServiceClient.scrapePageContent("https://dib.ae/home-finance"))
                .thenReturn("   "); // blank content

        // When / Then
        assertThatThrownBy(() -> productService.refreshProductContent(1L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Scraper returned empty content");

        verify(productRepository, never()).save(any());
    }
}
