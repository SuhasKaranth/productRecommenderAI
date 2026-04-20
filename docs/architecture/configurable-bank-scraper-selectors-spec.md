# FEAT-SCRAPER-1: Configurable Bank Scraper CSS Selectors -- Technical Specification

**Feature**: Make product card DOM selectors configurable per bank in `application.yml`
**Status**: Ready for implementation
**Date**: 2026-03-21
**Spec version**: 1.0

---

## 1. Overview

This feature adds a YAML-driven configuration layer to the scraper service so that each bank's
listing page can declare its own CSS selectors for product card containers, names, URLs,
descriptions, and benefits. When a URL being scraped matches a configured bank source, Playwright
queries the DOM directly using those selectors and produces a structured `List<StructuredCard>` --
bypassing the LLM-based product discovery entirely. The LLM is no longer needed for discovery on
configured pages, eliminating the token-budget ceiling that currently caps extraction at ~9 products
per page. For unconfigured URLs, the existing generic anchor-extraction and LLM-discovery flow
continues to operate with zero changes.

### Relationship to existing code

This feature builds directly on top of the Phase 1 DOM anchor extraction delivered in the previous
sprint (documented in `docs/architecture/product-page-url-extraction-specs.md`). The key classes
being modified are the same ones touched in that feature: `BasicScraperService`,
`EnhancedScraperService`, and `ScrapeResponse`. The existing `ANCHOR_EXTRACTION_JS` in
`BasicScraperService` (line 51), the `matchProductUrls()` method in `EnhancedScraperService`
(line 146), and the `AIProductExtractor.extractProducts()` / `isProductPage()` methods remain
unchanged and continue to serve unconfigured URLs.

---

## 2. New Classes / Files

### 2.1 `BankSourceConfig` -- per-bank selector configuration record

- **Package**: `com.smartguide.scraper.config`
- **File**: `product-scraper-service/src/main/java/com/smartguide/scraper/config/BankSourceConfig.java`
- **Type**: Java 17 `record`
- **Purpose**: Immutable holder for one bank listing page's CSS selector configuration, bound from a single entry in the `scraper.bank-sources` YAML list.

```
Fields:
  String bank                       -- Bank identifier (e.g. "DIB", "ADIB"). Used in logs and staging records.
                                       @NotBlank, required.
  String url                        -- Listing page URL this config applies to. Matched by prefix.
                                       @NotBlank, required.
  String cardContainerSelector      -- CSS selector for the outermost DOM element wrapping one product card.
                                       @NotBlank, required.
  String nameSelector               -- CSS selector relative to card container for the product name element.
                                       @NotBlank, required.
  String urlSelector                -- CSS selector relative to card container for the anchor linking to the
                                       detail page.
                                       @NotBlank, required.
  String descriptionSelector        -- CSS selector relative to card container for description text.
                                       @Nullable, optional. Defaults to null.
  String benefitsSelector           -- CSS selector relative to card container for benefit list items.
                                       @Nullable, optional. Defaults to null.
```

**Notes**:
- The record uses `@jakarta.validation.constraints.NotBlank` on the required fields.
- Spring Boot 3.2 supports records in `@ConfigurationProperties` binding via constructor binding natively.
- YAML key `card-container-selector` maps to constructor parameter `cardContainerSelector` via relaxed binding.

### 2.2 `ScraperProperties` -- configuration properties root

- **Package**: `com.smartguide.scraper.config`
- **File**: `product-scraper-service/src/main/java/com/smartguide/scraper/config/ScraperProperties.java`
- **Type**: `@ConfigurationProperties(prefix = "scraper")` class (not a record -- Spring Boot 3.2 requires a mutable class for `@Validated` + nested list binding to work reliably with records)
- **Annotations**: `@ConfigurationProperties(prefix = "scraper")`, `@Validated`, `@Component`
- **Purpose**: Binds the `scraper.bank-sources` YAML list and provides a lookup method.

```
Fields:
  @Valid
  List<BankSourceConfig> bankSources = new ArrayList<>()
    -- The full list of configured bank source entries. Empty by default (generic flow, no error).

Methods:
  Optional<BankSourceConfig> findConfigForUrl(String url)
    -- Iterates bankSources in list order, returns the first entry whose `url` field is a prefix
       of the given URL (case-sensitive comparison using url.startsWith(config.url())).
    -- If multiple entries match, returns the first match and logs a warning at WARN level:
       "Multiple bank-source configs match URL '{}' -- using first match: {}"
    -- If no entry matches, returns Optional.empty().
```

**Enable configuration properties scanning** by adding `@EnableConfigurationProperties(ScraperProperties.class)` to the main application class, or by annotating the class with `@Component` (simpler, chosen here).

### 2.3 `StructuredCard` -- intermediate data carrier from JS extraction

- **Package**: `com.smartguide.scraper.dto`
- **File**: `product-scraper-service/src/main/java/com/smartguide/scraper/dto/StructuredCard.java`
- **Type**: Java 17 `record`
- **Purpose**: Carries per-card data extracted by the configured-selector JavaScript from the browser back to Java. This is a separate type from `AnchorPair` because it carries four fields (name, href, description, benefits) rather than two (text, href). `AnchorPair` is unchanged and continues to serve the generic anchor flow.

```
Fields:
  String name          -- Product name extracted from the name-selector element (h3/h4 innerText).
  String href          -- Absolute URL to the product detail page, resolved from the url-selector anchor.
  String description   -- Text content from the description-selector element. May be null if selector
                          was not configured or matched nothing.
  List<String> benefits -- List of benefit strings from the benefits-selector elements. May be empty
                           if selector was not configured or matched nothing.
```

**Rationale for not extending AnchorPair**: `AnchorPair` is a two-field record used as the key type in `ScrapeResponse.anchorPairs` and in `EnhancedScraperService.matchProductUrls()`. Adding optional fields to it would change its semantics and require updating all existing consumers. A separate record is cleaner.

---

