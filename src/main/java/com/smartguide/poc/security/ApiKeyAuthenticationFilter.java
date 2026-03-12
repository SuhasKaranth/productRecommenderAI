package com.smartguide.poc.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartguide.poc.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Filter that intercepts all HTTP requests and validates API keys
 *
 * This filter:
 * 1. Extracts the API key from the request header
 * 2. Validates the key using ApiKeyService
 * 3. Sets authentication in SecurityContext if valid
 * 4. Returns 401/403 errors if authentication fails
 *
 * Public endpoints (like /health) are allowed without API keys
 * This is configured in SecurityConfig
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService, ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String requestPath = request.getRequestURI();
        String method = request.getMethod();

        logger.debug("Processing request: {} {}", method, requestPath);

        // Skip authentication for public endpoints (configured in SecurityConfig)
        // This filter will not be called for those endpoints

        // Extract API key from header
        String headerName = apiKeyService.getHeaderName();
        String apiKeyValue = request.getHeader(headerName);

        if (apiKeyValue == null || apiKeyValue.trim().isEmpty()) {
            logger.warn("API key missing for request: {} {}", method, requestPath);
            sendErrorResponse(response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "MISSING_API_KEY",
                    "API key is required. Please provide " + headerName + " header.");
            return;
        }

        // Validate API key
        Optional<ApiKey> apiKeyOpt = apiKeyService.validateApiKey(apiKeyValue);

        if (apiKeyOpt.isEmpty()) {
            logger.warn("Invalid API key provided for request: {} {}", method, requestPath);
            sendErrorResponse(response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "INVALID_API_KEY",
                    "Invalid API key provided.");
            return;
        }

        ApiKey apiKey = apiKeyOpt.get();

        // Create authentication token and set in security context
        ApiKeyAuthenticationToken authToken = new ApiKeyAuthenticationToken(apiKeyValue, apiKey);
        SecurityContextHolder.getContext().setAuthentication(authToken);

        logger.debug("API key authenticated successfully for request: {} {} with scopes: {}",
                method, requestPath, apiKey.getScopes());

        // Continue the filter chain
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Clear security context after request
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Send standardized error response in JSON format
     */
    private void sendErrorResponse(HttpServletResponse response,
                                   int statusCode,
                                   String errorCode,
                                   String message) throws IOException {

        ErrorResponse errorResponse = new ErrorResponse(
                "error",
                errorCode,
                message,
                null
        );

        response.setStatus(statusCode);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        String jsonResponse = objectMapper.writeValueAsString(errorResponse);
        response.getWriter().write(jsonResponse);
        response.getWriter().flush();
    }

    /**
     * Determine if this filter should be applied to the request
     * This is controlled by SecurityConfig, so this filter will only be called
     * for protected endpoints
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Let SecurityConfig handle which endpoints need authentication
        // This method can be used for additional custom logic if needed
        return false;
    }
}
