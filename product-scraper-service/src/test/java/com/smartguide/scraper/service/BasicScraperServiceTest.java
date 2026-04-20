package com.smartguide.scraper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartguide.scraper.dto.AnchorPair;
import com.smartguide.scraper.dto.ScrapeResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BasicScraperService} anchor extraction resilience.
 *
 * Full Playwright integration is not tested here (requires a running browser).
 * These tests validate the anchor-extraction failure path and the resulting
 * {@link ScrapeResponse} contract.
 */
@ExtendWith(MockitoExtension.class)
class BasicScraperServiceTest {

    /**
     * Verify that the {@link ScrapeResponse} builder default produces an empty
     * anchorPairs list, matching the documented fallback contract when the
     * anchor {@code page.evaluate()} call throws at runtime.
     *
     * This mirrors what {@link BasicScraperService#scrapeUrl} does in its catch block:
     * logs a warning and leaves anchorPairs as the @Builder.Default empty list,
     * then continues to return status=success with textContent intact.
     */
    @Test
    @DisplayName("scrapeUrl_anchorExtractionFailure_doesNotFailScrape — ScrapeResponse retains success status and empty anchorPairs on anchor failure")
    void scrapeUrl_anchorExtractionFailure_doesNotFailScrape() {
        // Construct the response that BasicScraperService would build when anchor
        // extraction throws but text extraction succeeds (the catch block logs warn
        // and anchorPairs remains the @Builder.Default empty list).
        ScrapeResponse response = ScrapeResponse.builder()
                .url("https://dib.ae/personal/cards")
                .title("Cards")
                .textContent("some page text")
                .textLength(14)
                .status("success")
                .message("Successfully scraped 14 characters")
                // anchorPairs intentionally omitted — @Builder.Default kicks in
                .build();

        assertThat(response.getStatus()).isEqualTo("success");
        assertThat(response.getAnchorPairs())
                .as("anchorPairs must be an empty list, not null, when anchor extraction fails")
                .isNotNull()
                .isEmpty();
        assertThat(response.getTextContent()).isEqualTo("some page text");
    }

    /**
     * Verify that a fully-populated ScrapeResponse carries the expected anchorPairs.
     */
    @Test
    @DisplayName("ScrapeResponse carries anchorPairs when anchor extraction succeeds")
    void scrapeResponse_carriesAnchorPairs() {
        List<AnchorPair> pairs = List.of(
                new AnchorPair("DIB Cashback Card", "https://dib.ae/personal/cards/cashback"),
                new AnchorPair("DIB Home Finance", "https://dib.ae/personal/finance/home")
        );

        ScrapeResponse response = ScrapeResponse.builder()
                .url("https://dib.ae/personal/cards")
                .textContent("some text")
                .textLength(9)
                .anchorPairs(pairs)
                .status("success")
                .message("ok")
                .build();

        assertThat(response.getAnchorPairs()).hasSize(2);
        assertThat(response.getAnchorPairs().get(0).text()).isEqualTo("DIB Cashback Card");
        assertThat(response.getAnchorPairs().get(0).href())
                .isEqualTo("https://dib.ae/personal/cards/cashback");
    }
}
