package com.smartguide.poc.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.time.Duration;
import java.util.Map;

/**
 * Thin WebClient wrapper that calls the scraper service's {@code /api/scraper/scrape-url} endpoint.
 * <p>
 * Returns the raw page text content for a given URL. The scraper service runs Playwright
 * internally, so timeout is set to 120 seconds to accommodate slow bank websites.
 * <p>
 * Configuration:
 * <ul>
 *   <li>{@code app.scraper-service.url} — base URL of the scraper service
 *       (default: {@code http://localhost:8081})</li>
 * </ul>
 *
 * @throws RuntimeException if the scraper service is unreachable or returns an error status
 */
@Service
@Slf4j
public class ScraperServiceClient {

    /** Playwright scraping is slow on bank sites — allow 2 minutes before timing out. */
    private static final Duration SCRAPE_TIMEOUT = Duration.ofSeconds(120);

    private final WebClient webClient;

    @Value("${app.scraper-service.url:http://localhost:8081}")
    private String scraperHost;

    /**
     * Default constructor used by Spring — creates its own WebClient instance,
     * matching the pattern used by {@link LLMService}.
     */
    public ScraperServiceClient() {
        this.webClient = WebClient.builder().build();
    }

    /**
     * Constructor for unit tests — accepts a pre-configured (mock) WebClient.
     *
     * @param webClient the WebClient to use for HTTP calls
     */
    ScraperServiceClient(WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Scrape the given URL by delegating to the scraper service's
     * {@code POST /api/scraper/scrape-url} endpoint.
     *
     * @param url the absolute URL to scrape
     * @return the raw text content extracted from the page
     * @throws RuntimeException if the scraper service is unreachable,
     *                          returns HTTP error, or reports status "error"
     */
    public String scrapePageContent(String url) {
        log.info("Requesting page content from scraper service for URL: {}", url);

        ScrapeResult result;
        try {
            result = webClient.post()
                    .uri(scraperHost + "/api/scraper/scrape-url")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("url", url))
                    .retrieve()
                    .bodyToMono(ScrapeResult.class)
                    .timeout(SCRAPE_TIMEOUT)
                    .onErrorMap(WebClientRequestException.class, ex ->
                            new RuntimeException("Scraper service unavailable: " + ex.getMessage(), ex))
                    .block();
        } catch (RuntimeException ex) {
            // Re-throw runtime exceptions (including our mapped connection error) as-is
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException("Scraper service unavailable: " + ex.getMessage(), ex);
        }

        if (result == null) {
            throw new RuntimeException("Scraper service returned null response for URL: " + url);
        }

        if ("error".equalsIgnoreCase(result.status())) {
            String detail = result.message() != null ? result.message() : "unknown error";
            throw new RuntimeException("Failed to scrape URL: " + detail);
        }

        String text = result.textContent();
        log.info("Scraper service returned {} chars for URL: {}", text != null ? text.length() : 0, url);
        return text;
    }

    /**
     * Local record that maps the scraper service's {@code ScrapeResponse} JSON fields.
     * Fields match the scraper-side DTO: url, title, textContent, textLength, status, message.
     */
    private record ScrapeResult(
            String url,
            String title,
            String textContent,
            Integer textLength,
            String status,
            String message
    ) {}
}
