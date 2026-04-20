package com.smartguide.poc.repository;

import com.smartguide.poc.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for Product entity
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Find product by product code
     */
    Optional<Product> findByProductCode(String productCode);

    /**
     * Find all active products
     */
    List<Product> findByActiveTrue();

    /**
     * Find active products by category
     */
    List<Product> findByCategoryAndActiveTrue(String category);

    /**
     * Find active products by multiple categories
     */
    List<Product> findByCategoryInAndActiveTrue(List<String> categories);

    /**
     * Find products matching category and minimum income requirement
     */
    @Query("SELECT p FROM Product p WHERE p.category IN :categories " +
           "AND p.active = true " +
           "AND (p.shariaCertified = true OR p.shariaCertified IS NULL) " +
           "AND (p.minIncome IS NULL OR p.minIncome <= :userIncome) " +
           "AND (p.minCreditScore IS NULL OR p.minCreditScore <= :creditScore)")
    List<Product> findRecommendedProducts(
            @Param("categories") List<String> categories,
            @Param("userIncome") BigDecimal userIncome,
            @Param("creditScore") Integer creditScore
    );

    /**
     * Find products by category with basic filters
     */
    @Query("SELECT p FROM Product p WHERE p.category IN :categories " +
           "AND p.active = true " +
           "AND (p.shariaCertified = true OR p.shariaCertified IS NULL)")
    List<Product> findByCategoriesWithBasicFilters(@Param("categories") List<String> categories);

    /**
     * Find products by category that have at least one keyword generated.
     * array_length(keywords, 1) returns NULL for NULL or empty arrays — the IS NOT NULL
     * check on the function result covers both cases without a separate null guard.
     */
    @Query(value = "SELECT * FROM products p " +
                   "WHERE p.category IN :categories " +
                   "AND p.active = true " +
                   "AND (p.sharia_certified = true OR p.sharia_certified IS NULL) " +
                   "AND array_length(p.keywords, 1) IS NOT NULL",
           nativeQuery = true)
    List<Product> findByCategoriesWithKeywords(@Param("categories") List<String> categories);
}
