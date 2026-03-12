package com.smartguide.poc.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for API key authentication
 * Loads API keys from application.yml which reads from environment variables
 */
@Configuration
@ConfigurationProperties(prefix = "security.api-key")
public class ApiKeyProperties {

    /**
     * Enable or disable API key authentication
     * Useful for local development
     */
    private boolean enabled = true;

    /**
     * Name of the HTTP header containing the API key
     * Default: X-API-Key
     */
    private String headerName = "X-API-Key";

    /**
     * List of admin-level API keys with full access
     * Format: sk_admin_<random_string>
     */
    private List<String> adminKeys = new ArrayList<>();

    /**
     * List of user-level API keys with limited access
     * Format: sk_user_<random_string>
     */
    private List<String> userKeys = new ArrayList<>();

    /**
     * List of API keys with custom scopes (future enhancement)
     * Format: sk_<scope>_<random_string>
     * Example: sk_read_products_abc123 with scope "read:products"
     */
    private List<String> scopedKeys = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public List<String> getAdminKeys() {
        return adminKeys;
    }

    public void setAdminKeys(List<String> adminKeys) {
        this.adminKeys = adminKeys != null ? adminKeys : new ArrayList<>();
    }

    public List<String> getUserKeys() {
        return userKeys;
    }

    public void setUserKeys(List<String> userKeys) {
        this.userKeys = userKeys != null ? userKeys : new ArrayList<>();
    }

    public List<String> getScopedKeys() {
        return scopedKeys;
    }

    public void setScopedKeys(List<String> scopedKeys) {
        this.scopedKeys = scopedKeys != null ? scopedKeys : new ArrayList<>();
    }

    /**
     * Get all configured API keys across all levels
     */
    public List<String> getAllKeys() {
        List<String> allKeys = new ArrayList<>();
        allKeys.addAll(adminKeys);
        allKeys.addAll(userKeys);
        allKeys.addAll(scopedKeys);
        return allKeys;
    }

    /**
     * Validate that at least some keys are configured when auth is enabled
     */
    public boolean hasKeys() {
        return !adminKeys.isEmpty() || !userKeys.isEmpty() || !scopedKeys.isEmpty();
    }
}