## 3. Modified Classes

### 3.1 `BasicScraperService.java`

**File**: `product-scraper-service/src/main/java/com/smartguide/scraper/service/BasicScraperService.java`

#### What must NOT change
- The `scrapeUrl(String url)` method signature, return type, and behavior are completely unchanged.
- The `ANCHOR_EXTRACTION_JS` constant is unchanged.
- The `parseAnchorPairs()` private method is unchanged.
- The constructor injection of `ObjectMapper` is unchanged.

#### New constructor dependency
- Inject `ScraperProperties` via the existing `@RequiredArgsConstructor` pattern (add as a `final` field).

#### New method: `scrapeUrlWithConfig(String url, BankSourceConfig config)`

```java
public ScrapeResponse scrapeUrlWithConfig(String url, BankSourceConfig config)
```

**Purpose**: Scrapes the given URL using Playwright, then runs a configured-selector JavaScript
expression (instead of `ANCHOR_EXTRACTION_JS`) to extract structured product cards from the DOM.

**Implementation algorithm**:

1. Validate URL using `UrlValidator.isValidUrl(url)`. On failure, return error `ScrapeResponse` (same pattern as `scrapeUrl`).
2. Create Playwright browser (Firefox, headless) -- same as `scrapeUrl`.
3. Navigate with `DOMCONTENTLOADED` + 90s timeout + 5s `waitForTimeout` -- same as `scrapeUrl`.
4. Extract `title` via `page.title()` -- same as `scrapeUrl`.
5. Extract `textContent` via `page.evaluate("() => document.body.innerText")` -- same as `scrapeUrl`.
6. Build the configured-selector JavaScript string (see Section 5) by interpolating `config.cardContainerSelector()`, `config.nameSelector()`, `config.urlSelector()`, `config.descriptionSelector()`, and `config.benefitsSelector()` into the template. If `descriptionSelector` is null, the JS uses `null` for that selector. Same for `benefitsSelector`.
7. Execute the configured JS via `page.evaluate(js)` to get a JSON string.
8. Parse the JSON string into `List<StructuredCard>` using `ObjectMapper` (new private method `parseStructuredCards(String json)`).
9. Also extract `anchorPairs` using the existing `ANCHOR_EXTRACTION_JS` and `parseAnchorPairs()` -- this populates the `ScrapeResponse.anchorPairs` field for backward compatibility and for any downstream consumer that relies on it.
10. Close the browser.
11. Build and return `ScrapeResponse` with:
    - `url`, `title`, `textContent`, `textLength`, `status="success"`, `message` -- same as `scrapeUrl`
    - `anchorPairs` -- from step 9
    - `structuredCards` -- from step 8 (new field on `ScrapeResponse`)
    - `extractionMethod` -- `"CONFIGURED_SELECTORS"` (new field on `ScrapeResponse`)
12. Wrap steps 7-8 in try/catch. On failure: log WARN, set `structuredCards` to empty list, set `extractionMethod` to `"GENERIC_ANCHORS"`. The scrape does not fail; `EnhancedScraperService` will fall through to the generic LLM path.

**Error handling**: If the Playwright navigation itself fails, return an error `ScrapeResponse` (same pattern as `scrapeUrl`). The configured-selector JS failure is a soft failure that logs and falls back.

#### New private method: `parseStructuredCards(String json)`

```java
private List<StructuredCard> parseStructuredCards(String json) throws Exception
```

Parses the JSON array returned by the configured-selector JavaScript into a list of `StructuredCard` records. Uses `ObjectMapper.readValue(json, new TypeReference<List<StructuredCard>>() {})`. Deduplicates by `href` (same logic as `parseAnchorPairs`), caps at `MAX_ANCHOR_PAIRS` (200).

### 3.2 `EnhancedScraperService.java`

**File**: `product-scraper-service/src/main/java/com/smartguide/scraper/service/EnhancedScraperService.java`

#### What must NOT change
- The `deduplicateByName()` method is unchanged.
- The `matchProductUrls()` method is unchanged.
- The `extractProductNameHints()` method is unchanged.
- The `normalize()` method is unchanged.

#### New constructor dependency
- Inject `ScraperProperties` via the existing `@RequiredArgsConstructor` pattern (add as a `final` field).

#### Modified method: `scrapeAndAnalyze(String url)`

The existing method is restructured with a branch at the top. The complete revised algorithm:

```
public ScrapeResponse scrapeAndAnalyze(String url) {
    log.info("Starting enhanced scraping for URL: {}", url);

    // NEW: Check for configured bank source
    Optional<BankSourceConfig> configOpt = scraperProperties.findConfigForUrl(url);

    ScrapeResponse scrapeResult;

    if (configOpt.isPresent()) {
        // --- CONFIGURED SELECTORS PATH ---
        BankSourceConfig config = configOpt.get();
        log.info("Bank source config found for URL: {} (bank={})", url, config.bank());

        // Step 1: Scrape with configured selectors
        scrapeResult = basicScraperService.scrapeUrlWithConfig(url, config);

        if (!"success".equals(scrapeResult.getStatus())) {
            return scrapeResult;
        }

        // Step 2: Check if configured extraction produced cards
        List<StructuredCard> cards = scrapeResult.getStructuredCards();

        if (cards == null || cards.isEmpty()) {
            // Configured selectors matched nothing -- fall through to generic flow
            log.warn("Configured selectors returned 0 cards for {} -- falling through to generic LLM flow",
                    url);
            // Fall through to generic path below
        } else {
            // Step 3: Convert StructuredCards to ExtractedProducts (no LLM needed for discovery)
            List<ExtractedProduct> products = cards.stream()
                .map(card -> ExtractedProduct.builder()
                    .productName(card.name())
                    .sourceUrl(card.href())
                    .description(card.description())
                    .keyBenefits(card.benefits())
                    .islamicProduct(true)  // All products in scope are Islamic banking products
                    .build())
                .collect(Collectors.toList());

            // Step 4: Deduplicate
            List<ExtractedProduct> deduplicated = deduplicateByName(products);

            // Step 5: Save to staging (sourceUrl already set from card.href, no matchProductUrls needed)
            log.info("Saving {} products from configured extraction to staging...", deduplicated.size());
            int savedCount = stagingProductService.saveExtractedProducts(
                    deduplicated, url, scrapeResult.getTextContent());

            scrapeResult.setMessage(String.format(
                "Configured selectors: extracted %d products, saved %d to staging (bank=%s)",
                deduplicated.size(), savedCount, config.bank()));

            log.info("Complete! Configured extraction: {} products, {} saved (bank={})",
                    deduplicated.size(), savedCount, config.bank());
            return scrapeResult;
        }
    }

    // --- GENERIC FLOW (existing code, unchanged) ---
    // This block runs when:
    //   (a) no config matched the URL, OR
    //   (b) config matched but configured selectors returned 0 cards (fallback)

    scrapeResult = basicScraperService.scrapeUrl(url);

    if (!"success".equals(scrapeResult.getStatus())) {
        return scrapeResult;
    }

    // ... existing Steps 2-4 from current scrapeAndAnalyze() are UNCHANGED ...
    // (isProductPage, extractProducts, extractProductNameHints, deduplicateByName,
    //  matchProductUrls, saveExtractedProducts)
}
```

