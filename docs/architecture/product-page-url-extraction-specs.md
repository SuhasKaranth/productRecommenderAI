# Product Page URL Extraction & Enrichment — Technical Specification

## Overview

The scraper currently scrapes a bank listing page (e.g. `https://dib.ae/personal/cards`) and stamps
the listing URL as `sourceUrl` on every product extracted from that page. Raw content carved by the LLM
averages ~320 characters per product because it shares 7743 characters across all products on the page.
This two-phase feature corrects both problems without breaking any existing API contracts or Flyway migrations.

---

## Current State

- `BasicScraperService.scrapeUrl()` opens a Playwright browser, waits 3 seconds for JS rendering, then
  calls `page.evaluate("() => document.body.innerText")` once. No anchor extraction is performed.
- `ScrapeResponse` carries only `url`, `title`, `textContent`, `textLength`, `status`, `message`. No
  link list field exists.
- `AIProductExtractor.extractProducts(pageContent, sourceUrl)` passes `sourceUrl` (the listing URL)
  directly to `mapToExtractedProduct()`, which hardcodes it as `ExtractedProduct.sourceUrl` for every
  product on the page.
- `AIProductExtractor.discoverProductUrls()` already exists and can resolve relative URLs to absolute,
  but it is not wired into the main `EnhancedScraperService.scrapeAndAnalyze()` flow.
- `StagingProduct.sourceUrl` and `Product.sourceUrl` therefore both receive the listing URL for all
  products from a given scrape run.
- `Product.rawPageContent` and `StagingProduct.rawPageContent` (added in V11) hold an LLM-carved excerpt
  of the listing page, not the product detail page.
- `ProductService.refreshProductContent()` re-scrapes `product.sourceUrl`. Because that URL is the
  listing page, it re-fetches listing content rather than the product's own detail page.
- `ProductEditDrawer.jsx` shows a 3-row enrichment checklist: Raw content, Summary, Keywords. There is
  no indicator of whether raw content came from a listing page or a product detail page.

---

## Phase 1 — DOM-Based Product Link Extraction During Scraping

### Goal

While the Playwright browser is still open after the JS wait, extract all rendered anchor tags from the
DOM. Pass the resulting link list to `AIProductExtractor` so the LLM can match each product name to its
individual detail page URL. Store that per-product URL as `sourceUrl` in staging. Store the listing page
raw content separately in a new `listing_page_raw_content` column so it is not lost.

### Data Flow

```
EnhancedScraperService.scrapeAndAnalyze(listingUrl)
  -> BasicScraperService.scrapeUrl(listingUrl)
       [Playwright: navigate, wait 3 s, innerText extraction UNCHANGED]
       [NEW: second page.evaluate() -> List<{text, href}> anchor pairs]
       -> ScrapeResponse { textContent, productLinks: List<String> }  [NEW field]
  -> AIProductExtractor.extractProducts(textContent, listingUrl, productLinks)  [NEW param]
       [buildExtractionPrompt() receives filtered link list appended as a LINKS section]
       [JSON schema adds "product_url" field]
       [mapToExtractedProduct() uses raw["product_url"] for sourceUrl; falls back to listingUrl]
  -> stagingProductService.saveExtractedProducts(products, listingUrl)
       [buildStagingRequest() sends product.sourceUrl (detail URL) NOT the listingUrl arg]
       [NEW: sends listingPageRawContent = textContent from ScrapeResponse]
```

### New Fields

