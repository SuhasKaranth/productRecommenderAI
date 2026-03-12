package com.smartguide.poc.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Custom authentication token for API key-based authentication
 *
 * This token holds:
 * - The API key value (principal)
 * - The ApiKey object with scopes (credentials)
 * - Spring Security authorities derived from scopes
 */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final String apiKeyValue;
    private final ApiKey apiKey;

    /**
     * Create an unauthenticated token (before validation)
     */
    public ApiKeyAuthenticationToken(String apiKeyValue) {
        super(null);
        this.apiKeyValue = apiKeyValue;
        this.apiKey = null;
        setAuthenticated(false);
    }

    /**
     * Create an authenticated token (after successful validation)
     */
    public ApiKeyAuthenticationToken(String apiKeyValue, ApiKey apiKey) {
        super(convertScopesToAuthorities(apiKey));
        this.apiKeyValue = apiKeyValue;
        this.apiKey = apiKey;
        setAuthenticated(true);
    }

    /**
     * Convert ApiKey scopes to Spring Security GrantedAuthority
     *
     * This allows us to use Spring Security's built-in authorization features
     * with @PreAuthorize, @Secured, etc.
     */
    private static Collection<? extends GrantedAuthority> convertScopesToAuthorities(ApiKey apiKey) {
        if (apiKey == null || apiKey.getScopes() == null) {
            return java.util.Collections.emptyList();
        }

        return apiKey.getScopes().stream()
                .flatMap(scope -> scope.getImpliedScopes().stream())
                .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope.getScopeName()))
                .collect(Collectors.toSet());
    }

    @Override
    public Object getCredentials() {
        return apiKey;
    }

    @Override
    public Object getPrincipal() {
        return apiKeyValue;
    }

    public ApiKey getApiKey() {
        return apiKey;
    }

    public String getApiKeyValue() {
        return apiKeyValue;
    }

    /**
     * Check if this token has a specific scope
     */
    public boolean hasScope(ApiKeyScope scope) {
        return apiKey != null && apiKey.hasScope(scope);
    }

    @Override
    public String toString() {
        return "ApiKeyAuthenticationToken{" +
                "authenticated=" + isAuthenticated() +
                ", scopes=" + (apiKey != null ? apiKey.getScopes() : "none") +
                '}';
    }
}
