-- Add scraping metadata to products table
ALTER TABLE products
ADD COLUMN IF NOT EXISTS source_website_id VARCHAR(100),
ADD COLUMN IF NOT EXISTS source_url TEXT,
ADD COLUMN IF NOT EXISTS scraped_at TIMESTAMP,
ADD COLUMN IF NOT EXISTS data_quality_score DECIMAL(3,2);

-- Create scrape sources table to track configured websites
CREATE TABLE IF NOT EXISTS scrape_sources (
    id BIGSERIAL PRIMARY KEY,
    website_id VARCHAR(100) UNIQUE NOT NULL,
    website_name VARCHAR(255) NOT NULL,
    base_url TEXT NOT NULL,
    config_path VARCHAR(500),
    active BOOLEAN DEFAULT true,
    last_scraped_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Create scrape logs table for tracking scrape jobs
CREATE TABLE IF NOT EXISTS scrape_logs (
    id BIGSERIAL PRIMARY KEY,
    source_id BIGINT REFERENCES scrape_sources(id) ON DELETE CASCADE,
    job_id VARCHAR(100) UNIQUE NOT NULL,
    status VARCHAR(50) NOT NULL, -- RUNNING, SUCCESS, FAILED, PARTIAL
    products_found INTEGER DEFAULT 0,
    products_saved INTEGER DEFAULT 0,
    products_updated INTEGER DEFAULT 0,
    products_skipped INTEGER DEFAULT 0,
    error_message TEXT,
    started_at TIMESTAMP DEFAULT NOW(),
    completed_at TIMESTAMP
);

-- Create indexes for performance
CREATE INDEX IF NOT EXISTS idx_products_source ON products(source_website_id);
CREATE INDEX IF NOT EXISTS idx_products_scraped_at ON products(scraped_at);
CREATE INDEX IF NOT EXISTS idx_scrape_sources_website_id ON scrape_sources(website_id);
CREATE INDEX IF NOT EXISTS idx_scrape_logs_job_id ON scrape_logs(job_id);
CREATE INDEX IF NOT EXISTS idx_scrape_logs_status ON scrape_logs(status);
CREATE INDEX IF NOT EXISTS idx_scrape_logs_source_id ON scrape_logs(source_id);

-- Add comments for documentation
COMMENT ON COLUMN products.source_website_id IS 'Identifier of the website this product was scraped from';
COMMENT ON COLUMN products.source_url IS 'Original URL where this product was found';
COMMENT ON COLUMN products.scraped_at IS 'Timestamp when this product was last scraped';
COMMENT ON COLUMN products.data_quality_score IS 'AI-generated quality score for scraped data (0.0-1.0)';

COMMENT ON TABLE scrape_sources IS 'Configuration for websites to scrape products from';
COMMENT ON TABLE scrape_logs IS 'Historical log of all scraping jobs executed';
