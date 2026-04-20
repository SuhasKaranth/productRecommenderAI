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
