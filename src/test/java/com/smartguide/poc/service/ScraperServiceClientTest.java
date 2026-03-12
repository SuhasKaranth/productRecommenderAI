package com.smartguide.poc.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ScraperServiceClient#scrapePageContent(String)}.
 * <p>
 * A {@link WebClient} backed by a mock {@link org.springframework.web.reactive.function.client.ExchangeFunction}
 * is used so that no real HTTP connection is made. This avoids the need for
 * additional test dependencies (MockWebServer, WireMock, etc.).
 */
class ScraperServiceClientTest {

    /**
     * Helper: build a ScraperServiceClient whose WebClient always returns the given JSON body.
     */
    private ScraperServiceClient clientWithResponse(String jsonBody) {
        WebClient mockWebClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(
                        ClientResponse.create(HttpStatus.OK)
                                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                .body(jsonBody)
                                .build()))
                .build();

        ScraperServiceClient client = new ScraperServiceClient(mockWebClient);
        // Set scraperHost via reflection (package-private constructor skips @Value injection)
        try {
            var field = ScraperServiceClient.class.getDeclaredField("scraperHost");
            field.setAccessible(true);
            field.set(client, "http://localhost:8081");
        } catch (Exception e) {
            throw new RuntimeException("Could not set scraperHost field for test", e);
        }
        return client;
    }

    // -----------------------------------------------------------------------
    // scrapePageContent — success: textContent returned
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("scrapePageContent: returns textContent when scraper responds with status 'success'")
    void scrapePageContent_success() {
        // Given — scraper returns a successful response with textContent
        String responseJson = """
                {
                  "url": "https://dib.ae/home-finance",
                  "title": "Home Finance",
                  "textContent": "Rich product page content from the bank website",
                  "textLength": 47,
                  "status": "success",
                  "message": "Successfully scraped 47 characters"
                }
                """;
        ScraperServiceClient client = clientWithResponse(responseJson);

        // When
        String result = client.scrapePageContent("https://dib.ae/home-finance");

        // Then
        assertThat(result).isEqualTo("Rich product page content from the bank website");
    }

    // -----------------------------------------------------------------------
    // scrapePageContent — error status: RuntimeException thrown
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("scrapePageContent: throws RuntimeException when scraper responds with status 'error'")
    void scrapePageContent_errorStatus() {
        // Given — scraper returns an error response
        String responseJson = """
                {
                  "url": "https://dib.ae/bad-url",
                  "status": "error",
                  "message": "Invalid URL format"
                }
                """;
        ScraperServiceClient client = clientWithResponse(responseJson);

        // When / Then
        assertThatThrownBy(() -> client.scrapePageContent("https://dib.ae/bad-url"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to scrape URL")
                .hasMessageContaining("Invalid URL format");
    }

    // -----------------------------------------------------------------------
    // scrapePageContent — null textContent handled gracefully
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("scrapePageContent: returns null textContent when response body omits the field")
    void scrapePageContent_nullTextContent() {
        // Given — status is success but textContent is absent (null after deserialization)
        String responseJson = """
                {
                  "url": "https://dib.ae/home-finance",
                  "status": "success"
                }
                """;
        ScraperServiceClient client = clientWithResponse(responseJson);

        // When — should not throw; the caller (ProductService) validates the result
        String result = client.scrapePageContent("https://dib.ae/home-finance");

        // Then — null is returned and the caller is responsible for detecting blank content
        assertThat(result).isNull();
    }
}
