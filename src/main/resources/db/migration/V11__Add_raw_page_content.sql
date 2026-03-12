-- V11: Add raw_page_content column to staging_products and products tables.
-- Stores verbatim visible text extracted from the scraped product page (not HTML).
-- Used as the primary grounding source for LLM-generated product summaries.

ALTER TABLE staging_products
    ADD COLUMN IF NOT EXISTS raw_page_content TEXT;

ALTER TABLE products
    ADD COLUMN IF NOT EXISTS raw_page_content TEXT;

COMMENT ON COLUMN staging_products.raw_page_content IS
    'Verbatim visible text from the scraped product page (not HTML), up to 15000 characters. '
    'Used as the primary grounding source when generating product summaries via LLM.';

COMMENT ON COLUMN products.raw_page_content IS
    'Verbatim visible text from the scraped product page (not HTML), up to 15000 characters. '
    'Used as the primary grounding source when generating product summaries via LLM.';