**Key design decisions in this branch**:

1. `matchProductUrls()` is NOT called in the configured path because `StructuredCard.href` already contains the correct detail-page URL extracted directly from the DOM anchor. Calling `matchProductUrls()` would be redundant and could produce incorrect results if the normalized-name heuristic picks a different anchor.

2. If the configured-selector JS returns zero cards (selector misconfiguration or page structure change), the method logs a warning and falls through to the generic flow. This ensures the scrape is not silently empty.

3. The generic flow call to `basicScraperService.scrapeUrl(url)` runs only when needed (no double-scrape).

### 3.3 `ScrapeResponse.java`

**File**: `product-scraper-service/src/main/java/com/smartguide/scraper/dto/ScrapeResponse.java`

#### New fields (additive only, backward-compatible)

```
/** Structured cards extracted using configured bank-source CSS selectors. Empty for generic flow. */
@Builder.Default
private List<StructuredCard> structuredCards = new ArrayList<>();

/** How products were extracted: "CONFIGURED_SELECTORS" or "GENERIC_ANCHORS". Null for legacy responses. */
private String extractionMethod;
```

These fields are additive. Existing consumers that do not read them are unaffected. The `@Builder.Default` on `structuredCards` ensures it is never null (same pattern as `anchorPairs`).

### 3.4 `ExtractedProduct.java` -- NO CHANGES

The existing `ExtractedProduct` class already has all the fields needed. The configured-selectors path populates `productName`, `sourceUrl`, `description`, `keyBenefits`, and `islamicProduct`. All other fields (`category`, `subCategory`, `islamicStructure`, `annualRate`, `annualFee`, `minIncome`, `minCreditScore`, `eligibilityCriteria`, `confidenceScore`, `extractionReasoning`, `pageContent`, `rawPageContent`) are left as null. They will be filled in by an admin via the staging review UI, or in a future phase by per-product LLM enrichment.

### 3.5 `AIProductExtractor.java` -- NO CHANGES

The `isProductPage()`, `extractProducts()`, and `discoverProductUrls()` methods are unchanged. They continue to serve the generic flow for unconfigured URLs. The `discoverProductUrls()` method is explicitly NOT removed per the project rules.

### 3.6 `StagingProductService.java` (scraper) -- NO CHANGES

The `saveExtractedProducts()` method already accepts a `List<ExtractedProduct>` with per-product `sourceUrl` set. It builds the staging request using `product.getSourceUrl()`. No changes needed -- the configured path produces `ExtractedProduct` objects with the same field contract.

The only behavioral difference is that configured-path products will have `description` and `keyBenefits` populated from the DOM rather than from the LLM, and fields like `category`, `annualRate`, `confidenceScore` will be null. The `buildStagingRequest()` method already uses `if (x != null)` guards for all optional fields (lines 106-135), so null fields are simply omitted from the POST body.

---

## 4. application.yml Changes

Add the following block to `product-scraper-service/src/main/resources/application.yml`. The `scraper` prefix is new and does not conflict with any existing configuration key.

```yaml
# Configurable CSS selectors for bank product listing pages.
# When a scrape URL matches a bank-source entry by prefix, the scraper uses
# DOM container-based extraction instead of generic anchor + LLM discovery.
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
```

**Field types** (all are `String`):

| YAML key | Java field | Required | Example |
|---|---|---|---|
| `bank` | `bank` | Yes | `DIB` |
| `url` | `url` | Yes | `https://dib.ae/personal/cards` |
| `card-container-selector` | `cardContainerSelector` | Yes | `div.card-list-item.show` |
| `name-selector` | `nameSelector` | Yes | `h3, h4` |
| `url-selector` | `urlSelector` | Yes | `a[href^='/personal/cards/']` |
| `description-selector` | `descriptionSelector` | No | `p` |
| `benefits-selector` | `benefitsSelector` | No | `ul li` |

Spring Boot relaxed binding converts `card-container-selector` to `cardContainerSelector` automatically.

---

## 5. JavaScript Extraction Expression

The following JavaScript template is used when a `BankSourceConfig` is found. It is built at runtime
by interpolating the five selector values from the config. The Java code constructs this string using
`String.formatted()` or string concatenation -- the selectors are NOT user input from an HTTP request
(they come from `application.yml`), so there is no script injection risk.