| Location | Field | Type | Purpose |
|---|---|---|---|
| `ScrapeResponse` | `productLinks` | `List<String>` | Rendered absolute anchor hrefs from DOM, filtered |
| `ExtractedProduct` | (none new) | — | `sourceUrl` now holds detail URL; `pageContent` holds listing text as before |
| LLM JSON schema | `product_url` | string | Detail page URL matched by LLM per product |
| `staging_products` | `listing_page_raw_content` | TEXT | Verbatim listing page text (V12 migration) |
| `products` | `listing_page_raw_content` | TEXT | Copied from staging on approval (V12 migration) |
| `StagingProduct` entity | `listingPageRawContent` | String | JPA mapping for new column |
| `Product` entity | `listingPageRawContent` | String | JPA mapping for new column |
| `StagingProductDTO` | `listingPageRawContent` | String | Included in `fromEntity` and `toEntity` |

### Files Changed

| File | Service | Change |
|---|---|---|
| `BasicScraperService.java` | Scraper | Add second `page.evaluate()` call while browser is open; collect anchor `{text, href}` pairs; filter to same-domain non-nav hrefs; resolve relative URLs to absolute; populate `ScrapeResponse.productLinks` |
| `ScrapeResponse.java` | Scraper | Add `List<String> productLinks` field (Lombok `@Builder` — backward-compatible) |
| `AIProductExtractor.java` | Scraper | (1) Add overload `extractProducts(pageContent, sourceUrl, productLinks)` that appends a `LINKS` section to the prompt. (2) Add `"product_url"` to the JSON output schema in `buildExtractionPrompt`. (3) In `mapToExtractedProduct`, read `raw["product_url"]` and use it as `sourceUrl` when non-null and non-blank; fall back to the `sourceUrl` parameter (listing URL) when absent. Existing `extractProducts(pageContent, sourceUrl)` delegates to the new overload with an empty list to preserve backward compatibility. |
| `EnhancedScraperService.java` | Scraper | Pass `scrapeResult.getProductLinks()` to `aiProductExtractor.extractProducts()`. Pass `scrapeResult.getTextContent()` as `listingPageRawContent` to `stagingProductService.saveExtractedProducts()`. |
| `StagingProductService.java` (scraper) | Scraper | Add `listingPageRawContent` parameter to `saveExtractedProducts` and `buildStagingRequest`; include it in the POST body to the main service staging API. |
| `StagingProduct.java` | Main app | Add `@Column(name = "listing_page_raw_content", columnDefinition = "TEXT") String listingPageRawContent` |
| `Product.java` | Main app | Add `@Column(name = "listing_page_raw_content", columnDefinition = "TEXT") String listingPageRawContent` |
| `StagingProductDTO.java` | Main app | Add `String listingPageRawContent`; update `fromEntity` and `toEntity` |
| `StagingProductService.java` (main) | Main app | In `copyToProduct()`, add `product.setListingPageRawContent(staging.getListingPageRawContent())` |
| `V12__Add_product_url_fields.sql` | DB | New migration — see SQL section below |

### DOM Link Extraction Expression

The following JavaScript expression is passed to `page.evaluate()` while the browser is open. It
returns a JSON array string that the Java side parses into `List<String>`:

```javascript
() => JSON.stringify(
  Array.from(document.querySelectorAll('a[href]'))
    .map(a => ({ text: a.innerText.trim(), href: a.href }))
    .filter(a => a.href.startsWith(window.location.origin))
    .filter(a => !/\/(login|contact|about|careers|sitemap|social|facebook|twitter|instagram|linkedin|youtube)/i.test(a.href))
    .map(a => a.href)
)
```

The Java side: parse the JSON string into `List<String>`, deduplicate, and cap at 100 entries to avoid
oversized prompts. Pass the deduplicated list into `buildExtractionPrompt`.

### Prompt Addition (Links Section)

Append the following block inside `buildExtractionPrompt` when `productLinks` is non-empty, placed
after the `WEBPAGE CONTENT` block and before `INSTRUCTIONS`:

```
AVAILABLE PRODUCT LINKS (rendered from page DOM — use these to assign product_url):
<one URL per line, up to 100 entries>

For each product, find the link whose URL path or anchor text most closely matches the product name,
and assign it as "product_url". If no suitable link exists, use null.
```

