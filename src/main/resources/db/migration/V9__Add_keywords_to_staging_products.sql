-- Add keywords field to staging_products table
-- Keywords will be used for search and categorization

ALTER TABLE staging_products
ADD COLUMN keywords TEXT[];

-- Create GIN index for efficient array search
CREATE INDEX idx_staging_products_keywords
ON staging_products USING gin(keywords);

-- Add comment
COMMENT ON COLUMN staging_products.keywords IS 'AI-generated or manually entered keywords for search optimization';
