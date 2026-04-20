package com.smartguide.scraper.dto;

import java.util.List;

/**
 * A product card extracted from the DOM using configured CSS selectors.
 *
 * <p>Produced by {@code ConfigurableCardExtractor} during the configured-bank flow.
 * All fields come directly from DOM queries — no LLM is involved.
 *
 * @param name        product name extracted from the configured name selector (never blank)
 * @param href        absolute or root-relative URL extracted from the configured URL selector
 *                    or the container's {@code href} attribute (never blank)
 * @param description optional product description text; {@code null} when the selector is blank
 *                    or yields no text
 * @param benefits    list of benefit strings extracted from list items; never {@code null},
 *                    may be empty when the selector is blank or yields no items
 */
public record StructuredCard(
        String name,
        String href,
        String description,
        List<String> benefits
) {}
