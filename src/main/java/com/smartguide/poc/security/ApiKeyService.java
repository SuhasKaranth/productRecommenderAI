package com.smartguide.poc.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Service for managing and validating API keys
 *
 * Responsibilities:
 * - Load API keys from configuration on startup
 * - Validate API key format and existence
 * - Check scope permissions
 * - Audit API key usage
 *
 * Future enhancements:
 * - Database-backed key storage
 * - Key rotation
 * - Usage rate limiting per key
 * - Key expiration handling
 */
@Service
public class ApiKeyService {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyService.class);

    private final ApiKeyProperties properties;

    /**
     * In-memory cache of API keys
     * Key: API key string, Value: ApiKey object with scopes
     *
     * Future: Replace with database or Redis cache
     */
    private final Map<String, ApiKey> apiKeyCache = new HashMap<>();

    public ApiKeyService(ApiKeyProperties properties) {
        this.properties = properties;
    }

    /**
     * Initialize API keys from configuration on application startup
     */
    @PostConstruct
    public void initializeApiKeys() {
        logger.info("Initializing API key authentication...");

        if (!properties.isEnabled()) {
            logger.warn("API key authentication is DISABLED - all endpoints are unprotected!");
            return;
        }

        // Load admin keys
        for (String keyValue : properties.getAdminKeys()) {
            if (isValidKeyFormat(keyValue)) {
                ApiKey apiKey = ApiKey.createAdminKey(keyValue, "Admin key");
                apiKeyCache.put(keyValue, apiKey);
                logger.info("Loaded ADMIN API key: {}***", keyValue.substring(0, Math.min(10, keyValue.length())));
            } else {
                logger.warn("Invalid admin key format, skipping: {}", maskKey(keyValue));
            }
        }

        // Load user keys
        for (String keyValue : properties.getUserKeys()) {
            if (isValidKeyFormat(keyValue)) {
                ApiKey apiKey = ApiKey.createUserKey(keyValue, "User key");
                apiKeyCache.put(keyValue, apiKey);
                logger.info("Loaded USER API key: {}***", keyValue.substring(0, Math.min(10, keyValue.length())));
            } else {
                logger.warn("Invalid user key format, skipping: {}", maskKey(keyValue));
            }
        }

        // Load scoped keys (future enhancement)
        for (String keyValue : properties.getScopedKeys()) {
            if (isValidKeyFormat(keyValue)) {
                // TODO: Parse scopes from key format or separate configuration
                logger.info("Loaded SCOPED API key: {}***", keyValue.substring(0, Math.min(10, keyValue.length())));
            }
        }

        if (apiKeyCache.isEmpty() && properties.isEnabled()) {
            logger.error("API key authentication is enabled but NO VALID KEYS are configured!");
            logger.error("Please configure API keys in application.yml or .env file");
        } else {
            logger.info("API key authentication initialized with {} keys", apiKeyCache.size());
        }
    }

    /**
     * Validate an API key and return the associated ApiKey object
     *
     * @param keyValue The API key to validate
     * @return Optional containing the ApiKey if valid, empty otherwise
     */
    public Optional<ApiKey> validateApiKey(String keyValue) {
        if (keyValue == null || keyValue.trim().isEmpty()) {
            logger.debug("API key validation failed: key is null or empty");
            return Optional.empty();
        }

        if (!properties.isEnabled()) {
            // When auth is disabled, return a dummy admin key for development
            logger.debug("API key authentication is disabled, allowing request");
            return Optional.of(ApiKey.createAdminKey("dev-key", "Development mode"));
        }

        // Look up key in cache
        ApiKey apiKey = apiKeyCache.get(keyValue);
        if (apiKey == null) {
            logger.warn("API key validation failed: key not found: {}", maskKey(keyValue));
            return Optional.empty();
        }

        // Check if key is valid (active and not expired)
        if (!apiKey.isValid()) {
            logger.warn("API key validation failed: key is inactive or expired: {}", maskKey(keyValue));
            return Optional.empty();
        }

        // Mark key as used (for future usage tracking)
        apiKey.markAsUsed();

        logger.debug("API key validated successfully: {} with scopes: {}",
                maskKey(keyValue), apiKey.getScopes());

        return Optional.of(apiKey);
    }

    /**
     * Check if an API key has a specific scope permission
     *
     * @param apiKey The API key to check
     * @param requiredScope The scope required for the operation
     * @return true if the key has the required scope, false otherwise
     */
    public boolean hasScope(ApiKey apiKey, ApiKeyScope requiredScope) {
        if (apiKey == null || requiredScope == null) {
            return false;
        }

        boolean hasPermission = apiKey.hasScope(requiredScope);

        if (!hasPermission) {
            logger.warn("Insufficient permissions: API key {} does not have scope {}",
                    maskKey(apiKey.getKeyValue()), requiredScope);
        }

        return hasPermission;
    }

    /**
     * Validate key format
     * Expected format: sk_<level>_<random_string>
     * Examples: sk_admin_abc123, sk_user_xyz789
     *
     * @param keyValue The key to validate
     * @return true if format is valid, false otherwise
     */
    private boolean isValidKeyFormat(String keyValue) {
        if (keyValue == null || keyValue.trim().isEmpty()) {
            return false;
        }

        // Basic format check: should start with sk_ and have minimum length
        if (!keyValue.startsWith("sk_")) {
            logger.warn("Invalid key format: must start with 'sk_'");
            return false;
        }

        if (keyValue.length() < 20) {
            logger.warn("Invalid key format: key too short (minimum 20 characters)");
            return false;
        }

        // Count underscores (should have at least 2: sk_<level>_<random>)
        long underscoreCount = keyValue.chars().filter(ch -> ch == '_').count();
        if (underscoreCount < 2) {
            logger.warn("Invalid key format: expected format sk_<level>_<random>");
            return false;
        }

        return true;
    }

    /**
     * Mask API key for logging (show only first few characters)
     *
     * @param keyValue The key to mask
     * @return Masked key string
     */
    private String maskKey(String keyValue) {
        if (keyValue == null || keyValue.length() < 10) {
            return "***";
        }
        return keyValue.substring(0, 10) + "***";
    }

    /**
     * Check if authentication is enabled
     */
    public boolean isAuthEnabled() {
        return properties.isEnabled();
    }

    /**
     * Get the configured header name for API keys
     */
    public String getHeaderName() {
        return properties.getHeaderName();
    }

    /**
     * Get count of configured keys (for monitoring)
     */
    public int getKeyCount() {
        return apiKeyCache.size();
    }

    /**
     * Get count of admin keys (for monitoring)
     */
    public int getAdminKeyCount() {
        return (int) apiKeyCache.values().stream()
                .filter(key -> key.hasScope(ApiKeyScope.ADMIN))
                .count();
    }

    /**
     * Get count of user keys (for monitoring)
     */
    public int getUserKeyCount() {
        return (int) apiKeyCache.values().stream()
                .filter(key -> key.hasScope(ApiKeyScope.USER) && !key.hasScope(ApiKeyScope.ADMIN))
                .count();
    }
}
