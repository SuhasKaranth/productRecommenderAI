-- Add new scrape sources
-- Copy this file and modify to add your own websites

INSERT INTO scrape_sources (website_id, website_name, base_url, config_path, active, created_at, updated_at)
VALUES
    -- Example: Adding HSBC Amanah
    ('hsbc_amanah', 'HSBC Amanah', 'https://www.hsbc.com.my/amanah/',
     'classpath:scraper-configs/hsbc-amanah.yml', true, NOW(), NOW()),

    -- Example: Adding Bank Islam
    ('bank_islam', 'Bank Islam Malaysia', 'https://www.bankislam.com',
     'classpath:scraper-configs/bank-islam.yml', true, NOW(), NOW())

ON CONFLICT (website_id) DO UPDATE SET
    website_name = EXCLUDED.website_name,
    base_url = EXCLUDED.base_url,
    config_path = EXCLUDED.config_path,
    updated_at = NOW();
