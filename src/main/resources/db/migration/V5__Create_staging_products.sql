-- Create staging products table for review before production
CREATE TABLE IF NOT EXISTS staging_products (
    id BIGSERIAL PRIMARY KEY,

    -- Product data (same structure as products table)
    product_code VARCHAR(50) NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    category VARCHAR(100),
    sub_category VARCHAR(100),
    description TEXT,
    islamic_structure VARCHAR(50),
    annual_rate DECIMAL(5,2),
    annual_fee DECIMAL(10,2),
    min_income DECIMAL(12,2),
    min_credit_score INT,
    eligibility_criteria JSONB,
    key_benefits JSONB,
    sharia_certified BOOLEAN DEFAULT true,
    active BOOLEAN DEFAULT true,

    -- Scraping metadata
    source_website_id VARCHAR(100),
    source_url TEXT,
    scraped_at TIMESTAMP,
    data_quality_score DECIMAL(3,2),
    raw_html TEXT,

    -- Staging/approval metadata
    scrape_log_id BIGINT REFERENCES scrape_logs(id) ON DELETE CASCADE,
    approval_status VARCHAR(20) DEFAULT 'PENDING', -- PENDING, APPROVED, REJECTED
    reviewed_by VARCHAR(100),
    reviewed_at TIMESTAMP,
    review_notes TEXT,

    -- AI categorization results
    ai_suggested_category VARCHAR(100),
    ai_confidence DECIMAL(3,2),
    ai_categorization_json JSONB,

    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Create indexes for performance
CREATE INDEX IF NOT EXISTS idx_staging_products_scrape_log ON staging_products(scrape_log_id);
CREATE INDEX IF NOT EXISTS idx_staging_products_status ON staging_products(approval_status);
CREATE INDEX IF NOT EXISTS idx_staging_products_website ON staging_products(source_website_id);
CREATE INDEX IF NOT EXISTS idx_staging_products_category ON staging_products(category);

-- Add unique constraint on product_code within a scrape job
CREATE UNIQUE INDEX IF NOT EXISTS idx_staging_unique_product_per_job
    ON staging_products(scrape_log_id, product_code);

-- Comments for documentation
COMMENT ON TABLE staging_products IS 'Staging area for scraped products pending human review and approval';
COMMENT ON COLUMN staging_products.approval_status IS 'PENDING, APPROVED, REJECTED';
COMMENT ON COLUMN staging_products.ai_suggested_category IS 'LLM-suggested product category';
COMMENT ON COLUMN staging_products.ai_confidence IS 'Confidence score for AI categorization (0.0-1.0)';
COMMENT ON COLUMN staging_products.ai_categorization_json IS 'Full AI categorization response with reasoning';
