package com.smartguide.scraper.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ScraperProperties}.
 * No Spring context needed — the class is tested as a plain Java object.
 */
class ScraperPropertiesTest {

    private static BankSourceConfig validConfig(String bank, String url) {
        return new BankSourceConfig(bank, url, ".card", "h3", "", "p", "ul li", null);
    }

    private static BankSourceConfig configWithBlankField(String bank, String url,
                                                          String containerSel, String nameSel) {
        return new BankSourceConfig(bank, url, containerSel, nameSel, "", null, null, null);
    }

    // --- findConfigForUrl tests ---

    @Test
    @DisplayName("findConfigForUrl_exactPrefixMatch — config url is a prefix of scraped URL -> config returned")
    void findConfigForUrl_exactPrefixMatch() {
        ScraperProperties props = new ScraperProperties();
        props.setBankSources(List.of(validConfig("DIB", "https://dib.ae/personal/cards")));

        Optional<BankSourceConfig> result =
                props.findConfigForUrl("https://dib.ae/personal/cards?lang=en");

        assertThat(result).isPresent();
        assertThat(result.get().bank()).isEqualTo("DIB");
    }

    @Test
    @DisplayName("findConfigForUrl_exactUrlMatch — scraped URL equals config url exactly -> config returned")
    void findConfigForUrl_exactUrlMatch() {
        ScraperProperties props = new ScraperProperties();
        props.setBankSources(List.of(validConfig("DIB", "https://dib.ae/personal/cards")));

        Optional<BankSourceConfig> result =
                props.findConfigForUrl("https://dib.ae/personal/cards");

        assertThat(result).isPresent();
        assertThat(result.get().bank()).isEqualTo("DIB");
    }

    @Test
    @DisplayName("findConfigForUrl_noMatch — no config url is a prefix of the scraped URL -> empty Optional")
    void findConfigForUrl_noMatch() {
        ScraperProperties props = new ScraperProperties();
        props.setBankSources(List.of(validConfig("DIB", "https://dib.ae/personal/cards")));

        Optional<BankSourceConfig> result =
                props.findConfigForUrl("https://adcb.com/personal/cards");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findConfigForUrl_multipleMatches_returnsFirst — two configs both match -> first returned")
    void findConfigForUrl_multipleMatches_returnsFirst() {
        BankSourceConfig first = validConfig("DIB-CARDS", "https://dib.ae/personal/cards");
        BankSourceConfig second = validConfig("DIB-PERSONAL", "https://dib.ae/personal");
        ScraperProperties props = new ScraperProperties();
        props.setBankSources(new ArrayList<>(List.of(first, second)));

        Optional<BankSourceConfig> result =
                props.findConfigForUrl("https://dib.ae/personal/cards/cashback");

        assertThat(result).isPresent();
        assertThat(result.get().bank()).isEqualTo("DIB-CARDS");
    }

    @Test
    @DisplayName("findConfigForUrl_nullUrl — null input -> empty Optional")
    void findConfigForUrl_nullUrl() {
        ScraperProperties props = new ScraperProperties();
        props.setBankSources(List.of(validConfig("DIB", "https://dib.ae/personal/cards")));

        assertThat(props.findConfigForUrl(null)).isEmpty();
    }

    // --- validateAndClean tests ---

    @Test
    @DisplayName("validateAndClean_removesInvalidEntries — entry with blank cardContainerSelector is removed at startup")
    void validateAndClean_removesInvalidEntries() {
        BankSourceConfig invalid = configWithBlankField("BAD-BANK", "https://bad.ae/products", "", "h3");
        BankSourceConfig valid = validConfig("DIB", "https://dib.ae/personal/cards");
        ScraperProperties props = new ScraperProperties();
        props.setBankSources(new ArrayList<>(List.of(invalid, valid)));

        props.validateAndCleanBankSources();

        assertThat(props.getBankSources()).hasSize(1);
        assertThat(props.getBankSources().get(0).bank()).isEqualTo("DIB");
    }

    @Test
    @DisplayName("validateAndClean_removesEntryWithBlankNameSelector — entry with blank nameSelector is removed")
    void validateAndClean_removesEntryWithBlankNameSelector() {
        BankSourceConfig invalid = configWithBlankField("BAD-BANK", "https://bad.ae/products", ".card", "");
        ScraperProperties props = new ScraperProperties();
        props.setBankSources(new ArrayList<>(List.of(invalid)));

        props.validateAndCleanBankSources();

        assertThat(props.getBankSources()).isEmpty();
    }

    @Test
    @DisplayName("validateAndClean_removesEntryWithBlankUrl — entry with blank url is removed")
    void validateAndClean_removesEntryWithBlankUrl() {
        BankSourceConfig invalid = configWithBlankField("DIB", "", ".card", "h3");
        ScraperProperties props = new ScraperProperties();
        props.setBankSources(new ArrayList<>(List.of(invalid)));

        props.validateAndCleanBankSources();

        assertThat(props.getBankSources()).isEmpty();
    }

    @Test
    @DisplayName("validateAndClean_keepsValidEntries — all-valid entries are preserved after cleanup")
    void validateAndClean_keepsValidEntries() {
        BankSourceConfig a = validConfig("DIB", "https://dib.ae/personal/cards");
        BankSourceConfig b = validConfig("ADCB", "https://adcb.com/personal/cards");
        ScraperProperties props = new ScraperProperties();
        props.setBankSources(new ArrayList<>(List.of(a, b)));

        props.validateAndCleanBankSources();

        assertThat(props.getBankSources()).hasSize(2);
    }
}
