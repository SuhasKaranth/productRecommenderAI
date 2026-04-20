-- V13: Realign intent_category_mapping to match actual product category values.
--
-- Products scraped via the CSS selector path and enriched by CardEnrichmentService
-- use the taxonomy: COVERED_CARDS, DEBIT_CARDS, CHARGE_CARDS, HOME_FINANCE,
-- PERSONAL_FINANCE, AUTO_FINANCE, TAKAFUL, SAVINGS, CURRENT_ACCOUNTS, INVESTMENTS.
--
-- The existing intent_category_mapping rows used an older taxonomy (CREDIT_CARD,
-- FINANCING, CASA, INSURANCE) that no longer matches any product in the products table,
-- causing all category-filtered recommendation queries to return zero results.
--
-- This migration:
--   1. Updates PAYMENT   → CHARGE_CARDS primary (credit/charge/covered card queries)
--   2. Updates TRAVEL    → COVERED_CARDS primary (travel benefit cards at DIB are covered cards)
--   3. Updates GENERAL   → COVERED_CARDS primary, DEBIT_CARDS + CHARGE_CARDS secondary
--   4. Updates HOME/CAR/LOAN/EDUCATION/BUSINESS/SAVINGS/INVESTMENT/INSURANCE
--              to align with the enrichment taxonomy for future products
--   5. Adds    DEBIT_CARD intent (explicit debit card queries)

-- 1. PAYMENT: credit cards, charge cards, covered cards, cashback/rewards cards
UPDATE intent_category_mapping
SET    primary_category     = 'CHARGE_CARDS',
       secondary_categories = ARRAY['COVERED_CARDS', 'DEBIT_CARDS']
WHERE  intent = 'PAYMENT';

-- 2. TRAVEL: travel-benefit cards (covered cards at DIB carry lounge/travel perks)
UPDATE intent_category_mapping
SET    primary_category     = 'COVERED_CARDS',
       secondary_categories = ARRAY['CHARGE_CARDS', 'DEBIT_CARDS']
WHERE  intent = 'TRAVEL';

-- 3. GENERAL: broad fallback — show covered cards first, then debit and charge
UPDATE intent_category_mapping
SET    primary_category     = 'COVERED_CARDS',
       secondary_categories = ARRAY['DEBIT_CARDS', 'CHARGE_CARDS']
WHERE  intent = 'GENERAL';

-- 4. HOME: home finance products (none scraped yet; aligns with enrichment taxonomy)
UPDATE intent_category_mapping
SET    primary_category     = 'HOME_FINANCE',
       secondary_categories = ARRAY['SAVINGS']
WHERE  intent = 'HOME';

-- 5. CAR: auto finance products (none scraped yet)
UPDATE intent_category_mapping
SET    primary_category     = 'AUTO_FINANCE',
       secondary_categories = ARRAY['PERSONAL_FINANCE']
WHERE  intent = 'CAR';

-- 6. LOAN: personal finance (general financing requests)
UPDATE intent_category_mapping
SET    primary_category     = 'PERSONAL_FINANCE',
       secondary_categories = ARRAY['HOME_FINANCE', 'AUTO_FINANCE']
WHERE  intent = 'LOAN';

-- 7. SAVINGS: savings products
UPDATE intent_category_mapping
SET    primary_category     = 'SAVINGS',
       secondary_categories = ARRAY['CURRENT_ACCOUNTS']
WHERE  intent = 'SAVINGS';

-- 8. INVESTMENT: investment products
UPDATE intent_category_mapping
SET    primary_category     = 'INVESTMENTS',
       secondary_categories = ARRAY['SAVINGS']
WHERE  intent = 'INVESTMENT';

-- 9. INSURANCE: Takaful products
UPDATE intent_category_mapping
SET    primary_category     = 'TAKAFUL',
       secondary_categories = ARRAY[]::varchar[]
WHERE  intent = 'INSURANCE';

-- 10. EDUCATION: personal finance for education
UPDATE intent_category_mapping
SET    primary_category     = 'PERSONAL_FINANCE',
       secondary_categories = ARRAY['SAVINGS']
WHERE  intent = 'EDUCATION';

-- 11. BUSINESS: personal finance / current accounts (no dedicated business category yet)
UPDATE intent_category_mapping
SET    primary_category     = 'PERSONAL_FINANCE',
       secondary_categories = ARRAY['CURRENT_ACCOUNTS']
WHERE  intent = 'BUSINESS';

-- 12. Add DEBIT_CARD intent (matches the updated LLM system prompt)
INSERT INTO intent_category_mapping (intent, primary_category, secondary_categories, confidence_threshold)
VALUES (
    'DEBIT_CARD',
    'DEBIT_CARDS',
    ARRAY['CURRENT_ACCOUNTS'],
    0.7
)
ON CONFLICT (intent) DO UPDATE
    SET primary_category     = EXCLUDED.primary_category,
        secondary_categories = EXCLUDED.secondary_categories,
        confidence_threshold = EXCLUDED.confidence_threshold;
