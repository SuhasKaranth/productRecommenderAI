package com.smartguide.scraper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartguide.scraper.dto.ExtractedProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the scraper module's {@link StagingProductService#saveExtractedProducts}
 * and the internal {@code buildStagingRequest} contract.
 */
@ExtendWith(MockitoExtension.class)
class StagingProductServiceScraperTest {

    @Mock
    private WebClient webClient;

    private StagingProductService stagingProductService;

    private static final String LISTING_URL = "https://dib.ae/personal/cards";
    private static final String DETAIL_URL  = "https://dib.ae/personal/cards/dib-cashback-card";
    private static final String LISTING_TEXT = "Full listing page text content";

    @BeforeEach
    void setUp() {
        stagingProductService = new StagingProductService(webClient, new ObjectMapper());

        // Wire the @Value fields via reflection — avoids needing a Spring context
        setField(stagingProductService, "mainServiceUrl", "http://localhost:8080");
        setField(stagingProductService, "mainServiceApiKey", "test-key");
    }

    @Test
    @DisplayName("buildStagingRequest_usesProductSourceUrl_notListingUrl — sourceUrl comes from product.getSourceUrl()")
    @SuppressWarnings("unchecked")
    void buildStagingRequest_usesProductSourceUrl_notListingUrl() {
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec<?> headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.bodyValue(bodyCaptor.capture())).thenReturn((WebClient.RequestHeadersSpec) headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("{}"));

        ExtractedProduct product = ExtractedProduct.builder()
                .productName("DIB Cashback Card")
                .description("A cashback card")
                .category("CREDIT_CARD")
                .sourceUrl(DETAIL_URL)
                .build();

        stagingProductService.saveExtractedProducts(List.of(product), LISTING_URL, LISTING_TEXT);

        Map<String, Object> body = bodyCaptor.getValue();
        assertThat(body.get("sourceUrl"))
                .as("sourceUrl must be the product detail URL, not the listing URL")
                .isEqualTo(DETAIL_URL);
    }

    @Test
    @DisplayName("buildStagingRequest_includesListingPageRawContent — listingPageRawContent is in request body")
    @SuppressWarnings("unchecked")
    void buildStagingRequest_includesListingPageRawContent() {
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec<?> headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.bodyValue(bodyCaptor.capture())).thenReturn((WebClient.RequestHeadersSpec) headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("{}"));

        ExtractedProduct product = ExtractedProduct.builder()
                .productName("DIB Cashback Card")
                .description("A cashback card")
                .category("CREDIT_CARD")
                .sourceUrl(DETAIL_URL)
                .build();

        stagingProductService.saveExtractedProducts(List.of(product), LISTING_URL, LISTING_TEXT);

        Map<String, Object> body = bodyCaptor.getValue();
        assertThat(body.get("listingPageRawContent"))
                .as("listingPageRawContent must be included in the staging request body")
                .isEqualTo(LISTING_TEXT);
    }

    @Test
    @DisplayName("buildStagingRequest_setsRawContentSource_LISTING_PAGE — rawContentSource equals LISTING_PAGE")
    @SuppressWarnings("unchecked")
    void buildStagingRequest_setsRawContentSource_LISTING_PAGE() {
        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec<?> headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.bodyValue(bodyCaptor.capture())).thenReturn((WebClient.RequestHeadersSpec) headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("{}"));

        ExtractedProduct product = ExtractedProduct.builder()
                .productName("DIB Cashback Card")
                .description("A cashback card")
                .category("CREDIT_CARD")
                .sourceUrl(DETAIL_URL)
                .build();

        stagingProductService.saveExtractedProducts(List.of(product), LISTING_URL, LISTING_TEXT);

        Map<String, Object> body = bodyCaptor.getValue();
        assertThat(body.get("rawContentSource"))
                .as("rawContentSource must be LISTING_PAGE")
                .isEqualTo("LISTING_PAGE");
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Could not set field " + fieldName, e);
        }
    }
}