```javascript
() => {
  const containerSelector = '%s';     // config.cardContainerSelector()
  const nameSelector = '%s';          // config.nameSelector()
  const urlSelector = '%s';           // config.urlSelector()
  const descSelector = %s;            // config.descriptionSelector() -- null or quoted string
  const benefitsSelector = %s;        // config.benefitsSelector()   -- null or quoted string
  const origin = window.location.origin;

  const skipPatterns = ['apply-now', 'compare', '#', 'javascript:', '.pdf'];

  return JSON.stringify(
    Array.from(document.querySelectorAll(containerSelector))
      .map(container => {
        // Extract product name from heading element inside container
        const nameEl = container.querySelector(nameSelector);
        const name = nameEl ? nameEl.innerText.trim() : '';

        // Extract detail page URL from anchor element inside container
        const urlEl = container.querySelector(urlSelector);
        let href = '';
        if (urlEl) {
          const raw = urlEl.getAttribute('href') || '';
          // Resolve relative URLs to absolute
          href = raw.startsWith('http') ? raw : origin + raw;
        }

        // Extract description text if selector is configured
        let description = null;
        if (descSelector) {
          const descEl = container.querySelector(descSelector);
          description = descEl ? descEl.innerText.trim() : null;
        }

        // Extract benefits list if selector is configured
        let benefits = [];
        if (benefitsSelector) {
          benefits = Array.from(container.querySelectorAll(benefitsSelector))
            .map(li => li.innerText.trim())
            .filter(t => t.length > 0);
        }

        return { name, href, description, benefits };
      })
      // Filter: must have a non-empty name
      .filter(card => card.name.length > 0)
      // Filter: must have a non-empty href
      .filter(card => card.href.length > 0)
      // Filter: skip non-product links (apply-now, compare, #, javascript:, .pdf)
      .filter(card => !skipPatterns.some(pat => card.href.includes(pat)))
  );
}
```

**Java-side string construction** (in `BasicScraperService`):

The method builds the JS string by interpolating config values. For nullable selectors
(`descriptionSelector`, `benefitsSelector`), the Java code emits the JavaScript literal `null`
(unquoted) when the config value is null, or a single-quoted string when it has a value.

```
private String buildConfiguredExtractionJs(BankSourceConfig config) {
    String descJs = config.descriptionSelector() == null
        ? "null"
        : "'" + config.descriptionSelector() + "'";
    String benefitsJs = config.benefitsSelector() == null
        ? "null"
        : "'" + config.benefitsSelector() + "'";

    return CONFIGURED_EXTRACTION_JS_TEMPLATE.formatted(
        config.cardContainerSelector(),
        config.nameSelector(),
        config.urlSelector(),
        descJs,
        benefitsJs
    );
}
```

Where `CONFIGURED_EXTRACTION_JS_TEMPLATE` is a `private static final String` text block containing
the JavaScript from above, with `%s` placeholders for the five selector positions.

---

## 6. Extraction Flow (with configured selectors)

Step-by-step algorithm when a URL matches a configured bank source:

```
EnhancedScraperService.scrapeAndAnalyze(url)
  |
  +-> ScraperProperties.findConfigForUrl(url)
  |     Returns Optional<BankSourceConfig> by prefix match against scraper.bank-sources[].url
  |
  +-> [CONFIG FOUND]
  |     |
  |     +-> BasicScraperService.scrapeUrlWithConfig(url, config)
  |     |     [Playwright: Firefox headless, navigate with DOMCONTENTLOADED + 90s timeout]
  |     |     [waitForTimeout(5000) -- allow React/JS to render dynamic content]
  |     |     [evaluate #1: document.body.innerText -> textContent (unchanged)]
  |     |     [evaluate #2: CONFIGURED_EXTRACTION_JS_TEMPLATE with config selectors -> JSON string]
  |     |       -> parseStructuredCards(json) -> List<StructuredCard>
  |     |     [evaluate #3: ANCHOR_EXTRACTION_JS -> anchorPairs (unchanged, for backward compat)]
  |     |     -> ScrapeResponse { textContent, anchorPairs, structuredCards, extractionMethod="CONFIGURED_SELECTORS" }
  |     |
  |     +-> IF structuredCards is empty:
  |     |     log.warn("Configured selectors returned 0 cards -- falling through to generic flow")
  |     |     -> GOTO [NO CONFIG] path below
  |     |
  |     +-> Convert List<StructuredCard> to List<ExtractedProduct>:
  |     |     card.name()        -> product.productName
  |     |     card.href()        -> product.sourceUrl       (already absolute, no matchProductUrls needed)
  |     |     card.description() -> product.description
  |     |     card.benefits()    -> product.keyBenefits
  |     |     true               -> product.islamicProduct
  |     |     null               -> product.category        (admin fills in via staging UI)
  |     |     null               -> product.annualRate, .annualFee, .minIncome, etc.
  |     |
  |     +-> deduplicateByName(products)  -- same method, handles duplicate DOM cards
  |     |
  |     +-> stagingProductService.saveExtractedProducts(deduplicated, url, textContent)
  |     |     [sourceUrl is per-product detail URL from card.href -- no matchProductUrls]
  |     |     [listingPageRawContent = textContent]
  |     |     [rawContentSource = "LISTING_PAGE"]
  |     |
  |     +-> return ScrapeResponse with message including count and bank name
  |
  +-> [NO CONFIG] -- existing generic flow, completely unchanged
        |
        +-> BasicScraperService.scrapeUrl(url)
        +-> AIProductExtractor.isProductPage(textContent)
        +-> AIProductExtractor.extractProducts(textContent, url)
        +-> extractProductNameHints(anchorPairs, url)
        +-> deduplicateByName(products)
        +-> matchProductUrls(deduplicated, anchorPairs, url)
        +-> stagingProductService.saveExtractedProducts(deduplicated, url, textContent)
        +-> return ScrapeResponse
```

---

## 7. StructuredCard -- Intermediate Data Carrier

As specified in Section 2.3, `StructuredCard` is a new Java 17 record in `com.smartguide.scraper.dto`.

```
record StructuredCard(
    String name,              // Product name from nameSelector element (h3/h4 innerText)
    String href,              // Absolute product detail URL from urlSelector anchor
    String description,       // Text from descriptionSelector element (may be null)
    List<String> benefits     // List of strings from benefitsSelector elements (may be empty)
)
```

