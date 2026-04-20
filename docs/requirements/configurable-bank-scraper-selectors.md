# FEAT-SCRAPER-1: Configurable Bank Scraper CSS Selectors

**One-line summary**: Make product card DOM selectors configurable per bank in `application.yml` so the scraper can reliably extract all products from any bank listing page without relying on LLM discovery.

**Priority**: Must-have (blocks scraper accuracy for any bank with 10+ products per page)
**Target Sprint**: Next available sprint after current in-flight work
**Owner**: Product Owner — Smart Guide
**Date**: 2026-03-21

---

## Problem Statement

The scraper service (`BasicScraperService`) currently extracts product links from bank listing pages using a generic anchor-based JavaScript selector:

```js
document.querySelectorAll('a[href^="' + listingPath + '/"]')
```

This approach has two critical limitations:

1. **LLM token budget exhaustion**: After anchor extraction, `AIProductExtractor` sends the full page text to a local Ollama LLM (Llama 3.2) with a configurable token cap (`SCRAPER_LLM_MAX_TOKENS`, default 6000). On pages with 20+ products (e.g., DIB cards listing), the LLM runs out of output tokens after approximately 9 products. The remaining products are silently dropped. Increasing the token budget is not a sustainable fix -- it increases latency and cost, and still fails unpredictably on larger pages.

2. **No bank-specific reusability**: The anchor selector `a[href^="/personal/cards/"]` is hardcoded to match DIB's URL structure. Other banks use different DOM structures, class names, and URL patterns. Adding each new bank currently requires code changes to `BasicScraperService`, which is slow and error-prone.

3. **Anchor-only extraction misses structured content**: The current approach extracts only the product name and detail-page URL from anchors. Product descriptions, benefits, and key features visible on the listing page are discarded, forcing the LLM to rediscover them from flattened page text -- wasting tokens on information already present in the DOM.

**Business impact**: The product catalog has significant coverage gaps. Any bank listing page with more than ~9 products will have incomplete coverage, meaning the recommendation engine cannot surface products it does not know about.

---

## Proposed Solution

### Phase 1 (this feature): Manual configuration per bank

Add a `scraper.bank-sources` configuration block in the scraper service's `application.yml`. Each entry defines the CSS selectors needed to extract product cards from a specific bank's listing page. An engineer or admin inspects the bank's page HTML once, identifies the correct selectors, and adds a configuration entry. No code changes are needed per bank.

**Extraction strategy change**: When a bank source has configured selectors, `BasicScraperService` uses Playwright to query the DOM directly via `document.querySelectorAll(cardContainerSelector)`. For each matched container element, it extracts the product name, detail URL, description, and benefits using the configured sub-selectors. This produces a structured `List<ExtractedProduct>` with pre-populated fields, bypassing LLM discovery entirely.

The LLM is still used for enrichment (classification, Islamic finance structure identification, profit rate extraction) but receives a focused, per-product text block instead of the entire page -- dramatically reducing token consumption.

**Fallback**: If no bank source configuration matches the URL being scraped, the existing generic anchor-based extraction continues to operate unchanged. This ensures backward compatibility.

### Example Configuration

```yaml
scraper:
  bank-sources:
    - bank: DIB
      url: https://dib.ae/personal/cards
      card-container-selector: "div.card-list-item.show"
      name-selector: "h3, h4"
      url-selector: "a[href^='/personal/cards/']"
      description-selector: "p"
      benefits-selector: "ul li"

    - bank: DIB
      url: https://dib.ae/personal/finance
      card-container-selector: "div.card-list-item.show"
      name-selector: "h3, h4"
      url-selector: "a[href^='/personal/finance/']"
      description-selector: "p"
      benefits-selector: "ul li"

    - bank: ADIB
      url: https://adib.ae/en/personal/cards
      card-container-selector: "div.product-card"
      name-selector: "h3.product-title"
      url-selector: "a.product-link"
      description-selector: "div.product-desc p"
      benefits-selector: "ul.benefits li"
```

**Configuration field definitions**:

| Field | Required | Description |
|---|---|---|
| `bank` | Yes | Bank identifier (used in logs and staging records) |
| `url` | Yes | The listing page URL this config applies to. Matched by prefix. |
| `card-container-selector` | Yes | CSS selector for the outermost DOM element wrapping one product card |
| `name-selector` | Yes | CSS selector (relative to card container) for the product name |
| `url-selector` | Yes | CSS selector (relative to card container) for the anchor linking to the detail page |
| `description-selector` | No | CSS selector (relative to card container) for product description text |
| `benefits-selector` | No | CSS selector (relative to card container) for benefit list items |

