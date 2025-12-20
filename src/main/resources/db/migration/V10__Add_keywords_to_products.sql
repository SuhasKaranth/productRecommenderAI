-- Add keywords column to products table
ALTER TABLE products ADD COLUMN IF NOT EXISTS keywords text[];

-- Add index for better keyword search performance
CREATE INDEX IF NOT EXISTS idx_products_keywords ON products USING GIN (keywords);