**Why not reuse or extend `AnchorPair`**:

`AnchorPair` is a two-field record (`text`, `href`) used as the value type in
`ScrapeResponse.anchorPairs` and consumed by `EnhancedScraperService.matchProductUrls()` and
`EnhancedScraperService.extractProductNameHints()`. Adding `description` and `benefits` fields to
`AnchorPair` would:

1. Change the JSON serialization format of `ScrapeResponse.anchorPairs`, which could break consumers
   that parse the `/api/scraper/scrape-url` response.
2. Force `matchProductUrls()` and `extractProductNameHints()` to handle fields they do not use.
3. Create semantic confusion: an `AnchorPair` represents a rendered `<a>` tag; a `StructuredCard`
   represents a product card container with multiple sub-elements.

A separate record is the correct design.

---

## 8. LLM Role After This Change

**Architectural decision**: When a `BankSourceConfig` exists for the scraped URL, the LLM is NOT
called at all -- neither for discovery nor for enrichment.

**Justification**:

1. **Discovery is replaced by DOM selectors**. The configured CSS selectors extract product name,
   detail URL, description, and benefits directly from the page structure. This is deterministic,
   faster, and produces complete results regardless of page size.

2. **Enrichment fields are left null for admin review**. Fields not present in the DOM (category,
   sub-category, Islamic finance structure, profit rates, annual fees, minimum income, minimum
   credit score, eligibility criteria, confidence score) are set to null on the `ExtractedProduct`.
   The admin fills these in via the staging review UI (`/staging-review` page). This is the same
   workflow the admin already follows today -- they review and correct LLM-suggested values before
   approval.

3. **Per-product LLM enrichment is deferred to a future phase**. The requirements document
   (Section "Phase 2: LLM Auto-Discovery of Selectors") explicitly scopes LLM usage changes to
   future work. Adding a per-product enrichment LLM call in this feature would increase complexity,
   latency, and Ollama token consumption without a clear product mandate.

**Summary table**:

| LLM Call | Configured URL | Unconfigured URL |
|---|---|---|
| `isProductPage()` | SKIPPED (config presence implies product page) | Called (unchanged) |
| `extractProducts()` | SKIPPED (DOM selectors replace discovery) | Called (unchanged) |
| `discoverProductUrls()` | NOT called (method preserved, not removed) | NOT called (not in main flow) |
| Per-product enrichment | NOT called (fields left null for admin) | NOT called (not implemented yet) |

---

## 9. Regression Impact Matrix

| Component | Risk Level | What to Verify |
|---|---|---|
| Generic anchor flow (no config match) | LOW | Scrape a URL that does NOT match any `scraper.bank-sources` entry. Verify `BasicScraperService.scrapeUrl()` is called (not `scrapeUrlWithConfig`), `isProductPage()` and `extractProducts()` are called, and products are saved with the same fields as before. |
| `ANCHOR_EXTRACTION_JS` | NONE | The constant and `parseAnchorPairs()` are not modified. The generic path calls `scrapeUrl()` which uses them unchanged. The configured path also runs `ANCHOR_EXTRACTION_JS` as step 9 (Section 3.1) to populate `anchorPairs`. |
| `deduplicateByName()` | LOW | Called in both paths. Verify it handles `ExtractedProduct` objects with null `category` (configured path) without NPE. Current implementation normalizes `productName` only -- no risk. |
| `matchProductUrls()` | NONE | Called only in the generic path. Not called in the configured path (detail URLs come from DOM). The method itself is unchanged. |
| `StagingProductService.saveExtractedProducts()` | LOW | Called in both paths with the same signature. Verify that products with null `category`, null `confidenceScore`, null `annualRate` etc. are accepted by the main app's `POST /api/admin/staging` endpoint. The `buildStagingRequest()` already guards all optional fields with `if (x != null)` -- null fields are simply omitted. Verify the main app's `AdminStagingController` / `StagingProductService` does not reject a staging product with null `category`. |
| `ScrapeResponse` backward compatibility | LOW | Two new fields (`structuredCards`, `extractionMethod`) are additive. `structuredCards` defaults to empty list via `@Builder.Default`. `extractionMethod` defaults to null. Existing consumers that do not read these fields are unaffected. The `/api/scraper/scrape-url` (MVP1) endpoint returns `ScrapeResponse` from `scrapeUrl()` which does not set these fields -- they serialize as `[]` and `null` respectively. |
| `AIProductExtractor` | NONE | No methods modified. `discoverProductUrls()` preserved. `extractProducts()` and `isProductPage()` still called for unconfigured URLs. |
| `ScraperController` endpoints | NONE | The `/api/scraper/scrape-url-enhanced` endpoint calls `enhancedScraperService.scrapeAndAnalyze(url)` -- the same method signature. The controller is unchanged. The `/api/scraper/scrape-url` endpoint calls `basicScraperService.scrapeUrl(url)` -- also unchanged. |

---

## 10. Test Specification

### 10.1 `ScraperPropertiesTest`

- **File**: `product-scraper-service/src/test/java/com/smartguide/scraper/config/ScraperPropertiesTest.java`
- **Mocks needed**: None (pure unit test)

| Test Method | What It Asserts |
|---|---|
| `findConfigForUrl_exactMatch_returnsConfig` | Given `bankSources` contains an entry with `url=https://dib.ae/personal/cards`, calling `findConfigForUrl("https://dib.ae/personal/cards")` returns that entry. |
| `findConfigForUrl_prefixMatch_returnsConfig` | Given `bankSources` contains `url=https://dib.ae/personal/cards`, calling `findConfigForUrl("https://dib.ae/personal/cards?foo=bar")` returns that entry (prefix match). |
| `findConfigForUrl_noMatch_returnsEmpty` | Given `bankSources` contains `url=https://dib.ae/personal/cards`, calling `findConfigForUrl("https://adib.ae/cards")` returns `Optional.empty()`. |
| `findConfigForUrl_multipleMatches_returnsFirst` | Given `bankSources` contains two entries with overlapping URL prefixes, calling with a URL that matches both returns the first entry. |
| `findConfigForUrl_emptyList_returnsEmpty` | Given `bankSources` is empty, any URL returns `Optional.empty()`. |
| `findConfigForUrl_nullUrl_returnsEmpty` | Calling `findConfigForUrl(null)` returns `Optional.empty()` without throwing. |

