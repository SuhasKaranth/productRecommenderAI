package com.smartguide.scraper.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import com.smartguide.scraper.config.BankSourceConfig;
import com.smartguide.scraper.config.ScraperProperties;
import com.smartguide.scraper.dto.StructuredCard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extracts product cards from a live Playwright {@link Page} using CSS selectors
 * declared in {@link BankSourceConfig}.
 *
 * <p>The DOM query is performed entirely in the browser via a single JavaScript evaluation
 * — no HTTP round-trips occur. For each matching container element the script extracts:
 * product name, href, optional description, and optional benefit list items.
 * Containers with a blank name or blank href are skipped by the script before the result
 * is returned to Java.
 *
 * <p>All failures (JS evaluation exceptions, JSON parse errors) are caught and logged as WARN.
 * The method always returns a list and never throws.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigurableCardExtractor {

    private final ScraperProperties scraperProperties;
    private final ObjectMapper objectMapper;

    /**
     * Extract structured product cards from the given page using the CSS selectors in {@code config}.
     *
     * @param page   live Playwright page; must already be navigated to the target URL
     * @param config bank-source configuration supplying the CSS selectors
     * @return list of {@link StructuredCard}; empty if no cards match or if JS evaluation fails
     */
    public List<StructuredCard> extractCards(Page page, BankSourceConfig config) {
        String js = buildExtractionScript(config);
        log.debug("Running configurable card extraction for bank='{}', containerSelector='{}'",
                config.bank(), config.cardContainerSelector());

        String json;
        try {
            Object result = page.evaluate(js);
            json = result != null ? result.toString() : "[]";
        } catch (Exception e) {
            log.warn("JS evaluation failed for bank='{}' — returning empty card list: {}",
                    config.bank(), e.getMessage());
            return new ArrayList<>();
        }

        return parseCards(json, config.bank());
    }

    /**
     * Build the JavaScript that will be evaluated inside the browser to extract card data.
     * The script returns a JSON string representing an array of card objects.
     *
     * <p>If {@code urlSelector} in the config is blank, the container element's own
     * {@code href} attribute is used instead.
     *
     * <p>If {@code skipIfLabelContains} is set, any container whose full inner text includes
     * that string (case-insensitive) is excluded before other processing occurs.
     * This handles pages like DIB where product cards and benefit tiles share the same
     * container CSS class but carry different heading labels ("COVERED CARDS" vs
     * "COVERED CARDS BENEFITS").
     */
    private String buildExtractionScript(BankSourceConfig config) {
        String containerSel = escapeJsString(config.cardContainerSelector());
        String nameSel = escapeJsString(config.nameSelector());
        String urlSel = config.urlSelector() != null ? escapeJsString(config.urlSelector()) : "";
        String descSel = config.descriptionSelector() != null
                ? escapeJsString(config.descriptionSelector()) : "";
        String benefitsSel = config.benefitsSelector() != null
                ? escapeJsString(config.benefitsSelector()) : "";
        // Empty string disables the label filter in JS
        String skipLabel = config.skipIfLabelContains() != null
                ? escapeJsString(config.skipIfLabelContains().toUpperCase()) : "";

        return """
                () => {
                  const origin = window.location.origin;
                  const listingPath = window.location.pathname;
                  const skipLabel = '%s';
                  const containers = Array.from(document.querySelectorAll('%s'));
                  const cards = containers
                    .filter(container => {
                      // Skip containers whose visible text contains the configured exclusion string.
                      // We check the full container innerText so we don't rely on an assumed label
                      // CSS class — "COVERED CARDS BENEFITS" appears in the container text while
                      // product cards only contain "COVERED CARDS" (without "BENEFITS").
                      if (skipLabel && container.innerText.toUpperCase().includes(skipLabel)) return false;
                      // Skip non-navigational and action links (mirrors BasicScraperService skip rules)
                      const rawHref = container.getAttribute('href') || '';
                      return !rawHref.endsWith('#')
                          && !rawHref.includes('javascript:')
                          && !rawHref.includes('apply-now')
                          && !rawHref.includes('compare')
                          && !rawHref.endsWith('.pdf')
                          && rawHref !== listingPath;
                    })
                    .map(container => {
                    // Name: try heading selector first; fall back to first non-empty innerText line
                    const nameEl = container.querySelector('%s');
                    const name = (nameEl && nameEl.innerText.trim())
                      ? nameEl.innerText.trim()
                      : (container.innerText.split('\\n').map(l => l.trim()).find(l => l.length > 0) || '');

                    // Href: prefer explicit urlSelector, fall back to container's own href
                    let href = '';
                    const urlSel = '%s';
                    if (urlSel) {
                      const linkEl = container.querySelector(urlSel);
                      href = linkEl ? (linkEl.getAttribute('href') || '') : '';
                    } else {
                      href = container.getAttribute('href') || '';
                    }
                    // Resolve root-relative hrefs to absolute
                    if (href && href.startsWith('/')) {
                      href = origin + href;
                    }

                    // Description (optional)
                    let description = null;
                    const descSel = '%s';
                    if (descSel) {
                      const descEl = container.querySelector(descSel);
                      if (descEl) {
                        const text = descEl.innerText.trim();
                        if (text) description = text;
                      }
                    }

                    // Benefits (optional list items)
                    const benefits = [];
                    const benefitsSel = '%s';
                    if (benefitsSel) {
                      Array.from(container.querySelectorAll(benefitsSel)).forEach(li => {
                        const text = li.innerText.trim();
                        if (text) benefits.push(text);
                      });
                    }

                    return { name, href, description, benefits };
                  }).filter(card => card.name.length > 0 && card.href.length > 0);

                  return JSON.stringify(cards);
                }
                """.formatted(skipLabel, containerSel, nameSel, urlSel, descSel, benefitsSel);
    }

    /**
     * Parse the JSON string returned by the browser script into a list of {@link StructuredCard}.
     */
    private List<StructuredCard> parseCards(String json, String bank) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return new ArrayList<>();
        }
        try {
            ObjectMapper lenient = objectMapper.copy()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            List<Map<String, Object>> rawCards = lenient.readValue(
                    json, new TypeReference<List<Map<String, Object>>>() {});

            List<StructuredCard> cards = new ArrayList<>();
            for (Map<String, Object> raw : rawCards) {
                String name = getString(raw, "name");
                String href = getString(raw, "href");
                if (name == null || name.isBlank() || href == null || href.isBlank()) {
                    log.debug("Skipping card with blank name or href for bank='{}'", bank);
                    continue;
                }
                // Java-side safety net: reject action/document links that the JS filter
                // should already have removed (defence-in-depth against unexpected JS output)
                if (href.contains("apply-now") || href.contains("compare")
                        || href.endsWith(".pdf") || href.endsWith("#")
                        || href.contains("javascript:")) {
                    log.debug("Skipping action/doc link for bank='{}': {}", bank, href);
                    continue;
                }
                String description = getString(raw, "description");
                List<String> benefits = getStringList(raw, "benefits");
                cards.add(new StructuredCard(name, href, description, benefits));
            }

            log.info("Configurable extraction for bank='{}': {} card(s) parsed", bank, cards.size());
            return cards;
        } catch (Exception e) {
            log.warn("Failed to parse card JSON for bank='{}': {}", bank, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Escape a string for safe embedding in a JavaScript single-quoted string literal. */
    private String escapeJsString(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List) {
            return ((List<?>) value).stream()
                    .filter(item -> item != null && !item.toString().isBlank())
                    .map(Object::toString)
                    .collect(java.util.stream.Collectors.toList());
        }
        return new ArrayList<>();
    }
}
