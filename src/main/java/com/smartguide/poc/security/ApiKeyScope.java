package com.smartguide.poc.security;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Extensible scope system for API key permissions
 *
 * Design principles:
 * - Hierarchical: ADMIN includes all USER scopes
 * - Granular: Can define specific resource:action scopes
 * - Extensible: Easy to add new scopes without breaking existing code
 *
 * Scope naming convention: <resource>:<action>
 * Examples:
 * - products:read
 * - products:write
 * - staging:approve
 * - scraper:execute
 */
public enum ApiKeyScope {

    // ========== ADMIN SCOPES (Full Access) ==========
    /**
     * Full administrative access - includes all scopes
     */
    ADMIN("admin:*", "Full administrative access"),

    // ========== PRODUCT SCOPES ==========
    /**
     * Read product information
     */
    PRODUCTS_READ("products:read", "Read product information"),

    /**
     * Write/modify product information (admin only by default)
     */
    PRODUCTS_WRITE("products:write", "Create and modify products"),

    /**
     * Delete products (admin only)
     */
    PRODUCTS_DELETE("products:delete", "Delete products"),

    // ========== RECOMMENDATION SCOPES ==========
    /**
     * Use the recommendation API
     */
    RECOMMEND("recommend:execute", "Get product recommendations"),

    // ========== STAGING SCOPES ==========
    /**
     * View staging products
     */
    STAGING_READ("staging:read", "View staging products"),

    /**
     * Approve or reject staging products
     */
    STAGING_APPROVE("staging:approve", "Approve or reject staging products"),

    /**
     * Edit staging products before approval
     */
    STAGING_EDIT("staging:edit", "Edit staging products"),

    // ========== SCRAPER SCOPES ==========
    /**
     * Execute web scraping jobs
     */
    SCRAPER_EXECUTE("scraper:execute", "Execute web scraping jobs"),

    /**
     * View scraper logs and job status
     */
    SCRAPER_READ("scraper:read", "View scraper logs and status"),

    // ========== USER ROLE (Common Access) ==========
    /**
     * Standard user access - read products and get recommendations
     */
    USER("user:*", "Standard user access");

    private final String scopeName;
    private final String description;

    ApiKeyScope(String scopeName, String description) {
        this.scopeName = scopeName;
        this.description = description;
    }

    public String getScopeName() {
        return scopeName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Check if this scope implies (includes) another scope
     *
     * Examples:
     * - ADMIN implies all scopes
     * - USER implies PRODUCTS_READ and RECOMMEND
     * - PRODUCTS_WRITE implies PRODUCTS_READ
     */
    public boolean implies(ApiKeyScope other) {
        if (this == other) {
            return true;
        }

        // ADMIN has all permissions
        if (this == ADMIN) {
            return true;
        }

        // USER has read and recommend permissions
        if (this == USER) {
            return other == PRODUCTS_READ ||
                   other == RECOMMEND ||
                   other == SCRAPER_READ ||
                   other == STAGING_READ;
        }

        // Write permissions include read permissions for the same resource
        if (this == PRODUCTS_WRITE && other == PRODUCTS_READ) {
            return true;
        }

        if (this == STAGING_APPROVE &&
            (other == STAGING_READ || other == STAGING_EDIT)) {
            return true;
        }

        if (this == SCRAPER_EXECUTE && other == SCRAPER_READ) {
            return true;
        }

        return false;
    }

    /**
     * Get all scopes implied by this scope
     */
    public Set<ApiKeyScope> getImpliedScopes() {
        Set<ApiKeyScope> implied = new HashSet<>();
        for (ApiKeyScope scope : values()) {
            if (this.implies(scope)) {
                implied.add(scope);
            }
        }
        return implied;
    }

    /**
     * Parse scope from string
     */
    public static ApiKeyScope fromString(String scopeStr) {
        for (ApiKeyScope scope : values()) {
            if (scope.scopeName.equalsIgnoreCase(scopeStr)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Unknown scope: " + scopeStr);
    }

    /**
     * Check if a set of scopes has permission for a required scope
     */
    public static boolean hasPermission(Set<ApiKeyScope> userScopes, ApiKeyScope requiredScope) {
        for (ApiKeyScope userScope : userScopes) {
            if (userScope.implies(requiredScope)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get all scopes for ADMIN role
     */
    public static Set<ApiKeyScope> getAdminScopes() {
        return Arrays.stream(values()).collect(Collectors.toSet());
    }

    /**
     * Get all scopes for USER role
     */
    public static Set<ApiKeyScope> getUserScopes() {
        return Set.of(
            USER,
            PRODUCTS_READ,
            RECOMMEND,
            STAGING_READ,
            SCRAPER_READ
        );
    }

    @Override
    public String toString() {
        return scopeName;
    }
}
