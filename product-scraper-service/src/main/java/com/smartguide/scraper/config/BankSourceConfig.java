package com.smartguide.scraper.config;

/**
 * Per-bank CSS selector configuration for structured product card extraction.
 *
 * <p>Each entry maps a bank's listing page URL prefix to the CSS selectors needed to
 * extract product cards directly from the DOM, bypassing the LLM discovery step.
 *
 * @param bank                    human-readable bank identifier (e.g. "DIB")
 * @param url                     listing page URL prefix; a scraped URL matches when it starts with this value
 * @param cardContainerSelector   CSS selector for product card container elements
 * @param nameSelector            CSS selector for the product name within each container
 * @param urlSelector             CSS selector for the link element within each container;
 *                                if blank, the container element's own {@code href} attribute is used
 * @param descriptionSelector     CSS selector for an optional description text within each container (nullable)
 * @param benefitsSelector        CSS selector for optional benefit list items within each container (nullable)
 * @param skipIfLabelContains     if non-blank, containers whose label text (case-insensitive) contains this
 *                                string are excluded — used to distinguish product cards from benefit tiles
 *                                that share the same container CSS class (e.g. "BENEFITS" for DIB)
 */
public record BankSourceConfig(
        String bank,
        String url,
        String cardContainerSelector,
        String nameSelector,
        String urlSelector,
        String descriptionSelector,
        String benefitsSelector,
        String skipIfLabelContains
) {}
