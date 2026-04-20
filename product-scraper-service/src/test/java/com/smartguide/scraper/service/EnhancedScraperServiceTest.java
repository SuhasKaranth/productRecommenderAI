package com.smartguide.scraper.service;

import com.smartguide.scraper.config.BankSourceConfig;
import com.smartguide.scraper.config.ScraperProperties;
import com.smartguide.scraper.dto.AnchorPair;
import com.smartguide.scraper.dto.ExtractedProduct;
import com.smartguide.scraper.dto.ScrapeResponse;
import com.smartguide.scraper.dto.StructuredCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EnhancedScraperService#matchProductUrls}.
 */
@ExtendWith(MockitoExtension.class)
class EnhancedScraperServiceTest {

    @Mock
    private BasicScraperService basicScraperService;

    @Mock
    private AIProductExtractor aiProductExtractor;

    @Mock
    private StagingProductService stagingProductService;

    @Mock
    private ScraperProperties scraperProperties;

    @Mock
    private com.smartguide.scraper.service.CardEnrichmentService cardEnrichmentService;

    @Mock
    private com.smartguide.scraper.config.EnrichmentConfig enrichmentConfig;

    @InjectMocks
    private EnhancedScraperService service;

    private static final String LISTING_URL = "https://dib.ae/personal/cards";
    private static final String DETAIL_URL  = "https://dib.ae/personal/cards/dib-cashback-card";

    private ExtractedProduct product(String name) {
        return ExtractedProduct.builder()
                .productName(name)
                .sourceUrl(LISTING_URL)
                .build();
    }

    @Test
    @DisplayName("matchProductUrls_exactMatch — anchor text equals product name -> detail URL assigned")
    void matchProductUrls_exactMatch() {
        ExtractedProduct p = product("DIB Cashback Card");
        List<AnchorPair> anchors = List.of(
                new AnchorPair("DIB Cashback Card", DETAIL_URL)
        );

        service.matchProductUrls(List.of(p), anchors, LISTING_URL);

        assertThat(p.getSourceUrl()).isEqualTo(DETAIL_URL);
    }

    @Test
    @DisplayName("matchProductUrls_partialMatch_nameContainsAnchor — product name contains anchor text -> detail URL assigned")
    void matchProductUrls_partialMatch_nameContainsAnchor() {
        ExtractedProduct p = product("DIB Cashback Visa Credit Card");
        List<AnchorPair> anchors = List.of(
                new AnchorPair("DIB Cashback", DETAIL_URL)
        );

        service.matchProductUrls(List.of(p), anchors, LISTING_URL);

        assertThat(p.getSourceUrl()).isEqualTo(DETAIL_URL);
    }

    @Test
    @DisplayName("matchProductUrls_partialMatch_anchorContainsName — anchor text contains product name -> detail URL assigned")
    void matchProductUrls_partialMatch_anchorContainsName() {
        ExtractedProduct p = product("Cashback Card");
        List<AnchorPair> anchors = List.of(
                new AnchorPair("DIB Cashback Card — Apply Now", DETAIL_URL)
        );

        service.matchProductUrls(List.of(p), anchors, LISTING_URL);

        assertThat(p.getSourceUrl()).isEqualTo(DETAIL_URL);
    }

    @Test
    @DisplayName("matchProductUrls_noMatch_fallsBackToListingUrl — no anchor text matches -> listing URL used")
    void matchProductUrls_noMatch_fallsBackToListingUrl() {
        ExtractedProduct p = product("DIB Cashback Card");
        List<AnchorPair> anchors = List.of(
                new AnchorPair("Home Finance Plan", "https://dib.ae/personal/finance/home")
        );

        service.matchProductUrls(List.of(p), anchors, LISTING_URL);

        assertThat(p.getSourceUrl()).isEqualTo(LISTING_URL);
    }

    @Test
    @DisplayName("matchProductUrls_caseInsensitive — matching ignores case")
    void matchProductUrls_caseInsensitive() {
        ExtractedProduct p = product("dib CASHBACK card");
        List<AnchorPair> anchors = List.of(
                new AnchorPair("DIB Cashback Card", DETAIL_URL)
        );

        service.matchProductUrls(List.of(p), anchors, LISTING_URL);

        assertThat(p.getSourceUrl()).isEqualTo(DETAIL_URL);
    }

