package com.smartguide.scraper.util;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.regex.Pattern;

/**
 * Utility class for validating URLs
 */
public class UrlValidator {

    // Regex pattern for valid URLs supporting multiple TLDs
    private static final Pattern URL_PATTERN = Pattern.compile(
        "^(https?)://([\\w.-]+\\.(com|ae|my|sg|in|uk|au|nz|sa|qa|bh|om|kw|eg|jo|lb|tr|pk|bd|id|th|ph|vn|cn|jp|kr|tw|hk))(/.*)?$",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Validate if a string is a valid HTTP/HTTPS URL
     * Supports multiple country domains including UAE (.ae)
     */
    public static boolean isValidUrl(String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) {
            return false;
        }

        // Check regex pattern
        if (!URL_PATTERN.matcher(urlString.trim()).matches()) {
            return false;
        }

        // Additional validation using Java URL class
        try {
            URL url = new URL(urlString);
            String protocol = url.getProtocol();
            return "http".equalsIgnoreCase(protocol) || "https".equalsIgnoreCase(protocol);
        } catch (MalformedURLException e) {
            return false;
        }
    }

    /**
     * Extract domain from URL
     */
    public static String extractDomain(String urlString) {
        try {
            URL url = new URL(urlString);
            return url.getHost();
        } catch (MalformedURLException e) {
            return null;
        }
    }

    /**
     * Normalize URL (ensure it has protocol)
     */
    public static String normalizeUrl(String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) {
            return null;
        }

        String normalized = urlString.trim();

        // Add https:// if no protocol specified
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://" + normalized;
        }

        return normalized;
    }

    /**
     * Check if URL is accessible (basic check)
     */
    public static boolean isAccessible(String urlString) {
        // This is a placeholder - actual implementation would make HTTP request
        return isValidUrl(urlString);
    }
}