### 10.2 `BasicScraperServiceConfiguredTest`

- **File**: `product-scraper-service/src/test/java/com/smartguide/scraper/service/BasicScraperServiceConfiguredTest.java`
- **Mocks needed**: None for JS template tests (pure string validation). Playwright is NOT mocked in unit tests.

| Test Method | What It Asserts |
|---|---|
| `buildConfiguredExtractionJs_allSelectorsPresent_containsAllSelectors` | Given a `BankSourceConfig` with all five selectors set, the generated JS string contains each selector value. |
| `buildConfiguredExtractionJs_nullDescriptionSelector_emitsJsNull` | Given a config with `descriptionSelector=null`, the JS contains `const descSelector = null;` (not a quoted string). |
| `buildConfiguredExtractionJs_nullBenefitsSelector_emitsJsNull` | Same for `benefitsSelector=null`. |
| `parseStructuredCards_validJson_returnsList` | Given a valid JSON array of `{name, href, description, benefits}` objects, returns the correct `List<StructuredCard>`. |
| `parseStructuredCards_duplicateHrefs_deduplicates` | Given JSON with two cards sharing the same `href`, returns only the first. |
| `parseStructuredCards_emptyArray_returnsEmptyList` | Given `[]`, returns empty list. |
| `parseStructuredCards_malformedJson_throwsException` | Given invalid JSON, throws an exception (caller catches it). |

**Note**: `buildConfiguredExtractionJs` is a private method. Test it indirectly by making it package-private, or test via the `scrapeUrlWithConfig` method in integration tests. For unit tests, the developer may choose to extract the JS-building logic to a package-private static helper method for direct testing.

### 10.3 `EnhancedScraperServiceConfiguredTest`

- **File**: `product-scraper-service/src/test/java/com/smartguide/scraper/service/EnhancedScraperServiceConfiguredTest.java`
- **Mocks needed**: `BasicScraperService`, `AIProductExtractor`, `StagingProductService`, `ScraperProperties`

| Test Method | What It Asserts |
|---|---|
| `scrapeAndAnalyze_configFound_usesConfiguredPath` | Given `ScraperProperties.findConfigForUrl()` returns a config, verify `basicScraperService.scrapeUrlWithConfig()` is called (not `scrapeUrl()`), `aiProductExtractor.isProductPage()` is NOT called, and `stagingProductService.saveExtractedProducts()` is called with the correct product count. |
| `scrapeAndAnalyze_configFound_productsHaveDetailUrls` | Given config returns structured cards with specific hrefs, verify the `ExtractedProduct` list passed to `saveExtractedProducts` has `sourceUrl` matching those hrefs (not the listing URL). |
| `scrapeAndAnalyze_configFound_emptyCards_fallsToGenericFlow` | Given config returns a `ScrapeResponse` with empty `structuredCards`, verify `basicScraperService.scrapeUrl()` IS called afterward, and `aiProductExtractor.isProductPage()` IS called. |
| `scrapeAndAnalyze_noConfig_usesGenericFlow` | Given `ScraperProperties.findConfigForUrl()` returns `Optional.empty()`, verify `basicScraperService.scrapeUrl()` is called, `aiProductExtractor.isProductPage()` is called, and the flow is identical to the current behavior. |
| `scrapeAndAnalyze_configFound_deduplicatesCards` | Given config returns structured cards with two cards having the same name (different case), verify `saveExtractedProducts` receives a deduplicated list. |
| `scrapeAndAnalyze_configFound_nullCategory_accepted` | Given config returns cards, verify the `ExtractedProduct` objects have `category=null` and `saveExtractedProducts` does not throw. |
| `scrapeAndAnalyze_configFound_extractionMethodSet` | Given config returns cards, verify `ScrapeResponse.extractionMethod` equals `"CONFIGURED_SELECTORS"`. |

**Fixture data**: Create `ScrapeResponse` objects using the builder with pre-populated `structuredCards` lists. Mock `basicScraperService.scrapeUrlWithConfig()` to return these fixtures.

### 10.4 Updates to Existing Test Classes

#### `EnhancedScraperServiceTest.java` (existing)

Add the `ScraperProperties` mock to the existing test class:

```
@Mock
private ScraperProperties scraperProperties;
```

Update `@InjectMocks` to pick up the new dependency. All existing tests continue to pass because
they test `deduplicateByName()` and `matchProductUrls()` directly -- methods that are unchanged.

#### `BasicScraperServiceTest.java` (existing)

Add the `ScraperProperties` field to match the updated constructor. Existing tests are pure
`ScrapeResponse` builder tests and do not invoke `BasicScraperService` methods directly, so they
continue to pass.

### 10.5 `ScrapeResponseTest`

- **File**: `product-scraper-service/src/test/java/com/smartguide/scraper/dto/ScrapeResponseTest.java`
- **Mocks needed**: None

| Test Method | What It Asserts |
|---|---|
| `builder_defaultStructuredCards_isEmptyList` | `ScrapeResponse.builder().build().getStructuredCards()` returns an empty list, not null. |
| `builder_defaultExtractionMethod_isNull` | `ScrapeResponse.builder().build().getExtractionMethod()` returns null. |
| `builder_withStructuredCards_carriesCards` | Setting `structuredCards` in the builder produces a response with those cards. |

### 10.6 Integration Test (optional, higher effort)