Also add `"product_url": "full absolute URL or null"` to the JSON output schema between `category`
and `sub_category`.

### Constraints

- The existing `discoverProductUrls()` method in `AIProductExtractor` must not be removed. It remains
  available for future use. The approach above does not call it; it integrates link hints directly into
  the existing full-extraction prompt instead.
- If `page.evaluate()` for link extraction throws (e.g. browser crashed), catch the exception, log a
  warning, and proceed with an empty `productLinks` list. The main scrape must not fail.
- The listing URL passed as the `sourceUrl` argument to `saveExtractedProducts` is used only as a
  fallback when a product has no `product_url` from the LLM. It must still be stored as
  `listing_page_raw_content` to preserve provenance.
- No change to the `POST /api/admin/staging` request schema is needed; `listingPageRawContent` is an
  additive field.

---

## Phase 2 — Modal Enrichment: Source Indicator & Refresh Upgrade

### Goal

Track whether `rawPageContent` was sourced from the listing page (set at scrape time) or the product
detail page (set after "Refresh Data"). Surface this in the `ProductEditDrawer` enrichment checklist
as a fourth row so admins know at a glance whether richer per-product content is available.

### Data Flow

```
At scrape time (Phase 1 completes):
  staging_products.raw_content_source = 'LISTING_PAGE'
  products.raw_content_source = 'LISTING_PAGE'   (copied on approval)

Admin clicks "Refresh Data" in ProductEditDrawer:
  POST /api/products/{id}/refresh-content
    ProductService.refreshProductContent(id)
      -> ScraperServiceClient.scrapePageContent(product.sourceUrl)  [UNCHANGED — now hits detail URL]
      -> product.setRawPageContent(rawText)                         [UNCHANGED]
      -> product.setScrapedAt(now())                               [UNCHANGED]
      -> product.setRawContentSource("PRODUCT_PAGE")               [NEW]
      -> productRepository.save(product)
      -> return Map { scrapedAt, rawContentSource, message }       [rawContentSource added to response]

ProductDTO returned by GET /api/products/{id}:
  includes rawContentSource field (NEW)

ProductEditDrawer.jsx:
  reads product.rawContentSource from loaded product
  renders 4th enrichment row based on value
```

### New Fields

| Location | Field | Type | Values | Purpose |
|---|---|---|---|---|
| `staging_products` | `raw_content_source` | VARCHAR(20) | `LISTING_PAGE`, `PRODUCT_PAGE` | Tracks content origin (V12 migration) |
| `products` | `raw_content_source` | VARCHAR(20) | `LISTING_PAGE`, `PRODUCT_PAGE` | Tracks content origin (V12 migration) |
| `StagingProduct` entity | `rawContentSource` | String | same | JPA mapping |
| `Product` entity | `rawContentSource` | String | same | JPA mapping |
| `StagingProductDTO` | `rawContentSource` | String | same | API representation |
| `ProductDTO` | `rawContentSource` | String | same | API representation |

### Files Changed

| File | Service | Change |
|---|---|---|
| `Product.java` | Main app | Add `@Column(name = "raw_content_source", length = 20) String rawContentSource` |
| `StagingProduct.java` | Main app | Add `@Column(name = "raw_content_source", length = 20) String rawContentSource` |
| `StagingProductDTO.java` | Main app | Add `String rawContentSource`; update `fromEntity` and `toEntity` |
| `ProductDTO.java` | Main app | Add `String rawContentSource` |
| `StagingProductService.java` (main) | Main app | In `copyToProduct()`, add `product.setRawContentSource(staging.getRawContentSource())` |
| `ProductService.java` | Main app | In `refreshProductContent()`, after `product.setScrapedAt()`, add `product.setRawContentSource("PRODUCT_PAGE")`. Include `rawContentSource` in the returned map. |
| `ProductController.java` | Main app | In `convertToDTO()` (or wherever `ProductDTO` is built from `Product`), map `product.getRawContentSource()` to `dto.rawContentSource` |
| `StagingProductService.java` (scraper) | Scraper | In `buildStagingRequest()`, add `data.put("rawContentSource", "LISTING_PAGE")` |
| `frontend/src/components/ProductEditDrawer.jsx` | Frontend | Add `rawContentSource` state variable loaded from `res.data.rawContentSource`. Add 4th `EnrichmentRow` — see UI spec below. |
| `V12__Add_product_url_fields.sql` | DB | Same migration as Phase 1 (see SQL section) |

