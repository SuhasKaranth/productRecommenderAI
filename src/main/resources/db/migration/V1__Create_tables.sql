-- Products table
CREATE TABLE IF NOT EXISTS products (
    id BIGSERIAL PRIMARY KEY,
    product_code VARCHAR(50) UNIQUE NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    category VARCHAR(100) NOT NULL,
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
    created_at TIMESTAMP DEFAULT NOW()
);

-- Intent to Category Mapping table
CREATE TABLE IF NOT EXISTS intent_category_mapping (
    id BIGSERIAL PRIMARY KEY,
    intent VARCHAR(100) UNIQUE NOT NULL,
    primary_category VARCHAR(100) NOT NULL,
    secondary_categories VARCHAR(100)[] DEFAULT ARRAY[]::VARCHAR[],
    confidence_threshold DECIMAL(3,2) DEFAULT 0.75,
    rank_order INT DEFAULT 1
);

-- Secondary categories table for JPA @ElementCollection
CREATE TABLE IF NOT EXISTS intent_secondary_categories (
    intent_mapping_id BIGINT NOT NULL,
    category VARCHAR(100),
    FOREIGN KEY (intent_mapping_id) REFERENCES intent_category_mapping(id) ON DELETE CASCADE
);

-- Create indexes for performance
CREATE INDEX IF NOT EXISTS idx_products_category ON products(category);
CREATE INDEX IF NOT EXISTS idx_products_active ON products(active);
CREATE INDEX IF NOT EXISTS idx_products_sharia ON products(sharia_certified);
CREATE INDEX IF NOT EXISTS idx_mapping_intent ON intent_category_mapping(intent);