- **File**: `product-scraper-service/src/test/java/com/smartguide/scraper/integration/ConfiguredScraperIntegrationTest.java`
- **Approach**: Use a local test HTTP server (`com.sun.net.httpserver.HttpServer` or Spring's `MockWebServer`) that serves a static HTML page mimicking a bank listing page with known `div.card-list-item.show` containers. Configure `scraper.bank-sources` in a `@TestPropertySource` to point at the local server. Run the full `EnhancedScraperService.scrapeAndAnalyze()` flow (requires Playwright installed).
- **What to verify**: The correct number of `ExtractedProduct` objects are passed to `StagingProductService`, each with the expected `productName`, `sourceUrl`, `description`, and `keyBenefits`. The LLM is not called (mock `AIProductExtractor` to throw if called).
- **Note**: This test requires Playwright browser binaries and is slow. Mark with `@Tag("integration")` and exclude from the default `mvn test` profile.

---

## 11. Fallback Behaviour

This section consolidates all fallback paths in one place. The invariant is: **the scraper must never return an empty result solely because of a misconfigured or absent bank-source entry.** The generic LLM flow is always the safety net.

### Fallback Case 1 — No config entry matches the submitted URL

**Trigger**: `ScraperProperties.bankSources` is empty, or none of the configured `url` values match the submitted URL (prefix check).

**Behaviour**:
- `EnhancedScraperService.scrapeAndAnalyze()` detects `Optional.empty()` from `findConfigForUrl()`.
- Calls `basicScraperService.scrapeUrl(url)` — the existing generic Playwright scrape.
- Calls `aiProductExtractor.isProductPage()` then `aiProductExtractor.extractProducts()` as before.
- Deduplication, URL matching, and staging save proceed exactly as they did before this feature was built.
- **No log warning is emitted** — absence of config is normal for unconfigured banks.

### Fallback Case 2 — Config entry found but configured selectors return 0 cards

**Trigger**: A `bank-source` entry matches the URL, `scrapeUrlWithConfig()` runs, but the JS expression `document.querySelectorAll(cardContainerSelector)` finds no elements (selector is wrong, page structure changed, or 5s JS wait was insufficient).

**Behaviour**:
- `scrapeUrlWithConfig()` sets `ScrapeResponse.structuredCards` to an empty list and `extractionMethod` to `"GENERIC_ANCHORS"`.
- `EnhancedScraperService` detects empty `structuredCards`.
- Logs: `WARN "Configured selectors for bank '{}' returned 0 cards on '{}' — falling through to generic flow"`.
- Calls `basicScraperService.scrapeUrl(url)` to perform a second Playwright scrape using the generic anchor approach.
- Generic LLM flow proceeds normally.
- **This is the critical safety net** — a broken CSS selector after a bank redesign does not produce a silent zero-product result.

### Fallback Case 3 — `scrapeUrlWithConfig()` throws an exception

**Trigger**: Playwright throws during navigation or JS evaluation inside `scrapeUrlWithConfig()` (e.g. timeout, browser crash, JS syntax error in the selector template).

**Behaviour**:
- The exception is caught inside `scrapeUrlWithConfig()` before it propagates.
- Logs: `WARN "Configured scrape failed for bank '{}' on '{}': {} — falling through to generic flow"`.
- Returns a `ScrapeResponse` with `structuredCards = []` and `extractionMethod = "GENERIC_ANCHORS"`.
- `EnhancedScraperService` treats this identically to Case 2 and falls through to the generic flow.
- The scrape run does not fail.

### Fallback Case 4 — `scraper.bank-sources` absent from application.yml entirely

**Trigger**: The `scraper:` key or `bank-sources:` key is missing from `application.yml` (e.g. fresh deployment without the new config block).

**Behaviour**:
- `ScraperProperties.bankSources` is initialised to an empty `ArrayList` by default.
- Identical to Case 1 for every URL.
- Application starts cleanly, no error, no warning.
- All existing scraper functionality operates exactly as before the feature was introduced.

### Fallback Decision Summary

| Condition | Config Used? | LLM Called? | Result |
|---|---|---|---|
| No config for URL | No | Yes | Generic flow — same as before this feature |
| Config found, 0 cards returned | No (fallback) | Yes | Generic flow — second Playwright scrape triggered |
| Config found, exception thrown | No (fallback) | Yes | Generic flow — second Playwright scrape triggered |
| Config absent from YAML entirely | No | Yes | Generic flow — same as before this feature |
| Config found, cards extracted | Yes | No | Configured flow — full coverage, no LLM |

---

## 12. Configuration Validation (Startup)

### `scraper.bank-sources` is empty or absent

- `ScraperProperties.bankSources` defaults to an empty `ArrayList`.
- `findConfigForUrl()` always returns `Optional.empty()`.
- `EnhancedScraperService.scrapeAndAnalyze()` takes the generic flow for every URL.
- No error, no warning. This is the default behavior for a fresh deployment.

### A bank source has a blank `card-container-selector`

- The `@NotBlank` constraint on `BankSourceConfig.cardContainerSelector()` causes a
  `ConstraintViolationException` at application startup if `@Validated` is present on
  `ScraperProperties`.
- **Alternative (recommended)**: To avoid failing startup on a single misconfigured entry, implement
  a `@PostConstruct` validation method in `ScraperProperties` that iterates `bankSources`, logs a
  `WARN` for any entry with a blank required field, and removes that entry from the list. This is
  more forgiving than a hard startup failure.

```
@PostConstruct
void validateAndCleanBankSources() {
    Iterator<BankSourceConfig> it = bankSources.iterator();
    while (it.hasNext()) {
        BankSourceConfig config = it.next();
        if (isBlank(config.bank()) || isBlank(config.url()) || isBlank(config.cardContainerSelector())
                || isBlank(config.nameSelector()) || isBlank(config.urlSelector())) {
            log.warn("Skipping invalid bank-source config (missing required field): bank={}, url={}",
                    config.bank(), config.url());
            it.remove();
        }
    }
    log.info("Loaded {} valid bank-source configurations", bankSources.size());
}
```

- **Decision**: Use the `@PostConstruct` approach. Do NOT use `@Validated` with `@NotBlank` on the
  record -- it would prevent the application from starting if a single entry is misconfigured. The
  `@PostConstruct` method gives a clear log message and silently drops the bad entry.

### A URL matches multiple `bank-source` entries

- `findConfigForUrl()` returns the first match (list order = YAML order).
- A `WARN` log is emitted: `"Multiple bank-source configs match URL '{}' -- using first match: bank={}, url={}"`.
- No error thrown. This allows operators to have overlapping entries (e.g., a more specific entry
  listed before a broader prefix entry) as long as they are ordered correctly in the YAML.

---

## 13. Constraints and Rules

1. **Java 17**: Records are used for `BankSourceConfig` and `StructuredCard`. Text blocks are used
   for the JavaScript template.

2. **Spring Boot 3.2**: `@ConfigurationProperties` with relaxed binding handles YAML kebab-case to
   Java camelCase mapping. The `spring-boot-configuration-processor` dependency already exists in
   `pom.xml` (line 98-101) and will generate `META-INF/spring-configuration-metadata.json` for IDE
   autocompletion of the new `scraper.*` keys.

3. **No hardcoded selectors in Java code**: All CSS selectors live in `application.yml` under
   `scraper.bank-sources`. The JavaScript template in `BasicScraperService` uses `%s` placeholders
   filled from config values. The only hardcoded JavaScript is the template structure itself (query
   logic, skip filters, JSON serialization) -- not the selectors.

4. **Backward compatibility**: The existing `scrapeUrl(String url)` method signature in
   `BasicScraperService` is unchanged. The new `scrapeUrlWithConfig(String url, BankSourceConfig config)`
   is an additional method, not a replacement. All existing callers (`ScraperController.scrapeUrl`
   endpoint, generic flow in `EnhancedScraperService`) continue to call `scrapeUrl()`.

5. **Sharia compliance**: All new code, comments, logs, and YAML keys use compliant terminology.
   No forbidden terms (`interest`, `loan`, `mortgage`, `insurance`, `conventional`) appear in any
   new file. The word "finance" (compliant) appears in example URLs (`/personal/finance/`) which is
   correct. The `islamicProduct` field is set to `true` by default on configured-path products.

6. **`discoverProductUrls()` preserved**: The method in `AIProductExtractor` (line 73) is not
   removed, not modified, and not called by this feature. It remains available for future use.

7. **No database migration needed**: This feature is entirely within the scraper service's
   configuration and code. No new database columns, no Flyway migration. The `ScrapeResponse`
   changes are in-memory DTO changes only. The staging API request body format is unchanged (null
   optional fields are simply omitted).

8. **No changes to the main app**: All changes are in the `product-scraper-service` module. The
   main app's `AdminStagingController`, `StagingProductService`, `StagingProduct` entity, and
   `Product` entity are unchanged.

---

## Appendix A: File Change Summary

| File | Module | Change Type |
|---|---|---|
| `product-scraper-service/src/main/java/com/smartguide/scraper/config/BankSourceConfig.java` | Scraper | NEW |
| `product-scraper-service/src/main/java/com/smartguide/scraper/config/ScraperProperties.java` | Scraper | NEW |
| `product-scraper-service/src/main/java/com/smartguide/scraper/dto/StructuredCard.java` | Scraper | NEW |
| `product-scraper-service/src/main/java/com/smartguide/scraper/service/BasicScraperService.java` | Scraper | MODIFIED -- add `scrapeUrlWithConfig()` method, add `ScraperProperties` field |
| `product-scraper-service/src/main/java/com/smartguide/scraper/service/EnhancedScraperService.java` | Scraper | MODIFIED -- add config lookup branch in `scrapeAndAnalyze()`, add `ScraperProperties` field |
| `product-scraper-service/src/main/java/com/smartguide/scraper/dto/ScrapeResponse.java` | Scraper | MODIFIED -- add `structuredCards` and `extractionMethod` fields |
| `product-scraper-service/src/main/resources/application.yml` | Scraper | MODIFIED -- add `scraper.bank-sources` block |
| `product-scraper-service/src/test/java/com/smartguide/scraper/config/ScraperPropertiesTest.java` | Scraper | NEW |
| `product-scraper-service/src/test/java/com/smartguide/scraper/service/BasicScraperServiceConfiguredTest.java` | Scraper | NEW |
| `product-scraper-service/src/test/java/com/smartguide/scraper/service/EnhancedScraperServiceConfiguredTest.java` | Scraper | NEW |
| `product-scraper-service/src/test/java/com/smartguide/scraper/dto/ScrapeResponseTest.java` | Scraper | NEW |
| `product-scraper-service/src/test/java/com/smartguide/scraper/service/EnhancedScraperServiceTest.java` | Scraper | MODIFIED -- add `ScraperProperties` mock |
| `product-scraper-service/src/test/java/com/smartguide/scraper/service/BasicScraperServiceTest.java` | Scraper | MODIFIED -- add `ScraperProperties` field to match constructor |

## Appendix B: Selector Discovery Checklist for New Banks

When adding a new bank to `scraper.bank-sources`, the engineer should:

1. Open the bank's listing page in a browser with DevTools.
2. Identify the repeating container element that wraps each product card. Right-click a card and
   inspect. Note the CSS class(es) on the outermost container (e.g., `div.card-list-item.show`).
3. Inside one container, find the heading element with the product name (usually `h3` or `h4`).
4. Inside one container, find the anchor element linking to the product detail page. Note the `href`
   pattern (e.g., `a[href^='/personal/cards/']`).
5. Optionally, find the description paragraph and benefits list.
6. Add a new entry to `scraper.bank-sources` in `application.yml`.
7. Restart the scraper service and trigger a scrape via `POST /api/scraper/scrape-url-enhanced`.
8. Verify in the staging review UI that all products appear with correct names and detail URLs.
