package com.smartguide.scraper.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import com.smartguide.scraper.config.BankSourceConfig;
import com.smartguide.scraper.dto.AnchorPair;
import com.smartguide.scraper.dto.ScrapeResponse;
import com.smartguide.scraper.dto.StructuredCard;
import com.smartguide.scraper.util.UrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Basic web scraping service using Playwright
 * MVP1: Simple synchronous scraping that extracts text content
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BasicScraperService {

    private final ObjectMapper objectMapper;
    private final ConfigurableCardExtractor configurableCardExtractor;

    /**
     * Targeted product card extraction JS.
     *
     * Selects only anchors whose href begins with the page's own path prefix (e.g. /personal/cards/).
     * This scopes extraction to product detail links and excludes global navigation, footer links,
     * and cross-category links that share the same origin but a different path.
     *
     * Name extraction priority:
     *   1. First h3 or h4 inside the anchor — the card title element on DIB listing pages.
     *   2. First non-empty line of the anchor's innerText — fallback for other page structures.
     *
     * Skip rules (adapted from DIB page structure analysis):
     *   - href ends with "#" or contains "javascript:"  — non-navigational links
     *   - href contains "apply-now" or "compare"        — action links, not product pages
     *   - href ends with ".pdf"                         — document links
     *   - href path equals the listing page path        — the page linking to itself
     *
     * Cards appear twice on DIB pages (spotlight carousel + main grid).
     * Deduplication is handled by the Java side (deduplicateByName in EnhancedScraperService).
     */
    private static final String ANCHOR_EXTRACTION_JS = """
            () => {
              const origin = window.location.origin;
              const listingPath = window.location.pathname;
              return JSON.stringify(
                Array.from(document.querySelectorAll('a[href^="' + listingPath + '/"]'))
                  .filter(a => {
                    const href = a.getAttribute('href') || '';
                    return !href.endsWith('#')
                        && !href.includes('javascript:')
                        && !href.includes('apply-now')
                        && !href.includes('compare')
                        && !href.endsWith('.pdf')
                        && href !== listingPath;
                  })
                  .map(a => {
                    const heading = a.querySelector('h3, h4');
                    const rawText = heading
                      ? heading.innerText.trim()
                      : (a.innerText.split('\\n').map(l => l.trim()).find(l => l.length > 0) || '');
                    return { text: rawText, href: origin + a.getAttribute('href') };
                  })
                  .filter(a => a.text.length > 0)
              );
            }
            """;

    private static final int MAX_ANCHOR_PAIRS = 200;

    /**
     * Scrape a URL and extract all text content
     */
    public ScrapeResponse scrapeUrl(String url) {
        // Validate URL
        if (!UrlValidator.isValidUrl(url)) {
            return ScrapeResponse.builder()
                .url(url)
                .status("error")
                .message("Invalid URL format")
                .build();
        }

        try (Playwright playwright = Playwright.create()) {
            // Use Firefox instead of Chromium to avoid macOS crash issues
            Browser browser = playwright.firefox().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();

            log.info("Scraping URL: {}", url);

            // Navigate to the URL.
            // DOMCONTENTLOADED fires once HTML is parsed — before images/fonts/stylesheets finish
            // loading. This avoids 60s timeouts on asset-heavy banking listing pages (e.g. 24 card
            // images). The 5-second post-navigate wait gives React/Angular time to render the
            // product list into the DOM before we extract text and anchors.
            page.navigate(url, new Page.NavigateOptions()
                .setTimeout(90000)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForTimeout(5000); // allow React/JS to render dynamic content

            // Extract page title
            String title = page.title();

            // Extract all visible text content
            String textContent = page.evaluate("() => document.body.innerText").toString();

            log.info("text content from scrapped page:{}", textContent);

            // Extract same-domain anchor pairs for product URL matching (Phase 1).
            // Wrapped in try/catch — a failure here must not abort the scrape.
            List<AnchorPair> anchorPairs = new ArrayList<>();
            try {
                String anchorJson = page.evaluate(ANCHOR_EXTRACTION_JS).toString();
                anchorPairs = parseAnchorPairs(anchorJson);
                log.info("Extracted {} anchor pairs from {}", anchorPairs.size(), url);
            } catch (Exception anchorEx) {
                log.warn("Anchor extraction failed for {} — proceeding with empty list: {}",
                        url, anchorEx.getMessage());
            }

            browser.close();

            log.info("Successfully scraped {} - extracted {} characters", url, textContent.length());

            return ScrapeResponse.builder()
                .url(url)
                .title(title)
                .textContent(textContent)
                .textLength(textContent.length())
                .anchorPairs(anchorPairs)
                .status("success")
                .message("Successfully scraped " + textContent.length() + " characters")
                .build();

        } catch (Exception e) {
            log.error("Error scraping URL: {}", url, e);
            return ScrapeResponse.builder()
                .url(url)
                .status("error")
                .message("Failed to scrape URL: " + e.getMessage())
                .build();
        }
    }

    /**
     * Scrape a URL using a configured {@link BankSourceConfig} for CSS-selector-based card extraction.
     *
     * <p>Uses the same Playwright setup as {@link #scrapeUrl(String)} (Firefox headless,
     * DOMCONTENTLOADED, 90 s timeout, 5 s post-navigate wait). After the page loads,
     * {@link ConfigurableCardExtractor#extractCards(Page, BankSourceConfig)} is called to produce
     * a {@link StructuredCard} list directly from the DOM — bypassing the LLM discovery flow.
     *
     * <p>The {@code textContent} and {@code title} fields of the returned response are also populated
     * so that the caller can fall back to the generic flow if the structured extraction yields zero cards.
     *
     * @param url    the listing page URL to scrape
     * @param config the bank-source configuration that matched this URL
     * @return {@link ScrapeResponse} with {@code structuredCards} (and {@code textContent}) populated;
     *         status is {@code "error"} on failure
     */
    public ScrapeResponse scrapeUrlWithConfig(String url, BankSourceConfig config) {
        if (!UrlValidator.isValidUrl(url)) {
            return ScrapeResponse.builder()
                    .url(url)
                    .status("error")
                    .message("Invalid URL format")
                    .build();
        }

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.firefox().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();

            log.info("Scraping URL with config (bank='{}'): {}", config.bank(), url);

            page.navigate(url, new Page.NavigateOptions()
                    .setTimeout(90000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForTimeout(5000);

            String title = page.title();
            String textContent = page.evaluate("() => document.body.innerText").toString();

            // Structured extraction via CSS selectors — core of the configured flow
            List<StructuredCard> structuredCards = configurableCardExtractor.extractCards(page, config);
            log.info("Configured extraction for bank='{}': {} structured card(s) extracted from {}",
                    config.bank(), structuredCards.size(), url);

            // Also run anchor extraction so the caller can fall back to the generic flow if needed
            List<AnchorPair> anchorPairs = new ArrayList<>();
            try {
                String anchorJson = page.evaluate(ANCHOR_EXTRACTION_JS).toString();
                anchorPairs = parseAnchorPairs(anchorJson);
                log.info("Extracted {} anchor pairs from {}", anchorPairs.size(), url);
            } catch (Exception anchorEx) {
                log.warn("Anchor extraction failed for {} — proceeding with empty list: {}",
                        url, anchorEx.getMessage());
            }

            browser.close();

            return ScrapeResponse.builder()
                    .url(url)
                    .title(title)
                    .textContent(textContent)
                    .textLength(textContent.length())
                    .anchorPairs(anchorPairs)
                    .structuredCards(structuredCards)
                    .status("success")
                    .message("Successfully scraped " + textContent.length() + " characters")
                    .build();

        } catch (Exception e) {
            log.error("Error scraping URL with config (bank='{}'): {}", config.bank(), url, e);
            return ScrapeResponse.builder()
                    .url(url)
                    .status("error")
                    .message("Failed to scrape URL: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Parse the JSON array returned by the anchor extraction JavaScript into a deduplicated,
     * capped list of {@link AnchorPair} objects.
     */
    private List<AnchorPair> parseAnchorPairs(String json) throws Exception {
        List<Map<String, String>> raw = objectMapper.readValue(
                json, new TypeReference<List<Map<String, String>>>() {});

        // Deduplicate by href while preserving first-seen order, then cap at MAX_ANCHOR_PAIRS
        Map<String, AnchorPair> seen = new LinkedHashMap<>();
        for (Map<String, String> entry : raw) {
            String href = entry.get("href");
            if (href != null && !seen.containsKey(href)) {
                seen.put(href, new AnchorPair(entry.get("text"), href));
            }
            if (seen.size() >= MAX_ANCHOR_PAIRS) {
                break;
            }
        }
        return new ArrayList<>(seen.values());
    }
}
