-- Make product_code nullable in staging_products
-- Scraped products don't have codes until approved
ALTER TABLE staging_products ALTER COLUMN product_code DROP NOT NULL;

-- Also drop the unique constraint since product_code can now be null
DROP INDEX IF EXISTS idx_staging_unique_product_per_job;

-- Add a new unique constraint that only applies to non-null product codes
CREATE UNIQUE INDEX IF NOT EXISTS idx_staging_unique_product_per_job
    ON staging_products(scrape_log_id, product_code)
    WHERE product_code IS NOT NULL;

COMMENT ON COLUMN staging_products.product_code IS 'Product code - can be null for newly scraped products, assigned during approval';