### Frontend UI Spec — 4th Enrichment Row

Add a new state variable `rawContentSource` initialized from `p.rawContentSource` alongside the
existing `scrapedAt` state variable. The row is inserted after the existing "Raw content" row:

| `rawContentSource` value | Icon | Label |
|---|---|---|
| `"PRODUCT_PAGE"` | `CheckCircleIcon` color="success" | "Source: Product page" |
| `"LISTING_PAGE"` | `WarningAmberIcon` color="warning" | "Source: Listing page — click Refresh for richer data" |
| null / undefined | `WarningAmberIcon` color="warning" | "Source: Not scraped yet" |

After a successful "Refresh Data" call, update `rawContentSource` state to `"PRODUCT_PAGE"` using the
value returned in `res.data.rawContentSource`. No additional API call is needed.

### Constraints

- The "Refresh Data" button `disabled` condition and `sourceUrl` check are unchanged.
- `POST /api/products/{id}/refresh-content` response currently returns `{ scrapedAt, message }`.
  Adding `rawContentSource` to this map is additive and does not break existing callers.
- `rawContentSource` is nullable in the database (no `NOT NULL` constraint) so that existing rows
  before this migration are valid without a backfill.

---

## DB Migration (V12)

File: `src/main/resources/db/migration/V12__Add_product_url_fields.sql`

```sql
-- V12: Add listing_page_raw_content and raw_content_source to both product tables.
-- listing_page_raw_content: preserves the listing page text after Phase 1 switches
--   raw_page_content to hold per-product detail page content.
-- raw_content_source: tracks whether raw_page_content came from a listing page
--   (set at scrape time) or a product detail page (set after Refresh Data).

ALTER TABLE staging_products
    ADD COLUMN IF NOT EXISTS listing_page_raw_content TEXT,
    ADD COLUMN IF NOT EXISTS raw_content_source VARCHAR(20);

ALTER TABLE products
    ADD COLUMN IF NOT EXISTS listing_page_raw_content TEXT,
    ADD COLUMN IF NOT EXISTS raw_content_source VARCHAR(20);

COMMENT ON COLUMN staging_products.listing_page_raw_content IS
    'Verbatim visible text of the listing page from which this product was extracted. '
    'Preserved separately once per-product detail pages are scraped.';

COMMENT ON COLUMN staging_products.raw_content_source IS
    'Indicates the source of raw_page_content: LISTING_PAGE (set at scrape time) '
    'or PRODUCT_PAGE (set after a Refresh Data operation on the detail page URL).';

COMMENT ON COLUMN products.listing_page_raw_content IS
    'Verbatim visible text of the listing page from which this product was extracted. '
    'Preserved separately once per-product detail pages are scraped.';

COMMENT ON COLUMN products.raw_content_source IS
    'Indicates the source of raw_page_content: LISTING_PAGE (set at scrape time) '
    'or PRODUCT_PAGE (set after a Refresh Data operation on the detail page URL).';
```

---

## Data Flow Diagram