    @Test
    @DisplayName("matchProductUrls_punctuationStripped — 'DIB Cashback Card!' matches 'dib cashback card'")
    void matchProductUrls_punctuationStripped() {
        ExtractedProduct p = product("DIB Cashback Card!");
        List<AnchorPair> anchors = List.of(
                new AnchorPair("dib cashback card", DETAIL_URL)
        );

        service.matchProductUrls(List.of(p), anchors, LISTING_URL);

        assertThat(p.getSourceUrl()).isEqualTo(DETAIL_URL);
    }

    @Test
    @DisplayName("matchProductUrls_emptyAnchorList_allFallback — empty anchor list -> all products get listing URL")
    void matchProductUrls_emptyAnchorList_allFallback() {
        ExtractedProduct p1 = product("DIB Cashback Card");
        ExtractedProduct p2 = product("DIB Home Finance");

        service.matchProductUrls(List.of(p1, p2), List.of(), LISTING_URL);

        assertThat(p1.getSourceUrl()).isEqualTo(LISTING_URL);
        assertThat(p2.getSourceUrl()).isEqualTo(LISTING_URL);
    }

    // --- deduplicateByName tests ---

    @Test
    @DisplayName("deduplicateByName — exact duplicate names are removed, first occurrence kept")
    void deduplicateByName_removesExactDuplicates() {
        List<ExtractedProduct> input = List.of(
                product("DIB SHAMS Platinum Covered Card"),
                product("DIB SHAMS Infinite Covered Card"),
                product("DIB SHAMS Platinum Covered Card"),  // duplicate
                product("DIB SHAMS Platinum Covered Card")   // duplicate
        );

        List<ExtractedProduct> result = service.deduplicateByName(input);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getProductName()).isEqualTo("DIB SHAMS Platinum Covered Card");
        assertThat(result.get(1).getProductName()).isEqualTo("DIB SHAMS Infinite Covered Card");
    }

    @Test
    @DisplayName("deduplicateByName — case-insensitive; 'dib cashback card' and 'DIB Cashback Card' are the same product")
    void deduplicateByName_caseInsensitiveDeduplicate() {
        List<ExtractedProduct> input = List.of(
                product("DIB Cashback Card"),
                product("dib cashback card")
        );

        List<ExtractedProduct> result = service.deduplicateByName(input);

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("deduplicateByName — all unique names -> list unchanged")
    void deduplicateByName_allUnique_noChange() {
        List<ExtractedProduct> input = List.of(
                product("DIB SHAMS Platinum Covered Card"),
                product("DIB SHAMS Signature Covered Card"),
                product("DIB SHAMS Infinite Covered Card")
        );

        List<ExtractedProduct> result = service.deduplicateByName(input);

        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("deduplicateByName — empty list returns empty list")
    void deduplicateByName_emptyList() {
        assertThat(service.deduplicateByName(List.of())).isEmpty();
    }

    // --- scrapeAndAnalyze configured flow tests ---

    private static final BankSourceConfig DIB_CONFIG = new BankSourceConfig(
            "DIB", "https://dib.ae/personal/cards",
            "a[href^='/personal/cards/']:has(h3)", "h3", "", "p", "ul li", null
    );

    @Test
    @DisplayName("scrapeAndAnalyze_configuredFlow_usesStructuredCards — config present, 3 cards returned -> saveExtractedProducts called with 3 products, extractProducts NOT called")
    void scrapeAndAnalyze_configuredFlow_usesStructuredCards() {
        List<StructuredCard> cards = List.of(
                new StructuredCard("DIB Cashback Card", "https://dib.ae/personal/cards/cashback",
                        "Earn cashback", List.of("5% cashback")),
                new StructuredCard("DIB SHAMS Infinite", "https://dib.ae/personal/cards/shams-infinite",
                        "Premium card", List.of("Airport lounge")),
                new StructuredCard("DIB Signature Card", "https://dib.ae/personal/cards/signature",
                        null, List.of())
        );

        ScrapeResponse scrapeResponse = ScrapeResponse.builder()
                .url(LISTING_URL)
                .status("success")
                .textContent("page text content")
                .structuredCards(cards)
                .anchorPairs(List.of())
                .build();

        when(scraperProperties.findConfigForUrl(LISTING_URL)).thenReturn(Optional.of(DIB_CONFIG));
        when(basicScraperService.scrapeUrlWithConfig(LISTING_URL, DIB_CONFIG)).thenReturn(scrapeResponse);
        when(stagingProductService.saveExtractedProducts(anyList(), anyString(), anyString())).thenReturn(3);

        ScrapeResponse result = service.scrapeAndAnalyze(LISTING_URL);

        // LLM extraction must NOT be called for the configured flow
        verify(aiProductExtractor, never()).extractProducts(anyString(), anyString());
        verify(aiProductExtractor, never()).isProductPage(anyString());

        // Staging save must be called once with 3 products
        verify(stagingProductService).saveExtractedProducts(anyList(), eq(LISTING_URL), anyString());

        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getMessage()).contains("Configured extraction");
        assertThat(result.getMessage()).contains("3");
    }

    @Test
    @DisplayName("scrapeAndAnalyze_configuredFlow_emptyCards_fallsBackToGenericFlow — config present but 0 cards -> extractProducts IS called")
    void scrapeAndAnalyze_configuredFlow_emptyCards_fallsBackToGenericFlow() {
        ScrapeResponse scrapeResponse = ScrapeResponse.builder()
                .url(LISTING_URL)
                .status("success")
                .textContent("page text content")
                .structuredCards(List.of())   // zero cards — triggers fallback
                .anchorPairs(List.of())
                .build();

        when(scraperProperties.findConfigForUrl(LISTING_URL)).thenReturn(Optional.of(DIB_CONFIG));
        when(basicScraperService.scrapeUrlWithConfig(LISTING_URL, DIB_CONFIG)).thenReturn(scrapeResponse);
        // Generic flow: page is detected as a product page
        when(aiProductExtractor.isProductPage(anyString())).thenReturn(true);
        when(aiProductExtractor.extractProducts(anyString(), anyString())).thenReturn(List.of(
                product("DIB Cashback Card")
        ));
        when(stagingProductService.saveExtractedProducts(anyList(), anyString(), anyString())).thenReturn(1);

        ScrapeResponse result = service.scrapeAndAnalyze(LISTING_URL);

        // The generic LLM extraction must be invoked as fallback
        verify(aiProductExtractor).extractProducts(anyString(), anyString());
        assertThat(result.getStatus()).isEqualTo("success");
    }

    // --- convertStructuredCards tests ---

    @Test
    @DisplayName("convertStructuredCards_mapsFieldsCorrectly — name, href, description, and benefits all mapped")
    void convertStructuredCards_mapsFieldsCorrectly() {
        List<StructuredCard> cards = List.of(
                new StructuredCard("DIB Cashback Card", "https://dib.ae/personal/cards/cashback",
                        "Earn cashback on every spend", List.of("5% cashback", "No min spend"))
        );

        List<ExtractedProduct> products = service.convertStructuredCards(cards);

        assertThat(products).hasSize(1);
        ExtractedProduct p = products.get(0);
        assertThat(p.getProductName()).isEqualTo("DIB Cashback Card");
        assertThat(p.getSourceUrl()).isEqualTo("https://dib.ae/personal/cards/cashback");
        assertThat(p.getDescription()).isEqualTo("Earn cashback on every spend");
        assertThat(p.getKeyBenefits()).containsExactly("5% cashback", "No min spend");
    }

    @Test
    @DisplayName("convertStructuredCards_nullDescription_mappedToEmptyString — null description becomes empty string")
    void convertStructuredCards_nullDescription_mappedToEmptyString() {
        List<StructuredCard> cards = List.of(
                new StructuredCard("DIB Signature Card", "https://dib.ae/personal/cards/signature",
                        null, List.of())
        );

        List<ExtractedProduct> products = service.convertStructuredCards(cards);

        assertThat(products).hasSize(1);
        assertThat(products.get(0).getDescription()).isEqualTo("");
    }
}
