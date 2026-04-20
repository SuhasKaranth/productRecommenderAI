package com.smartguide.scraper.controller;

import com.smartguide.scraper.dto.ScrapeJob;
import com.smartguide.scraper.dto.ScrapeRequest;
import com.smartguide.scraper.service.EnhancedScraperService;
import com.smartguide.scraper.service.ScrapeJobStore;
import com.smartguide.scraper.service.ScraperConfigLoader;
import com.smartguide.scraper.service.ScraperOrchestrationService;
import com.smartguide.scraper.service.BasicScraperService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the three new enrichment endpoints on {@link ScraperController}.
 *
 * <p>Tests follow the same pattern as the existing Mockito-based service tests —
 * no Spring context is loaded, keeping the tests fast and free of infrastructure
 * dependencies (Playwright, database, Ollama).
 *
 * <p>The async {@code CompletableFuture.runAsync()} call means the HTTP response
 * arrives before the scrape completes. Tests verify only the immediate HTTP response;
 * the enrichment logic is covered by {@link CardEnrichmentServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class ScraperControllerEnrichmentTest {

    @Mock
    private ScraperOrchestrationService orchestrationService;

    @Mock
    private ScraperConfigLoader configLoader;

    @Mock
    private BasicScraperService basicScraperService;

    @Mock
    private EnhancedScraperService enhancedScraperService;

    @Mock
    private ScrapeJobStore jobStore;

    @InjectMocks
    private ScraperController controller;

    private static final String TEST_URL = "https://dib.ae/personal/cards";

    private ScrapeJob activeJob(String jobId) {
        return ScrapeJob.builder()
                .jobId(jobId)
                .url(TEST_URL)
                .status(ScrapeJob.Status.IN_PROGRESS)
                .startedAt(LocalDateTime.now())
                .cancelled(false)
                .build();
    }

    private ScrapeJob completedJob(String jobId) {
        return ScrapeJob.builder()
                .jobId(jobId)
                .url(TEST_URL)
                .status(ScrapeJob.Status.COMPLETED)
                .startedAt(LocalDateTime.now().minusMinutes(2))
                .completedAt(LocalDateTime.now())
                .cancelled(false)
                .build();
    }

    private ScrapeRequest requestFor(String url) {
        ScrapeRequest req = new ScrapeRequest();
        req.setUrl(url);
        return req;
    }

    // -------------------------------------------------------------------------
    // POST /api/scraper/scrape-and-enrich
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("scrapeAndEnrich_returns202WithJobId — normal case returns 202 ACCEPTED with jobId")
    void scrapeAndEnrich_returns202WithJobId() {
        ScrapeJob job = activeJob("enrich-123-abc");
        when(jobStore.getActiveJobForUrl(TEST_URL)).thenReturn(null);
        when(jobStore.createJob(TEST_URL)).thenReturn(job);
        // enhancedScraperService.scrapeAndEnrichAsync runs on background thread — we stub as no-op
        doNothing().when(enhancedScraperService).scrapeAndEnrichAsync(anyString(), any(ScrapeJob.class));

        ResponseEntity<?> response = controller.scrapeAndEnrich(requestFor(TEST_URL));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("jobId")).isEqualTo("enrich-123-abc");
        assertThat(body.get("status")).isEqualTo("STARTED");
        assertThat(body.get("url")).isEqualTo(TEST_URL);
    }

    @Test
    @DisplayName("scrapeAndEnrich_duplicateUrl_returns409 — active job for same URL returns 409 CONFLICT")
    void scrapeAndEnrich_duplicateUrl_returns409() {
        ScrapeJob existing = activeJob("enrich-existing-zzz");
        when(jobStore.getActiveJobForUrl(TEST_URL)).thenReturn(existing);

        ResponseEntity<?> response = controller.scrapeAndEnrich(requestFor(TEST_URL));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("jobId")).isEqualTo("enrich-existing-zzz");
        assertThat(body.get("status")).isEqualTo("IN_PROGRESS");

        // No new job should be created
        verify(jobStore, never()).createJob(anyString());
    }

    // -------------------------------------------------------------------------
    // GET /api/scraper/jobs/{jobId}/status
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getJobStatus_unknownJobId_returns404 — unknown jobId returns 404 NOT FOUND")
    void getJobStatus_unknownJobId_returns404() {
        when(jobStore.getJob("nonexistent")).thenReturn(null);

        ResponseEntity<?> response = controller.getEnrichmentJobStatus("nonexistent");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("getJobStatus_existingJob_returns200 — known jobId returns 200 with job state")
    void getJobStatus_existingJob_returns200() {
        ScrapeJob job = activeJob("enrich-123-abc");
        when(jobStore.getJob("enrich-123-abc")).thenReturn(job);

        ResponseEntity<?> response = controller.getEnrichmentJobStatus("enrich-123-abc");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(job);
    }

    @Test
    @DisplayName("getJobStatus_completedJob_returns200 — completed job is also returned with 200")
    void getJobStatus_completedJob_returns200() {
        ScrapeJob job = completedJob("enrich-done-xyz");
        when(jobStore.getJob("enrich-done-xyz")).thenReturn(job);

        ResponseEntity<?> response = controller.getEnrichmentJobStatus("enrich-done-xyz");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((ScrapeJob) response.getBody()).getStatus())
                .isEqualTo(ScrapeJob.Status.COMPLETED);
    }

    // -------------------------------------------------------------------------
    // DELETE /api/scraper/jobs/{jobId}
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("cancelJob_activeJob_returns200 — active job returns 200 with CANCELLING status")
    void cancelJob_activeJob_returns200() {
        ScrapeJob job = activeJob("enrich-123-abc");
        when(jobStore.getJob("enrich-123-abc")).thenReturn(job);

        ResponseEntity<?> response = controller.cancelEnrichmentJob("enrich-123-abc");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("status")).isEqualTo("CANCELLING");
        verify(jobStore).requestCancellation("enrich-123-abc");
    }

    @Test
    @DisplayName("cancelJob_completedJob_returns400 — completed job returns 400 with 'not active' message")
    void cancelJob_completedJob_returns400() {
        ScrapeJob job = completedJob("enrich-done-xyz");
        when(jobStore.getJob("enrich-done-xyz")).thenReturn(job);

        ResponseEntity<?> response = controller.cancelEnrichmentJob("enrich-done-xyz");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat((String) body.get("message")).contains("not active");
        verify(jobStore, never()).requestCancellation(anyString());
    }

    @Test
    @DisplayName("cancelJob_unknownJob_returns404 — unknown jobId returns 404 NOT FOUND")
    void cancelJob_unknownJob_returns404() {
        when(jobStore.getJob("nonexistent")).thenReturn(null);

        ResponseEntity<?> response = controller.cancelEnrichmentJob("nonexistent");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(jobStore, never()).requestCancellation(anyString());
    }
}
