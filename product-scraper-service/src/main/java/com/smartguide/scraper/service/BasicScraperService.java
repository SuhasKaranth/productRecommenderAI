package com.smartguide.scraper.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import com.smartguide.scraper.dto.ScrapeResponse;
import com.smartguide.scraper.util.UrlValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Basic web scraping service using Playwright
 * MVP1: Simple synchronous scraping that extracts text content
 */
@Slf4j
@Service
public class BasicScraperService {

    /**
     * Scrape a URL and extract all text content
     */
    public ScrapeResponse scrapeUrl(String url) {
        // Validate URL
        if (!UrlValidator.isValidUrl(url)) {
            return ScrapeResponse.builder()
                .url(url)
                .status("error")
                .message("Invalid URL format")
                .build();
        }

        try (Playwright playwright = Playwright.create()) {
            // Use Firefox instead of Chromium to avoid macOS crash issues
            Browser browser = playwright.firefox().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();

            log.info("Scraping URL: {}", url);

            // Navigate to the URL
            page.navigate(url, new Page.NavigateOptions()
                .setTimeout(30000) // 30 second timeout
                .setWaitUntil(WaitUntilState.NETWORKIDLE));

            // Extract page title
            String title = page.title();

            // Extract all visible text content
            String textContent = page.evaluate("() => document.body.innerText").toString();

            browser.close();

            log.info("Successfully scraped {} - extracted {} characters", url, textContent.length());

            return ScrapeResponse.builder()
                .url(url)
                .title(title)
                .textContent(textContent)
                .textLength(textContent.length())
                .status("success")
                .message("Successfully scraped " + textContent.length() + " characters")
                .build();

        } catch (Exception e) {
            log.error("Error scraping URL: {}", url, e);
            return ScrapeResponse.builder()
                .url(url)
                .status("error")
                .message("Failed to scrape URL: " + e.getMessage())
                .build();
        }
    }
}
