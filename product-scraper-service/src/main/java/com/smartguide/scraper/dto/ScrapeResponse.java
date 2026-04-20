package com.smartguide.scraper.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScrapeResponse {
    private String url;
    private String title;
    private String textContent;
    private Integer textLength;
    private String status;
    private String message;

    /** Rendered same-domain anchor pairs extracted from the DOM while the browser was open. */
    @Builder.Default
    private List<AnchorPair> anchorPairs = new ArrayList<>();

    /**
     * Product cards extracted via configured CSS selectors (configured-bank flow only).
     * Empty for unconfigured URLs — those use the generic anchor-extraction and LLM flow.
     */
    @Builder.Default
    private List<StructuredCard> structuredCards = new ArrayList<>();
}
