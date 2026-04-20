package com.smartguide.scraper.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * YAML-driven configuration that maps bank listing page URL prefixes to CSS selector sets.
 *
 * <p>Bound from the {@code scraper.bank-sources} list in {@code application.yml}.
 * At startup, entries with blank required fields are removed and a warning is logged for each.
 * Required fields are: {@code bank}, {@code url}, {@code cardContainerSelector},
 * {@code nameSelector}.
 *
 * <p>Use {@link #findConfigForUrl(String)} to look up a matching bank config for a given URL.
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "scraper")
public class ScraperProperties {

    private List<BankSourceConfig> bankSources = new ArrayList<>();

    public List<BankSourceConfig> getBankSources() {
        return bankSources;
    }

    public void setBankSources(List<BankSourceConfig> bankSources) {
        this.bankSources = bankSources != null ? bankSources : new ArrayList<>();
    }

    /**
     * Validates loaded bank-source entries at startup.
     * Removes any entry where a required field (bank, url, cardContainerSelector, nameSelector)
     * is null or blank, logging a WARN for each removed entry.
     * Logs an INFO summary of how many valid entries were retained.
     */
    @PostConstruct
    void validateAndCleanBankSources() {
        List<BankSourceConfig> valid = new ArrayList<>();
        for (BankSourceConfig config : bankSources) {
            if (isBlank(config.bank())) {
                log.warn("Removing bank-source entry with blank 'bank' field: {}", config);
                continue;
            }
            if (isBlank(config.url())) {
                log.warn("Removing bank-source entry for bank='{}' with blank 'url' field", config.bank());
                continue;
            }
            if (isBlank(config.cardContainerSelector())) {
                log.warn("Removing bank-source entry for bank='{}' with blank 'cardContainerSelector' field",
                        config.bank());
                continue;
            }
            if (isBlank(config.nameSelector())) {
                log.warn("Removing bank-source entry for bank='{}' with blank 'nameSelector' field",
                        config.bank());
                continue;
            }
            valid.add(config);
        }
        bankSources = valid;
        log.info("Loaded {} valid bank-source configuration(s)", bankSources.size());
    }

    /**
     * Find the first bank-source config whose {@code url} is a prefix of the given scraped URL.
     *
     * <p>Logs a WARN when multiple entries match (the first one is still returned).
     *
     * @param url the URL that is about to be scraped
     * @return an Optional containing the first matching {@link BankSourceConfig}, or empty if none match
     */
    public Optional<BankSourceConfig> findConfigForUrl(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        List<BankSourceConfig> matches = bankSources.stream()
                .filter(config -> url.startsWith(config.url()))
                .toList();

        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            log.warn("Multiple bank-source configs match URL '{}': {} — using first match (bank='{}')",
                    url, matches.stream().map(BankSourceConfig::bank).toList(), matches.get(0).bank());
        }
        return Optional.of(matches.get(0));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
