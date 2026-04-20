package com.smartguide.scraper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import com.smartguide.scraper.config.BankSourceConfig;
import com.smartguide.scraper.config.ScraperProperties;
import com.smartguide.scraper.dto.StructuredCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ConfigurableCardExtractor}.
 * Playwright's {@link Page} is mocked so no real browser is launched.
 */
@ExtendWith(MockitoExtension.class)
class ConfigurableCardExtractorTest {

    @Mock
    private ScraperProperties scraperProperties;

    private ConfigurableCardExtractor extractor;

    private static final BankSourceConfig DIB_CONFIG = new BankSourceConfig(
            "DIB",
            "https://dib.ae/personal/cards",
            "a[href^='/personal/cards/']:has(h3)",
            "h3",
            "",
            "p",
            "ul li",
            null
    );

    @BeforeEach
    void setUp() {
        extractor = new ConfigurableCardExtractor(scraperProperties, new ObjectMapper());
    }

    @Test
    @DisplayName("extractCards_happyPath — JS returns valid JSON with 3 cards -> 3 StructuredCards returned")
    void extractCards_happyPath() {
        Page page = mock(Page.class);
        String json = """
                [
                  {"name":"DIB Cashback Card","href":"https://dib.ae/personal/cards/cashback","description":"Earn cashback","benefits":["5% cashback","No annual fee"]},
                  {"name":"DIB SHAMS Infinite","href":"https://dib.ae/personal/cards/shams-infinite","description":"Premium card","benefits":["Airport lounge"]},
                  {"name":"DIB Signature Card","href":"https://dib.ae/personal/cards/signature","description":null,"benefits":[]}
                ]
                """;
        when(page.evaluate(anyString())).thenReturn(json);

        List<StructuredCard> result = extractor.extractCards(page, DIB_CONFIG);

        assertThat(result).hasSize(3);

        StructuredCard first = result.get(0);
        assertThat(first.name()).isEqualTo("DIB Cashback Card");
        assertThat(first.href()).isEqualTo("https://dib.ae/personal/cards/cashback");
        assertThat(first.description()).isEqualTo("Earn cashback");
        assertThat(first.benefits()).containsExactly("5% cashback", "No annual fee");

        StructuredCard third = result.get(2);
        assertThat(third.name()).isEqualTo("DIB Signature Card");
        assertThat(third.description()).isNull();
        assertThat(third.benefits()).isEmpty();
    }

    @Test
    @DisplayName("extractCards_emptyResult — JS returns '[]' -> empty list returned")
    void extractCards_emptyResult() {
        Page page = mock(Page.class);
        when(page.evaluate(anyString())).thenReturn("[]");

        List<StructuredCard> result = extractor.extractCards(page, DIB_CONFIG);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("extractCards_jsThrows — page.evaluate throws RuntimeException -> empty list returned, no exception propagates")
    void extractCards_jsThrows() {
        Page page = mock(Page.class);
        when(page.evaluate(anyString())).thenThrow(new RuntimeException("Playwright JS error"));

        List<StructuredCard> result = extractor.extractCards(page, DIB_CONFIG);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("extractCards_blankNameSkipped — card with blank name is filtered out")
    void extractCards_blankNameSkipped() {
        Page page = mock(Page.class);
        String json = """
                [
                  {"name":"","href":"https://dib.ae/personal/cards/cashback","description":"desc","benefits":[]},
                  {"name":"DIB Valid Card","href":"https://dib.ae/personal/cards/valid","description":null,"benefits":[]}
                ]
                """;
        when(page.evaluate(anyString())).thenReturn(json);

        List<StructuredCard> result = extractor.extractCards(page, DIB_CONFIG);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("DIB Valid Card");
    }

    @Test
    @DisplayName("extractCards_blankHrefSkipped — card with blank href is filtered out")
    void extractCards_blankHrefSkipped() {
        Page page = mock(Page.class);
        String json = """
                [
                  {"name":"DIB Card With No URL","href":"","description":"desc","benefits":[]},
                  {"name":"DIB Card With URL","href":"https://dib.ae/personal/cards/valid","description":null,"benefits":[]}
                ]
                """;
        when(page.evaluate(anyString())).thenReturn(json);

        List<StructuredCard> result = extractor.extractCards(page, DIB_CONFIG);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("DIB Card With URL");
    }

    @Test
    @DisplayName("extractCards_skipsApplyNowLinks — card whose href contains 'apply-now' is filtered out by the Java safety net")
    void extractCards_skipsApplyNowLinks() {
        Page page = mock(Page.class);
        String json = """
                [
                  {"name":"Apply Now","href":"https://dib.ae/personal/cards/apply-now","description":null,"benefits":[]}
                ]
                """;
        when(page.evaluate(anyString())).thenReturn(json);

        List<StructuredCard> result = extractor.extractCards(page, DIB_CONFIG);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("extractCards_skipsPdfLinks — card whose href ends with '.pdf' is filtered out by the Java safety net")
    void extractCards_skipsPdfLinks() {
        Page page = mock(Page.class);
        String json = """
                [
                  {"name":"Brochure","href":"https://dib.ae/personal/cards/brochure.pdf","description":null,"benefits":[]}
                ]
                """;
        when(page.evaluate(anyString())).thenReturn(json);

        List<StructuredCard> result = extractor.extractCards(page, DIB_CONFIG);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("extractCards_includesCard_withNameFromInnerTextFallback — card with a plain name (no heading element) is returned")
    void extractCards_includesCard_withNameFromInnerTextFallback() {
        Page page = mock(Page.class);
        String json = """
                [
                  {"name":"DIB Classic Card","href":"https://dib.ae/personal/cards/classic-card","description":null,"benefits":[]}
                ]
                """;
        when(page.evaluate(anyString())).thenReturn(json);

        List<StructuredCard> result = extractor.extractCards(page, DIB_CONFIG);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("DIB Classic Card");
        assertThat(result.get(0).href()).isEqualTo("https://dib.ae/personal/cards/classic-card");
    }
}
