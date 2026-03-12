package com.smartguide.poc.security;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

/**
 * Represents an API key with associated scopes and metadata
 *
 * This class is designed to be extensible for future enhancements:
 * - Database persistence (add @Entity annotation)
 * - Key expiration
 * - Usage tracking
 * - Rate limiting per key
 */
public class ApiKey {

    /**
     * The actual API key value (hashed in production)
     */
    private final String keyValue;

    /**
     * Human-readable description of this key
     * Example: "Production frontend app", "Admin CLI tool"
     */
    private final String description;

    /**
     * Set of scopes granted to this key
     */
    private final Set<ApiKeyScope> scopes;

    /**
     * When this key was created (for future audit purposes)
     */
    private final LocalDateTime createdAt;

    /**
     * Optional expiration date (null = no expiration)
     * Future enhancement for key rotation
     */
    private final LocalDateTime expiresAt;

    /**
     * Whether this key is currently active
     */
    private boolean active;

    /**
     * Last time this key was used (for future usage tracking)
     */
    private LocalDateTime lastUsedAt;

    private ApiKey(Builder builder) {
        this.keyValue = builder.keyValue;
        this.description = builder.description;
        this.scopes = builder.scopes;
        this.createdAt = builder.createdAt != null ? builder.createdAt : LocalDateTime.now();
        this.expiresAt = builder.expiresAt;
        this.active = builder.active;
        this.lastUsedAt = builder.lastUsedAt;
    }

    /**
     * Check if this API key has a specific scope
     */
    public boolean hasScope(ApiKeyScope requiredScope) {
        return ApiKeyScope.hasPermission(this.scopes, requiredScope);
    }

    /**
     * Check if this API key is valid for use
     */
    public boolean isValid() {
        if (!active) {
            return false;
        }

        if (expiresAt != null && LocalDateTime.now().isAfter(expiresAt)) {
            return false;
        }

        return true;
    }

    /**
     * Check if this key is expired
     */
    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * Mark this key as used (for tracking)
     */
    public void markAsUsed() {
        this.lastUsedAt = LocalDateTime.now();
    }

    // Getters
    public String getKeyValue() {
        return keyValue;
    }

    public String getDescription() {
        return description;
    }

    public Set<ApiKeyScope> getScopes() {
        return scopes;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApiKey apiKey = (ApiKey) o;
        return Objects.equals(keyValue, apiKey.keyValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyValue);
    }

    @Override
    public String toString() {
        return "ApiKey{" +
                "description='" + description + '\'' +
                ", scopes=" + scopes +
                ", active=" + active +
                ", expired=" + isExpired() +
                '}';
    }

    /**
     * Builder for creating ApiKey instances
     */
    public static class Builder {
        private String keyValue;
        private String description = "Unnamed key";
        private Set<ApiKeyScope> scopes;
        private LocalDateTime createdAt;
        private LocalDateTime expiresAt;
        private boolean active = true;
        private LocalDateTime lastUsedAt;

        public Builder keyValue(String keyValue) {
            this.keyValue = keyValue;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder scopes(Set<ApiKeyScope> scopes) {
            this.scopes = scopes;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder expiresAt(LocalDateTime expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder active(boolean active) {
            this.active = active;
            return this;
        }

        public Builder lastUsedAt(LocalDateTime lastUsedAt) {
            this.lastUsedAt = lastUsedAt;
            return this;
        }

        public ApiKey build() {
            Objects.requireNonNull(keyValue, "keyValue must not be null");
            Objects.requireNonNull(scopes, "scopes must not be null");
            return new ApiKey(this);
        }
    }

    /**
     * Create an admin-level API key
     */
    public static ApiKey createAdminKey(String keyValue, String description) {
        return new Builder()
                .keyValue(keyValue)
                .description(description)
                .scopes(ApiKeyScope.getAdminScopes())
                .build();
    }

    /**
     * Create a user-level API key
     */
    public static ApiKey createUserKey(String keyValue, String description) {
        return new Builder()
                .keyValue(keyValue)
                .description(description)
                .scopes(ApiKeyScope.getUserScopes())
                .build();
    }

    /**
     * Create a custom API key with specific scopes
     */
    public static ApiKey createCustomKey(String keyValue, String description, Set<ApiKeyScope> scopes) {
        return new Builder()
                .keyValue(keyValue)
                .description(description)
                .scopes(scopes)
                .build();
    }
}