### Implementation scope

1. **New configuration class**: A Spring `@ConfigurationProperties` class that binds `scraper.bank-sources` to a `List<BankSourceConfig>`.

2. **BasicScraperService changes**: Add a new Playwright JS extraction method that takes selectors as parameters. When a matching `BankSourceConfig` exists for the target URL, use container-based extraction. Otherwise, fall back to the existing `ANCHOR_EXTRACTION_JS`.

3. **EnhancedScraperService changes**: When container-based extraction returns pre-populated products, skip the LLM discovery step (`isProductPage` + `extractProducts` from full page text). Instead, pass each product's extracted text block to the LLM individually for enrichment (category, Islamic structure, confidence score).

4. **ScrapeResponse changes**: Include a `extractionMethod` field (`CONFIGURED_SELECTORS` or `GENERIC_ANCHORS`) so downstream consumers and logs can distinguish how products were found.

---

## Expected Outcome

| Metric | Before | After |
|---|---|---|
| Products extracted from DIB cards page | ~9 of 24 | 24 of 24 |
| LLM tokens consumed per listing page | 6000+ (single call, full page) | ~800 per product (targeted enrichment) |
| Time to add a new bank | Code change + deploy | Config change + restart |
| Product name accuracy | Depends on LLM interpretation | Exact DOM text extraction |
| Detail page URL accuracy | ~70% (fuzzy name matching) | ~98% (direct href extraction) |

**For the admin**: Complete product coverage from day one for any configured bank. No more missing products in the staging queue.

**For the recommendation engine**: A fuller product catalog means better recommendations and fewer zero-result queries.

---

## Phase 2 (future): LLM Auto-Discovery of Selectors

In a future iteration, the system can use an LLM to automatically identify the correct CSS selectors for a new bank's listing page. The admin would provide only the listing URL, and the LLM would analyze the page's DOM structure to propose a `BankSourceConfig`. The admin would review and approve the suggested selectors before they take effect. This eliminates the need for manual HTML inspection but requires the Phase 1 configuration infrastructure to be in place first. Phase 2 is explicitly out of scope for this feature.

---

## Regression Impact

The following existing functionality must be re-validated after this change:

- **Generic anchor extraction**: Must continue to work identically for URLs that do not match any `bank-sources` entry. The `ANCHOR_EXTRACTION_JS` path in `BasicScraperService` must remain untouched.
- **LLM product classification** (`isProductPage`): Still called for unconfigured URLs. Verify it is correctly bypassed for configured URLs.
- **LLM product extraction** (`extractProducts`): Still called for unconfigured URLs. Verify enrichment-only mode works correctly for configured URLs.
- **Deduplication** (`deduplicateByName`): Must still handle duplicates from configured selectors (some pages render the same product in multiple sections).
- **URL matching** (`matchProductUrls`): Bypassed when the container selector already extracts the URL directly. Verify the fallback path still works.
- **Staging product save** (`StagingProductService.saveExtractedProducts`): Must accept products from both extraction methods without schema changes.
- **Scraper API response format**: `ScrapeResponse` must remain backward-compatible for any consumers. The new `extractionMethod` field is additive only.

---

## Testing Scope

### Unit tests
- `BankSourceConfig` binding: verify `application.yml` values are correctly parsed into the config object
- URL prefix matching: verify the correct `BankSourceConfig` is selected (or none) for a given URL
- Container-based JS extraction: mock Playwright `page.evaluate()` with sample DOM, verify correct product fields are extracted
- Fallback: verify generic anchor extraction is used when no config matches

### Integration tests
- End-to-end scrape of a configured URL using a local HTML fixture served by a test HTTP server
- Verify staging products are saved with correct fields from container-based extraction
- Verify the existing generic scrape path still works for an unconfigured URL

### Manual tests
- Scrape the live DIB cards page (`https://dib.ae/personal/cards`) with the configured selectors and verify all 20+ products appear in staging
- Scrape a non-DIB URL without configuration and verify the existing behavior is unchanged
- Intentionally misconfigure a selector and verify the scraper logs a clear error and falls back gracefully

---

## Out of Scope

- **LLM auto-discovery of selectors** (Phase 2)
- **Admin UI for managing bank source configurations** -- config is in `application.yml` only for now
- **Detail page scraping** -- this feature covers listing page extraction only; scraping individual product detail pages is a separate feature
- **Scheduled/automated re-scraping** -- this feature is triggered manually via the existing scraper API
- **Database-backed selector storage** -- selectors live in `application.yml`; moving them to a database table is a future enhancement
- **Selector versioning or change history** -- not needed until selectors are managed via UI
