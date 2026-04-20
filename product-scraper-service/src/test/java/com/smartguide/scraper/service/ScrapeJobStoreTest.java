package com.smartguide.scraper.service;

import com.smartguide.scraper.dto.EnrichmentProgressEvent;
import com.smartguide.scraper.dto.ScrapeJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ScrapeJobStore}.
 * No Spring context required — tested as a plain Java object.
 */
class ScrapeJobStoreTest {

    private ScrapeJobStore store;
    private static final String URL_A = "https://dib.ae/personal/cards";
    private static final String URL_B = "https://adcb.com/personal/cards";

    @BeforeEach
    void setUp() {
        store = new ScrapeJobStore();
    }

    // -------------------------------------------------------------------------
    // createJob
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createJob_returnsNewJob — new job is IN_PROGRESS with a generated jobId")
    void createJob_returnsNewJob() {
        ScrapeJob job = store.createJob(URL_A);

        assertThat(job).isNotNull();
        assertThat(job.getJobId()).isNotBlank();
        assertThat(job.getJobId()).startsWith("enrich-");
        assertThat(job.getUrl()).isEqualTo(URL_A);
        assertThat(job.getStatus()).isEqualTo(ScrapeJob.Status.IN_PROGRESS);
        assertThat(job.isActive()).isTrue();
        assertThat(job.getStartedAt()).isNotNull();
        assertThat(job.getEvents()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("createJob_duplicateUrl_returnsNull — second call for same URL returns null while first is active")
    void createJob_duplicateUrl_returnsNull() {
        ScrapeJob first = store.createJob(URL_A);
        assertThat(first).isNotNull();

        ScrapeJob duplicate = store.createJob(URL_A);

        assertThat(duplicate).isNull();
    }

    @Test
    @DisplayName("createJob_duplicateUrl_afterCompletion_succeeds — after completing first job, new job for same URL is allowed")
    void createJob_duplicateUrl_afterCompletion_succeeds() {
        ScrapeJob first = store.createJob(URL_A);
        assertThat(first).isNotNull();

        store.completeJob(first.getJobId(), ScrapeJob.Status.COMPLETED);

        ScrapeJob second = store.createJob(URL_A);
        assertThat(second).isNotNull();
        assertThat(second.getJobId()).isNotEqualTo(first.getJobId());
    }

    @Test
    @DisplayName("createJob_differentUrls_bothSucceed — two different URLs can have concurrent jobs")
    void createJob_differentUrls_bothSucceed() {
        ScrapeJob jobA = store.createJob(URL_A);
        ScrapeJob jobB = store.createJob(URL_B);

        assertThat(jobA).isNotNull();
        assertThat(jobB).isNotNull();
        assertThat(jobA.getJobId()).isNotEqualTo(jobB.getJobId());
    }

    @Test
    @DisplayName("createJob_trailingSlashIgnored — URLs differing only by trailing slash are treated as duplicates")
    void createJob_trailingSlashIgnored() {
        store.createJob(URL_A);

        // Same URL with trailing slash — should be treated as duplicate
        ScrapeJob duplicate = store.createJob(URL_A + "/");

        assertThat(duplicate).isNull();
    }

    // -------------------------------------------------------------------------
    // getJob
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getJob_existingJob_returnsJob — getJob returns the created job by its ID")
    void getJob_existingJob_returnsJob() {
        ScrapeJob created = store.createJob(URL_A);

        ScrapeJob retrieved = store.getJob(created.getJobId());

        assertThat(retrieved).isSameAs(created);
    }

    @Test
    @DisplayName("getJob_unknownId_returnsNull — getJob(nonexistent) returns null")
    void getJob_unknownId_returnsNull() {
        ScrapeJob result = store.getJob("nonexistent-job-id");

        assertThat(result).isNull();
    }

    // -------------------------------------------------------------------------
    // getActiveJobForUrl
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getActiveJobForUrl_activeJob_returnsJob — returns active job for the URL")
    void getActiveJobForUrl_activeJob_returnsJob() {
        ScrapeJob created = store.createJob(URL_A);

        ScrapeJob active = store.getActiveJobForUrl(URL_A);

        assertThat(active).isSameAs(created);
    }

    @Test
    @DisplayName("getActiveJobForUrl_noJob_returnsNull — returns null when no active job exists for URL")
    void getActiveJobForUrl_noJob_returnsNull() {
        ScrapeJob result = store.getActiveJobForUrl(URL_A);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("getActiveJobForUrl_afterCompletion_returnsNull — returns null after job completes")
    void getActiveJobForUrl_afterCompletion_returnsNull() {
        ScrapeJob job = store.createJob(URL_A);
        store.completeJob(job.getJobId(), ScrapeJob.Status.COMPLETED);

        ScrapeJob result = store.getActiveJobForUrl(URL_A);

        assertThat(result).isNull();
    }

    // -------------------------------------------------------------------------
    // requestCancellation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("requestCancellation_setsFlag — cancelled flag is true after requestCancellation")
    void requestCancellation_setsFlag() {
        ScrapeJob job = store.createJob(URL_A);
        assertThat(job.isCancelled()).isFalse();

        store.requestCancellation(job.getJobId());

        assertThat(job.isCancelled()).isTrue();
        // Status remains IN_PROGRESS until the enrichment loop reacts
        assertThat(job.isActive()).isTrue();
    }

    @Test
    @DisplayName("requestCancellation_completedJob_noOp — cancellation of a completed job has no effect")
    void requestCancellation_completedJob_noOp() {
        ScrapeJob job = store.createJob(URL_A);
        store.completeJob(job.getJobId(), ScrapeJob.Status.COMPLETED);

        store.requestCancellation(job.getJobId()); // Should be a no-op

        assertThat(job.isCancelled()).isFalse();
    }

    // -------------------------------------------------------------------------
    // addEvent
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("addEvent_appendsToList — three events added are all present in job.getEvents()")
    void addEvent_appendsToList() {
        ScrapeJob job = store.createJob(URL_A);
        String jobId = job.getJobId();

        EnrichmentProgressEvent e1 = EnrichmentProgressEvent.builder()
                .phase(EnrichmentProgressEvent.Phase.SCRAPING)
                .message("Connecting...")
                .timestamp(LocalDateTime.now())
                .build();
        EnrichmentProgressEvent e2 = EnrichmentProgressEvent.builder()
                .phase(EnrichmentProgressEvent.Phase.ENRICHING)
                .message("Batch 1 of 2 complete.")
                .batchNumber(1)
                .totalBatches(2)
                .productsProcessed(7)
                .timestamp(LocalDateTime.now())
                .build();
        EnrichmentProgressEvent e3 = EnrichmentProgressEvent.builder()
                .phase(EnrichmentProgressEvent.Phase.SAVING)
                .message("Saving 14 products...")
                .timestamp(LocalDateTime.now())
                .build();

        store.addEvent(jobId, e1);
        store.addEvent(jobId, e2);
        store.addEvent(jobId, e3);

        assertThat(job.getEvents()).hasSize(3);
        assertThat(job.getEvents().get(0).getMessage()).isEqualTo("Connecting...");
        assertThat(job.getEvents().get(1).getBatchNumber()).isEqualTo(1);
        assertThat(job.getEvents().get(2).getPhase())
                .isEqualTo(EnrichmentProgressEvent.Phase.SAVING);
    }

    @Test
    @DisplayName("addEvent_unknownJobId_noOp — addEvent for unknown job ID silently does nothing")
    void addEvent_unknownJobId_noOp() {
        // Should not throw
        store.addEvent("nonexistent-id", EnrichmentProgressEvent.builder()
                .phase(EnrichmentProgressEvent.Phase.SCRAPING)
                .message("test")
                .timestamp(LocalDateTime.now())
                .build());
    }

    // -------------------------------------------------------------------------
    // completeJob
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("completeJob_removesFromActiveUrlIndex — URL can be reused after completeJob is called")
    void completeJob_removesFromActiveUrlIndex() {
        ScrapeJob first = store.createJob(URL_A);
        store.completeJob(first.getJobId(), ScrapeJob.Status.COMPLETED);

        // Completing should remove from active URL index
        assertThat(store.getActiveJobForUrl(URL_A)).isNull();

        // New job for the same URL should succeed
        ScrapeJob second = store.createJob(URL_A);
        assertThat(second).isNotNull();
    }

    @Test
    @DisplayName("completeJob_setsCompletedAt — completedAt is set on completion")
    void completeJob_setsCompletedAt() {
        ScrapeJob job = store.createJob(URL_A);
        assertThat(job.getCompletedAt()).isNull();

        store.completeJob(job.getJobId(), ScrapeJob.Status.FAILED);

        assertThat(job.getCompletedAt()).isNotNull();
        assertThat(job.getStatus()).isEqualTo(ScrapeJob.Status.FAILED);
    }
}
