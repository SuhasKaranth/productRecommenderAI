package com.smartguide.scraper.dto;

/**
 * Represents a rendered anchor tag extracted from the DOM:
 * visible text (used for Java-side product name matching) and
 * absolute href (the candidate detail-page URL).
 */
public record AnchorPair(String text, String href) {
}