```
SCRAPE TRIGGER (admin submits listing URL)
        |
        v
BasicScraperService.scrapeUrl(listingUrl)
  [Playwright: navigate -> waitForTimeout(3000)]
  [evaluate #1: document.body.innerText  -> textContent]
  [evaluate #2: anchor extraction JS     -> productLinks List<String>]
        |
        v
ScrapeResponse { url, textContent, productLinks }
        |
        v
AIProductExtractor.isProductPage(textContent)  [UNCHANGED]
        |
        v
AIProductExtractor.extractProducts(textContent, listingUrl, productLinks)
  [buildExtractionPrompt: appends LINKS section with productLinks]
  [LLM JSON schema includes "product_url"]
  [mapToExtractedProduct: sourceUrl = raw["product_url"] ?? listingUrl]
        |
        v
List<ExtractedProduct> { sourceUrl=detailUrl, rawPageContent=listing excerpt }
        |
        v
scraper StagingProductService.saveExtractedProducts(products, listingUrl)
  [buildStagingRequest: sourceUrl=product.sourceUrl (detail URL)]
  [                     listingPageRawContent=scrapeResult.textContent]
  [                     rawContentSource="LISTING_PAGE"]
  [POST /api/admin/staging  (X-API-Key auth, UNCHANGED)]
        |
        v
main app AdminStagingController -> StagingProductService.createStagingProduct()
  staging_products row:
    source_url            = detail URL (per product)
    raw_page_content      = LLM-carved excerpt from listing page
    listing_page_raw_content = full listing page text
    raw_content_source    = LISTING_PAGE
        |
        v (admin approves in StagingReview UI)
main app StagingProductService.approveStagingProduct() -> copyToProduct()
  products row: all fields copied including listing_page_raw_content, raw_content_source
        |
        v (admin opens ProductEditDrawer, clicks Refresh Data)
ProductEditDrawer -> POST /api/products/{id}/refresh-content
  ProductService.refreshProductContent(id)
    ScraperServiceClient.scrapePageContent(product.sourceUrl)  [NOW hits detail URL]
    product.rawPageContent      = detail page text
    product.scrapedAt           = now()
    product.rawContentSource    = PRODUCT_PAGE
        |
        v
ProductEditDrawer receives { scrapedAt, rawContentSource="PRODUCT_PAGE", message }
  -> updates rawContentSource state
  -> 4th enrichment row changes from amber WARNING to green CHECK
```

---

## Acceptance Criteria

1. After a scrape run, each product row in `staging_products` has a `source_url` that points to its
   individual detail page (e.g. `https://dib.ae/personal/cards/dib-cashback-card`), not the listing page.
2. After a scrape run, `staging_products.listing_page_raw_content` contains the full listing page text,
   not null.
3. After a scrape run, `staging_products.raw_content_source` equals `LISTING_PAGE`.
4. If the DOM link extraction `page.evaluate()` throws an exception, the scrape run still completes and
   products are extracted using the listing URL as `sourceUrl` fallback.
5. If the LLM returns no `product_url` for a product (null or missing), that product's `sourceUrl`
   falls back to the listing URL with no error thrown.
6. Approving a staging product copies `listing_page_raw_content` and `raw_content_source` to the
   corresponding `products` row.
7. `GET /api/products/{id}` returns `rawContentSource` in the response body.
8. After clicking "Refresh Data" in `ProductEditDrawer`, if the product had `sourceUrl` pointing to a
   detail page, `products.raw_content_source` is updated to `PRODUCT_PAGE` in the database.
9. The `ProductEditDrawer` enrichment checklist shows a 4th row; a green check appears when
   `rawContentSource === "PRODUCT_PAGE"` and an amber warning appears otherwise.
10. All existing API endpoints (`POST /api/v1/recommend`, `GET /api/products`, `GET /api/admin/staging`,
    `POST /api/admin/staging/{id}/approve`, etc.) continue to respond correctly without modification
    to their existing fields.
11. Flyway applies V12 cleanly on top of V11 with no checksum errors on V1–V11.
12. Products scraped before this feature is deployed (where `raw_content_source` is null) do not cause
    errors; the `ProductEditDrawer` treats null as the "Not scraped yet" amber state.
