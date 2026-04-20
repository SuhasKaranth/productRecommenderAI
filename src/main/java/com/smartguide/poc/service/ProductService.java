package com.smartguide.poc.service;

import com.smartguide.poc.dto.RankingResult;
import com.smartguide.poc.entity.Product;
import com.smartguide.poc.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for product retrieval and ranking
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final EntityManager entityManager;
    private final LLMService llmService;
    private final ScraperServiceClient scraperServiceClient;

    @org.springframework.beans.factory.annotation.Value("${app.ranking.llm.enabled:true}")
    private boolean llmRankingEnabled;

    @org.springframework.beans.factory.annotation.Value("${app.ranking.llm.max-products:10}")
    private int maxProductsForLLM;

    /**
     * Get product recommendations based on filters and intent
     */
    public RankingResult getRecommendations(
            Map<String, Object> filters,
            Map<String, Object> intentData,
            Map<String, Object> categories,
            String userInput) {

        List<Product> products = queryProducts(filters);

        if (products.isEmpty()) {
            log.warn("No keyword-ready products found with filters, getting fallback products");
            products = getFallbackProducts();
        }

        // Add categories and user input to intent data for ranking
        Map<String, Object> enrichedIntentData = new HashMap<>(intentData);
        enrichedIntentData.put("primary_category", categories.get("primary"));
        enrichedIntentData.put("secondary_categories", categories.get("secondary"));
        enrichedIntentData.put("user_input", userInput != null ? userInput.toLowerCase() : "");

        RankingResult ranked = rankProducts(products, enrichedIntentData, userInput);

        // Return top 5, preserving the chat summary
        List<Map<String, Object>> top5 = ranked.rankedProducts().stream().limit(5).collect(Collectors.toList());
        return new RankingResult(ranked.chatSummary(), top5);
    }

    /**
     * Query products from database with filters
     */
    @SuppressWarnings("unchecked")
    private List<Product> queryProducts(Map<String, Object> filters) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Product> query = cb.createQuery(Product.class);
        Root<Product> product = query.from(Product.class);

        List<Predicate> predicates = new ArrayList<>();

        // Category filter
        List<String> categories = (List<String>) filters.get("categories");
        if (categories != null && !categories.isEmpty()) {
            predicates.add(product.get("category").in(categories));
        }

        // Active filter
        if (filters.containsKey("active")) {
            predicates.add(cb.equal(product.get("active"), filters.get("active")));
        }

        // Keywords filter — only include products that have at least one keyword generated.
        // array_length(keywords, 1) returns NULL when the array is NULL or empty, so
        // the IS NOT NULL check on array_length covers both cases.
        predicates.add(
            cb.isNotNull(
                cb.function("array_length", Integer.class,
                    product.get("keywords"), cb.literal(1))
            )
        );

        // Sharia certified filter — treat NULL as true (all products are Sharia-compliant by default)
        if (filters.containsKey("sharia_certified")) {
            predicates.add(cb.or(
                    cb.equal(product.get("shariaCertified"), filters.get("sharia_certified")),
                    cb.isNull(product.get("shariaCertified"))
            ));
        }

        // User income filter
        if (filters.containsKey("user_income")) {
            BigDecimal userIncome = (BigDecimal) filters.get("user_income");
            predicates.add(cb.or(
                    cb.lessThanOrEqualTo(product.get("minIncome"), userIncome),
                    cb.isNull(product.get("minIncome"))
            ));
        }

        // Credit score filter
        if (filters.containsKey("user_credit_score")) {
            Integer creditScore = (Integer) filters.get("user_credit_score");
            predicates.add(cb.or(
                    cb.lessThanOrEqualTo(product.get("minCreditScore"), creditScore),
                    cb.isNull(product.get("minCreditScore"))
            ));
        }

        // Exclude products filter
        if (filters.containsKey("exclude_products")) {
            List<String> excludeProducts = (List<String>) filters.get("exclude_products");
            if (!excludeProducts.isEmpty()) {
                predicates.add(cb.not(product.get("productCode").in(excludeProducts)));
            }
        }

        query.where(predicates.toArray(new Predicate[0]));

        return entityManager.createQuery(query).getResultList();
    }

    /**
     * Get generic fallback products when no specific matches found.
     * Only returns products that have keywords generated (same constraint as the main query).
     */
    private List<Product> getFallbackProducts() {
        List<String> fallbackCategories = Arrays.asList(
                "COVERED_CARDS", "DEBIT_CARDS", "CHARGE_CARDS",
                "HOME_FINANCE", "PERSONAL_FINANCE", "AUTO_FINANCE",
                "TAKAFUL", "SAVINGS", "CURRENT_ACCOUNTS", "INVESTMENTS");

        return productRepository.findByCategoriesWithKeywords(fallbackCategories)
                .stream()
                .limit(10)
                .collect(Collectors.toList());
    }

    /**
     * Rank products using two-stage approach: formula-based pre-filtering + LLM re-ranking.
     * Returns a {@link RankingResult} that includes both the ranked products and an
     * LLM-generated chat summary (null when LLM is disabled or ranking fails).
     */
    private RankingResult rankProducts(List<Product> products, Map<String, Object> intentData, String userInput) {
        // Stage 1: Get top candidates using formula-based ranking
        log.info("Stage 1: Formula-based ranking of {} products", products.size());
        List<Map<String, Object>> formulaRanked = rankProductsWithFormula(products, intentData);

        List<Map<String, Object>> topCandidates = formulaRanked.stream()
                .limit(maxProductsForLLM)
                .collect(Collectors.toList());

        log.info("Formula ranking completed. Top {} candidates selected for LLM re-ranking", topCandidates.size());

        // Stage 2: LLM re-ranking of top candidates
        if (llmRankingEnabled && !topCandidates.isEmpty()) {
            try {
                log.info("Stage 2: LLM re-ranking enabled, processing {} products", topCandidates.size());
                List<Product> topProducts = topCandidates.stream()
                        .map(item -> (Product) item.get("product"))
                        .collect(Collectors.toList());

                RankingResult llmResult = llmService.rankProductsWithLLM(
                        topProducts, userInput, intentData);

                log.info("LLM re-ranking completed successfully");
                return llmResult;

            } catch (Exception e) {
                log.warn("LLM re-ranking failed, using formula-based results: {}", e.getMessage());
            }
        } else {
            log.info("LLM ranking disabled, using formula-based results");
        }

        // Fallback: formula-based results, no chat summary
        return new RankingResult(null, topCandidates);
    }

    /**
     * Rank products based on relevance to intent using formula-based scoring
     */
    private List<Map<String, Object>> rankProductsWithFormula(List<Product> products, Map<String, Object> intentData) {
        List<Map<String, Object>> scoredProducts = new ArrayList<>();

        for (Product product : products) {
            double score = calculateRelevanceScore(product, intentData);
            String reason = generateReason(product, intentData);

            Map<String, Object> scoredProduct = new HashMap<>();
            scoredProduct.put("product", product);
            scoredProduct.put("score", score);
            scoredProduct.put("reason", reason);

            scoredProducts.add(scoredProduct);
        }

        // Sort by score descending
        scoredProducts.sort((a, b) ->
                Double.compare((Double) b.get("score"), (Double) a.get("score"))
        );

        return scoredProducts;
    }

    /**
     * Calculate relevance score for a product
     * Ranking formula:
     * - Category match: 40%
     * - Specific keyword match: 30% (NEW - matches user's specific query keywords)
     * - Recency: 15%
     * - Popularity: 10%
     * - Benefit alignment: 5%
     */
    @SuppressWarnings("unchecked")
    private double calculateRelevanceScore(Product product, Map<String, Object> intentData) {
        double score = 0.0;

        String primaryCategory = (String) intentData.get("primary_category");
        List<String> secondaryCategories = (List<String>) intentData.get("secondary_categories");
        String userInput = (String) intentData.get("user_input");

        // 1. Category match (40%)
        if (product.getCategory().equals(primaryCategory)) {
            score += 0.40;
        } else if (secondaryCategories != null && secondaryCategories.contains(product.getCategory())) {
            score += 0.28;
        } else {
            score += 0.08;
        }

        // 2. Specific keyword match (30%) - Check if user's query keywords match product keywords/benefits
        if (userInput != null && !userInput.isEmpty()) {
            double keywordMatchScore = checkSpecificKeywordMatch(product, userInput);
            score += keywordMatchScore * 0.30;
        } else {
            score += 0.05;
        }

        // 3. Recency (15%)
        if (product.getCreatedAt() != null) {
            long daysOld = ChronoUnit.DAYS.between(product.getCreatedAt(), LocalDateTime.now());
            if (daysOld < 30) {
                score += 0.15;
            } else if (daysOld < 90) {
                score += 0.11;
            } else {
                score += 0.08;
            }
        } else {
            score += 0.08;
        }

        // 4. Popularity (10%) - Simplified for POC
        List<String> popularProducts = Arrays.asList("CC_TRAVEL_01", "CASA_SAV_01", "FIN_HOME_01");
        if (popularProducts.contains(product.getProductCode())) {
            score += 0.10;
        } else {
            score += 0.05;
        }

        // 5. Benefit alignment (5%)
        String intent = (String) intentData.get("intent");
        if (checkBenefitAlignment(product, intent)) {
            score += 0.05;
        } else {
            score += 0.02;
        }

        return Math.min(1.0, Math.max(0.0, score));
    }

    /**
     * Check if product keywords or benefits match specific user query keywords
     * Returns a score between 0.0 and 1.0 based on match quality
     */
    private double checkSpecificKeywordMatch(Product product, String userInput) {
        double matchScore = 0.0;
        String[] userKeywords = userInput.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", "")
                .split("\\s+");

        // Check against product keywords (if available)
        if (product.getKeywords() != null && !product.getKeywords().isEmpty()) {
            String productKeywordsText = String.join(" ", product.getKeywords()).toLowerCase();

            for (String userKeyword : userKeywords) {
                if (userKeyword.length() >= 3 && productKeywordsText.contains(userKeyword)) {
                    matchScore += 0.4; // Strong match in keywords
                }
            }
        }

        // Check against product benefits
        if (product.getKeyBenefits() != null && !product.getKeyBenefits().isEmpty()) {
            String benefitsText = String.join(" ", product.getKeyBenefits()).toLowerCase();

            for (String userKeyword : userKeywords) {
                if (userKeyword.length() >= 3 && benefitsText.contains(userKeyword)) {
                    matchScore += 0.3; // Good match in benefits
                }
            }
        }

        // Check against product name
        String productName = product.getProductName().toLowerCase();
        for (String userKeyword : userKeywords) {
            if (userKeyword.length() >= 3 && productName.contains(userKeyword)) {
                matchScore += 0.2; // Decent match in product name
            }
        }

        // Cap at 1.0
        return Math.min(1.0, matchScore);
    }

    /**
     * Check if product benefits align with intent
     */
    private boolean checkBenefitAlignment(Product product, String intent) {
        if (product.getKeyBenefits() == null || product.getKeyBenefits().isEmpty()) {
            return false;
        }

        String benefitsText = String.join(" ", product.getKeyBenefits()).toLowerCase();

        Map<String, List<String>> intentKeywords = Map.of(
                "TRAVEL", Arrays.asList("travel", "forex", "international", "airport", "lounge"),
                "LOAN", Arrays.asList("finance", "loan", "credit", "tenure", "payment"),
                "SAVINGS", Arrays.asList("profit", "return", "savings", "monthly"),
                "INVESTMENT", Arrays.asList("fund", "portfolio", "dividend", "growth"),
                "CAR", Arrays.asList("auto", "vehicle", "car", "motor"),
                "HOME", Arrays.asList("home", "house", "property", "mortgage"),
                "EDUCATION", Arrays.asList("education", "study", "university", "tuition"),
                "BUSINESS", Arrays.asList("business", "sme", "corporate", "company"),
                "INSURANCE", Arrays.asList("coverage", "protection", "takaful", "claim"),
                "PAYMENT", Arrays.asList("payment", "cashback", "rewards", "card")
        );

        List<String> keywords = intentKeywords.getOrDefault(intent, Collections.emptyList());
        return keywords.stream().anyMatch(benefitsText::contains);
    }

    /**
     * Generate explanation for why product was recommended
     */
    private String generateReason(Product product, Map<String, Object> intentData) {
        String intent = (String) intentData.get("intent");
        String structure = product.getIslamicStructure() != null
                ? product.getIslamicStructure()
                : "Sharia-compliant";

        // Get product-specific highlight
        String productHighlight = getProductHighlight(product, intent);

        Map<String, String> reasonTemplates = Map.of(
                "TRAVEL", "Perfect for travelers with %s structure - %s",
                "LOAN", "Flexible %s financing - %s",
                "SAVINGS", "Grow your wealth with %s profit-sharing - %s",
                "INVESTMENT", "Build your portfolio with %s investment - %s",
                "CAR", "Drive your dream car with %s auto financing - %s",
                "HOME", "Own your home through %s partnership - %s",
                "EDUCATION", "Invest in education with %s financing - %s",
                "BUSINESS", "Grow your business with %s solutions - %s",
                "INSURANCE", "Comprehensive protection through %s - %s",
                "PAYMENT", "Convenient payments with %s structure - %s"
        );

        String template = reasonTemplates.getOrDefault(intent,
                "Sharia-compliant " + product.getCategory().replace("_", " ").toLowerCase() + " - %s");

        return String.format(template, structure, productHighlight);
    }

    /**
     * Get product-specific highlight based on key benefits and product name
     */
    private String getProductHighlight(Product product, String intent) {
        List<String> benefits = product.getKeyBenefits();

        // If product has benefits, extract a relevant one
        if (benefits != null && !benefits.isEmpty()) {
            // Try to find benefit matching the intent
            Map<String, List<String>> intentKeywords = Map.of(
                    "TRAVEL", Arrays.asList("travel", "international", "forex", "cashback", "lounge"),
                    "BUSINESS", Arrays.asList("business", "corporate", "expense", "limit", "employee"),
                    "PAYMENT", Arrays.asList("cashback", "rewards", "points", "fee", "supplementary")
            );

            List<String> keywords = intentKeywords.getOrDefault(intent, Collections.emptyList());

            // Find first benefit containing any keyword
            for (String benefit : benefits) {
                String lowerBenefit = benefit.toLowerCase();
                for (String keyword : keywords) {
                    if (lowerBenefit.contains(keyword)) {
                        return benefit;
                    }
                }
            }

            // If no keyword match, return first benefit
            return benefits.get(0);
        }

        // Fallback to product name highlight
        String productName = product.getProductName().toLowerCase();
        if (productName.contains("travel")) {
            return "ideal for frequent travelers";
        } else if (productName.contains("business")) {
            return "designed for business needs";
        } else if (productName.contains("cashback") || productName.contains("rewards")) {
            return "earn rewards on every purchase";
        } else if (productName.contains("student") || productName.contains("campus")) {
            return "perfect for students";
        } else if (productName.contains("platinum") || productName.contains("elite")) {
            return "premium benefits and privileges";
        }

        return "meets your financial needs";
    }

    /**
     * Generate keywords for a product using LLM
     */
    public List<String> generateKeywords(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));

        log.info("Generating keywords for product: {}", product.getProductName());

        // Convert Product to a map format that LLMService can use
        Map<String, Object> productData = new HashMap<>();
        productData.put("productName", product.getProductName());
        productData.put("category", product.getCategory());
        productData.put("description", product.getDescription());
        productData.put("islamicStructure", product.getIslamicStructure());
        productData.put("keyBenefits", product.getKeyBenefits());
        productData.put("rawPageContent", product.getRawPageContent());

        List<String> keywords = llmService.generateKeywordsFromMap(productData);
        log.info("Generated {} keywords: {}", keywords.size(), keywords);

        return keywords;
    }

    /**
     * Generate summary for a product using LLM
     */
    public String generateSummary(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));
        log.info("Generating summary for product: {}", product.getProductName());
        Map<String, Object> productData = new HashMap<>();
        productData.put("productName", product.getProductName());
        productData.put("category", product.getCategory());
        productData.put("subCategory", product.getSubCategory());
        productData.put("islamicStructure", product.getIslamicStructure());
        productData.put("description", product.getDescription());
        productData.put("keyBenefits", product.getKeyBenefits());
        productData.put("keywords", product.getKeywords());
        productData.put("annualRate", product.getAnnualRate());
        productData.put("annualFee", product.getAnnualFee());
        productData.put("minIncome", product.getMinIncome());
        productData.put("minCreditScore", product.getMinCreditScore());
        productData.put("eligibilityCriteria", product.getEligibilityCriteria());
        productData.put("sourceUrl", product.getSourceUrl());
        productData.put("rawPageContent", product.getRawPageContent());
        String summary = llmService.generateSummaryFromMap(productData);
        log.info("Generated summary ({} chars) for product: {}",
                summary != null ? summary.length() : 0, product.getProductName());
        return summary;
    }

    /**
     * Save keywords for a product
     */
    @Transactional
    public Product saveKeywords(Long id, List<String> keywords) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));

        product.setKeywords(keywords);
        Product saved = productRepository.save(product);

        log.info("Saved {} keywords for product: {}", keywords.size(), product.getProductName());
        return saved;
    }

    /**
     * Refresh a product's raw page content by re-scraping its configured {@code sourceUrl}.
     * <p>
     * Workflow:
     * <ol>
     *   <li>Load the product — throws {@link RuntimeException} if not found.</li>
     *   <li>Validate that a source URL is configured — throws {@link IllegalStateException} if not.</li>
     *   <li>Delegate to {@link ScraperServiceClient} to fetch the current page text.</li>
     *   <li>Persist {@code rawPageContent} and {@code scrapedAt} on the product.</li>
     * </ol>
     *
     * @param id product ID
     * @return map with {@code scrapedAt} and a confirmation {@code message}
     * @throws RuntimeException      if product not found or scraper returns empty content
     * @throws IllegalStateException if the product has no {@code sourceUrl} configured
     */
    @Transactional
    public Map<String, Object> refreshProductContent(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));

        if (product.getSourceUrl() == null || product.getSourceUrl().isBlank()) {
            throw new IllegalStateException("No source URL configured for product: " + id);
        }

        log.info("Refreshing page content for product {} (sourceUrl={})",
                product.getProductName(), product.getSourceUrl());

        String rawText = scraperServiceClient.scrapePageContent(product.getSourceUrl());

        if (rawText == null || rawText.isBlank()) {
            throw new RuntimeException("Scraper returned empty content for: " + product.getSourceUrl());
        }

        product.setRawPageContent(rawText);
        product.setScrapedAt(LocalDateTime.now());
        productRepository.save(product);

        log.info("Refreshed raw content for product {} ({} chars)",
                product.getProductName(), rawText.length());

        return Map.of(
                "scrapedAt", product.getScrapedAt(),
                "message", "Content refreshed successfully"
        );
    }
}
