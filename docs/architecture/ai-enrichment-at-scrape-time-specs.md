# AI Enrichment at Scrape Time -- Technical Specification

## Document Info

| Field | Value |
|---|---|
| Feature | AI enrichment of CSS-extracted product cards before staging save |
| Services affected | `product-scraper-service` (primary), `frontend` (progress UI) |
| Existing endpoints preserved | `POST /api/scraper/scrape-url-enhanced` unchanged |
| New endpoints | `POST /api/scraper/scrape-and-enrich`, `GET /api/scraper/jobs/{jobId}/status` |
| Database migrations | None -- all required columns already exist in `staging_products` |
| Requirements doc | `docs/requirements/ai-enrichment-at-scrape-time.md` |

---

## 1. Architecture Overview

### 1.1 Where enrichment sits in the pipeline

The enrichment step is inserted into `EnhancedScraperService.scrapeAndAnalyze()` in the **configured-bank flow only**, between the existing `convertStructuredCards()` call and the `stagingProductService.saveExtractedProducts()` call.

**Current flow (configured-bank path in `EnhancedScraperService`, lines 53-78):**

```
URL matched to BankSourceConfig
  -> BasicScraperService.scrapeUrlWithConfig(url, config)
  -> convertStructuredCards(structuredCards)        // StructuredCard -> ExtractedProduct
  -> deduplicateByName(products)
  -> StagingProductService.saveExtractedProducts()  // saves with NULL category/keywords
```

**New flow:**

```
URL matched to BankSourceConfig
  -> BasicScraperService.scrapeUrlWithConfig(url, config)
  -> convertStructuredCards(structuredCards)
  -> deduplicateByName(products)
  -> CardEnrichmentService.enrich(products, progressCallback)   // NEW
  -> StagingProductService.saveExtractedProducts()               // now saves WITH category/keywords
```

The generic LLM flow (`runGenericFlow`) is NOT changed. That flow already produces enriched products via `AIProductExtractor.extractProducts()`.

### 1.2 New classes vs extended classes

| Action | Class | Package |
|---|---|---|
| **Create** | `CardEnrichmentService` | `com.smartguide.scraper.service` |
| **Create** | `EnrichmentConfig` | `com.smartguide.scraper.config` |
| **Create** | `EnrichmentProgressEvent` | `com.smartguide.scraper.dto` |
| **Create** | `EnrichedCard` | `com.smartguide.scraper.dto` |
| **Create** | `ScrapeJob` | `com.smartguide.scraper.dto` |
| **Create** | `ScrapeJobStore` | `com.smartguide.scraper.service` |
| **Modify** | `EnhancedScraperService` | `com.smartguide.scraper.service` |
| **Modify** | `ScraperController` | `com.smartguide.scraper.controller` |
| **Modify** | `ExtractedProduct` | `com.smartguide.scraper.dto` |
| **Modify** | `application.yml` | `product-scraper-service/src/main/resources` |

### 1.3 Data flow diagram

```
[Playwright Browser]
       |
       v
[ConfigurableCardExtractor.extractCards()]
       |  List<StructuredCard>
       v
[EnhancedScraperService.convertStructuredCards()]
       |  List<ExtractedProduct>  (category=null, keywords=null, aiConfidence=null)
       v
[EnhancedScraperService.deduplicateByName()]
       |  List<ExtractedProduct>  (deduplicated)
       v
[CardEnrichmentService.enrich()]
       |  Splits into batches of N (default 7)
       |  For each batch:
       |    -> Build LLM prompt with product names + descriptions + benefits
       |    -> Call Ollama API
       |    -> Parse JSON response
       |    -> Validate category against taxonomy
       |    -> Check for Sharia non-compliant terms
       |    -> Merge enrichment fields into each ExtractedProduct
       |    -> Emit EnrichmentProgressEvent via callback
       |
       |  List<ExtractedProduct>  (category, aiSuggestedCategory, aiConfidence, keywords populated)
       v
[StagingProductService.saveExtractedProducts()]
       |  Each product saved via main-service POST /api/admin/staging
       v
[staging_products table]  (ready for admin review)
```

### 1.4 Enrichment modes

Both modes use the same `CardEnrichmentService` class. The mode is selected by `scraper.enrichment.mode` in `application.yml`.

| Mode | Behaviour | When to use |
|---|---|---|
| `BATCH` | N cards sent to the LLM in a single prompt. The LLM returns a JSON array with one enrichment object per card. | Default. Lower total latency, fewer LLM calls, less token overhead from repeated system instructions. |
| `SEQUENTIAL` | Each card sent to the LLM in its own call. | When the LLM struggles with multi-product prompts (e.g., smaller models that lose accuracy with long contexts). |

---

## 2. Non-blocking UI Thread Strategy

### 2.1 Approach comparison

| Criterion | Job-based polling | Server-Sent Events (SSE) |
|---|---|---|
| **Implementation complexity** | Low -- standard REST endpoints | Medium -- requires `SseEmitter`, timeout handling, reconnection logic |
| **Proxy/load-balancer compatibility** | Excellent -- short-lived HTTP requests | Poor -- many reverse proxies (nginx, Cloudflare) buffer or terminate long-lived connections |
| **Client disconnect handling** | Trivial -- job continues regardless, client polls when ready | Must detect broken connection, clean up emitter |
| **Frontend complexity** | Low -- `setInterval` + cleanup on unmount | Medium -- `EventSource` API, reconnect-on-error logic |
| **Progress granularity** | On each poll (e.g., every 2 seconds) | Real-time as events are emitted |
| **State persistence** | Job state stored server-side, survives client refresh | Lost on disconnect unless also stored server-side |
| **Cancellation** | `DELETE /api/scraper/jobs/{jobId}` sets a flag | Same, but also must close the SSE emitter |

### 2.2 Recommendation: Job-based polling

The job-based polling approach is recommended for this system because:

1. The scraper service sits behind the main app proxy in production -- SSE connections may be terminated.
2. The frontend already uses axios (not EventSource) and the polling pattern matches the existing `scraperApi.getJobStatus()` call in `api.js`.
3. Client refresh/disconnect must not interrupt the enrichment pipeline.
4. The admin may navigate away to the staging page and come back -- polling resumes automatically.

### 2.3 Endpoint design

#### POST /api/scraper/scrape-and-enrich

Starts an asynchronous scrape-and-enrich job. Returns immediately with a job ID.

**Request:**

```json
{
  "url": "https://dib.ae/personal/cards"
}
```

**Response (202 Accepted):**

```json
{
  "jobId": "enrich-1713520800000-a1b2c3",
  "url": "https://dib.ae/personal/cards",
  "status": "STARTED",
  "message": "Scrape and enrich job started"
}
```

**Error -- duplicate in-flight job (409 Conflict):**

```json
{
  "jobId": "enrich-1713520795000-x9y8z7",
  "url": "https://dib.ae/personal/cards",
  "status": "IN_PROGRESS",
  "message": "A scrape job for this URL is already in progress"
}
```

#### GET /api/scraper/jobs/{jobId}/status

Returns the current state of a scrape-and-enrich job, including all progress events emitted so far.

**Response (200 OK) -- in progress:**

```json
{
  "jobId": "enrich-1713520800000-a1b2c3",
  "url": "https://dib.ae/personal/cards",
  "status": "IN_PROGRESS",
  "phase": "ENRICHING",
  "startedAt": "2026-04-19T10:00:00",
  "totalProducts": 21,
  "totalBatches": 3,
  "completedBatches": 1,
  "productsProcessed": 7,
  "events": [
    {
      "timestamp": "2026-04-19T10:00:02",
      "phase": "SCRAPING",
      "message": "Found 21 product cards via CSS selectors."
    },
    {
      "timestamp": "2026-04-19T10:00:15",
      "phase": "ENRICHING",
      "batchNumber": 1,
      "totalBatches": 3,
      "productsProcessed": 7,
      "message": "Batch 1 of 3 complete. 6 high confidence, 1 needs review.",
      "products": [
        {
          "productName": "Al Islami Classic Charge Card",
          "category": "CHARGE_CARDS",
          "aiConfidence": 0.92,
          "keywordsCount": 8,
          "shariaFlag": false,
          "error": null
        },
        {
          "productName": "Cashback Covered Card",
          "category": "COVERED_CARDS",
          "aiConfidence": 0.88,
          "keywordsCount": 6,
          "shariaFlag": false,
          "error": null
        }
      ]
    }
  ]
}
```

**Response (200 OK) -- completed:**

```json
{
  "jobId": "enrich-1713520800000-a1b2c3",
  "url": "https://dib.ae/personal/cards",
  "status": "COMPLETED",
  "phase": "DONE",
  "startedAt": "2026-04-19T10:00:00",
  "completedAt": "2026-04-19T10:01:45",
  "totalProducts": 21,
  "totalBatches": 3,
  "completedBatches": 3,
  "productsProcessed": 21,
  "savedCount": 21,
  "highConfidenceCount": 18,
  "needsReviewCount": 3,
  "enrichmentFailureCount": 0,
  "summary": "21 products scraped. 18 categorised with high confidence. 3 flagged for manual review. 0 enrichment failures.",
  "events": [ /* ... all events ... */ ]
}
```

**Response (200 OK) -- failed:**

```json
{
  "jobId": "enrich-1713520800000-a1b2c3",
  "url": "https://dib.ae/personal/cards",
  "status": "FAILED",
  "phase": "SCRAPING",
  "startedAt": "2026-04-19T10:00:00",
  "completedAt": "2026-04-19T10:00:05",
  "error": "Playwright failed to load page: net::ERR_NAME_NOT_RESOLVED",
  "events": []
}
```

#### DELETE /api/scraper/jobs/{jobId}

Requests cancellation of an in-progress job. The server sets a cancellation flag; the enrichment loop checks this flag before processing the next batch. Products already enriched are saved to staging.

**Response (200 OK):**

```json
{
  "jobId": "enrich-1713520800000-a1b2c3",
  "status": "CANCELLING",
  "message": "Cancellation requested. Already-processed products will be saved."
}
```

### 2.4 EnrichmentProgressEvent DTO

```java
package com.smartguide.scraper.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class EnrichmentProgressEvent {

    public enum Phase { SCRAPING, ENRICHING, SAVING, DONE, FAILED, CANCELLED }

    private LocalDateTime timestamp;
    private Phase phase;
    private String message;

    // Enrichment-specific (null during SCRAPING/SAVING phases)
    private Integer batchNumber;
    private Integer totalBatches;
    private Integer productsProcessed;

    // Per-product results for this batch (null during SCRAPING/SAVING)
    private List<ProductResult> products;

    @Data
    @Builder
    public static class ProductResult {
        private String productName;
        private String category;
        private Double aiConfidence;
        private Integer keywordsCount;
        private boolean shariaFlag;
        private String error;  // null if enrichment succeeded
    }
}
```

---

## 3. New Classes (Scraper Service)

### 3.1 EnrichmentConfig

Maps to `scraper.enrichment.*` properties in `application.yml`.

```java
package com.smartguide.scraper.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "scraper.enrichment")
public class EnrichmentConfig {

    public enum EnrichmentMode { BATCH, SEQUENTIAL }

    /** BATCH or SEQUENTIAL. Default: BATCH */
    private EnrichmentMode mode = EnrichmentMode.BATCH;

    /** Number of products per LLM call in BATCH mode. Default: 7. Ignored in SEQUENTIAL mode. */
    private int batchSize = 7;

    /** Timeout in seconds for a single LLM call. Default: 120. */
    private int llmTimeoutSeconds = 120;

    /** Whether to retry a failed LLM call once before marking the batch as failed. Default: true. */
    private boolean retryOnFailure = true;

    /** Maximum number of retry attempts per batch. Default: 1. */
    private int maxRetries = 1;

    @PostConstruct
    void validate() {
        if (batchSize <= 0) {
            log.warn("scraper.enrichment.batch-size={} is invalid, defaulting to 7", batchSize);
            batchSize = 7;
        }
        if (llmTimeoutSeconds <= 0) {
            log.warn("scraper.enrichment.llm-timeout-seconds={} is invalid, defaulting to 120", llmTimeoutSeconds);
            llmTimeoutSeconds = 120;
        }
        if (maxRetries < 0) {
            log.warn("scraper.enrichment.max-retries={} is invalid, defaulting to 1", maxRetries);
            maxRetries = 1;
        }
        log.info("Enrichment config: mode={}, batchSize={}, llmTimeoutSeconds={}, retryOnFailure={}, maxRetries={}",
                mode, batchSize, llmTimeoutSeconds, retryOnFailure, maxRetries);
    }
}
```

### 3.2 EnrichedCard

Intermediate DTO returned by the LLM for a single card's enrichment result. This is the shape the LLM is instructed to produce per product.

```java
package com.smartguide.scraper.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * LLM enrichment result for a single product card.
 * Parsed from the LLM JSON response and merged into the corresponding ExtractedProduct.
 */
@Data
@Builder
public class EnrichedCard {
    private String productName;          // echoed back for matching
    private String category;             // one of the 10 valid Islamic banking categories
    private Double confidence;           // 0.0-1.0
    private List<String> keywords;       // searchable keywords
    private String islamicStructure;     // Murabaha, Ijarah, etc. (nullable)
    private String targetCustomer;       // who this product is for (nullable)
    private List<String> shariaViolations; // list of non-compliant terms found (empty if clean)
}
```

### 3.3 CardEnrichmentService

The central enrichment service. Depends on the existing `WebClient` bean and `ObjectMapper`.

```java
package com.smartguide.scraper.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartguide.scraper.config.EnrichmentConfig;
import com.smartguide.scraper.config.EnrichmentConfig.EnrichmentMode;
import com.smartguide.scraper.dto.EnrichedCard;
import com.smartguide.scraper.dto.EnrichmentProgressEvent;
import com.smartguide.scraper.dto.EnrichmentProgressEvent.Phase;
import com.smartguide.scraper.dto.EnrichmentProgressEvent.ProductResult;
import com.smartguide.scraper.dto.ExtractedProduct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CardEnrichmentService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final EnrichmentConfig config;

    @Value("${OLLAMA_HOST:http://localhost:11434}")
    private String ollamaHost;

    @Value("${OLLAMA_MODEL:llama3.2}")
    private String ollamaModel;

    @Value("${SCRAPER_LLM_MAX_TOKENS:6000}")
    private int maxTokens;

    // Valid Islamic banking categories (from requirements)
    private static final Set<String> VALID_CATEGORIES = Set.of(
        "COVERED_CARDS", "DEBIT_CARDS", "CHARGE_CARDS",
        "HOME_FINANCE", "PERSONAL_FINANCE", "AUTO_FINANCE",
        "TAKAFUL", "SAVINGS", "CURRENT_ACCOUNTS", "INVESTMENTS"
    );

    // Sharia non-compliant terms and their corrections
    private static final Map<String, String> SHARIA_VIOLATIONS = Map.of(
        "interest", "profit rate",
        "loan", "finance",
        "mortgage", "home finance",
        "insurance", "Takaful"
    );

    /**
     * Enrich a list of extracted products with AI-generated metadata.
     *
     * @param products         products to enrich (modified in place)
     * @param progressCallback called after each batch completes (nullable)
     * @param cancellationFlag supplier that returns true if job was cancelled (nullable)
     * @return the same list with enrichment fields populated
     */
    public List<ExtractedProduct> enrich(
            List<ExtractedProduct> products,
            Consumer<EnrichmentProgressEvent> progressCallback,
            java.util.function.BooleanSupplier cancellationFlag) {

        if (products == null || products.isEmpty()) {
            return products != null ? products : new ArrayList<>();
        }

        if (config.getMode() == EnrichmentMode.BATCH) {
            return enrichBatch(products, progressCallback, cancellationFlag);
        } else {
            return enrichSequential(products, progressCallback, cancellationFlag);
        }
    }

    // ---- BATCH mode ----

    private List<ExtractedProduct> enrichBatch(
            List<ExtractedProduct> products,
            Consumer<EnrichmentProgressEvent> progressCallback,
            java.util.function.BooleanSupplier cancellationFlag) {

        List<List<ExtractedProduct>> batches = partition(products, config.getBatchSize());
        int totalBatches = batches.size();
        int productsProcessedSoFar = 0;

        for (int i = 0; i < totalBatches; i++) {
            // Check cancellation before each batch
            if (cancellationFlag != null && cancellationFlag.getAsBoolean()) {
                log.info("Enrichment cancelled after {} batches", i);
                break;
            }

            List<ExtractedProduct> batch = batches.get(i);
            int batchNum = i + 1;
            log.info("Enriching batch {}/{} ({} products)", batchNum, totalBatches, batch.size());

            try {
                String prompt = buildBatchPrompt(batch);
                String response = callLLM(prompt);
                List<EnrichedCard> enriched = parseBatchResponse(response);
                mergeBatchResults(batch, enriched);
            } catch (Exception e) {
                log.error("Batch {}/{} enrichment failed: {}", batchNum, totalBatches, e.getMessage());
                markBatchFailed(batch);
            }

            // Post-process: validate categories, check Sharia terms
            for (ExtractedProduct product : batch) {
                validateCategory(product);
                checkShariaCompliance(product);
            }

            productsProcessedSoFar += batch.size();

            // Emit progress event
            if (progressCallback != null) {
                emitBatchEvent(progressCallback, batch, batchNum, totalBatches, productsProcessedSoFar);
            }
        }

        return products;
    }

    // ---- SEQUENTIAL mode ----

    private List<ExtractedProduct> enrichSequential(
            List<ExtractedProduct> products,
            Consumer<EnrichmentProgressEvent> progressCallback,
            java.util.function.BooleanSupplier cancellationFlag) {

        int total = products.size();

        for (int i = 0; i < total; i++) {
            if (cancellationFlag != null && cancellationFlag.getAsBoolean()) {
                log.info("Enrichment cancelled after {} products", i);
                break;
            }

            ExtractedProduct product = products.get(i);

            try {
                String prompt = buildSequentialPrompt(product);
                String response = callLLM(prompt);
                EnrichedCard enriched = parseSequentialResponse(response);
                if (enriched != null) {
                    mergeEnrichment(product, enriched);
                } else {
                    markProductFailed(product);
                }
            } catch (Exception e) {
                log.error("Sequential enrichment failed for '{}': {}",
                        product.getProductName(), e.getMessage());
                markProductFailed(product);
            }

            validateCategory(product);
            checkShariaCompliance(product);

            // In SEQUENTIAL mode, emit per-product events
            if (progressCallback != null) {
                emitSequentialEvent(progressCallback, product, i + 1, total);
            }
        }

        return products;
    }

    // ---- Prompt builders ----
    // (see Section 4 for full prompt text)

    String buildBatchPrompt(List<ExtractedProduct> batch);
    String buildSequentialPrompt(ExtractedProduct product);

    // ---- LLM call ----

    private String callLLM(String prompt) { /* see Section 5 for retry logic */ }

    // ---- Response parsing ----

    List<EnrichedCard> parseBatchResponse(String response);
    EnrichedCard parseSequentialResponse(String response);

    // ---- Merging ----

    private void mergeBatchResults(List<ExtractedProduct> batch, List<EnrichedCard> enriched);
    private void mergeEnrichment(ExtractedProduct product, EnrichedCard enriched);

    // ---- Validation ----

    void validateCategory(ExtractedProduct product);
    void checkShariaCompliance(ExtractedProduct product);

    // ---- Failure marking ----

    private void markBatchFailed(List<ExtractedProduct> batch);
    private void markProductFailed(ExtractedProduct product);

    // ---- Helpers ----

    private <T> List<List<T>> partition(List<T> list, int size);
    private void emitBatchEvent(...);
    private void emitSequentialEvent(...);
}
```

#### Key method signatures and responsibilities

| Method | Responsibility |
|---|---|
| `enrich(products, callback, cancel)` | Entry point. Delegates to batch or sequential mode. |
| `buildBatchPrompt(batch)` | Constructs the LLM prompt for N products. See Section 4.1. |
| `buildSequentialPrompt(product)` | Constructs the LLM prompt for 1 product. See Section 4.2. |
| `callLLM(prompt)` | Calls Ollama API with configured timeout. Implements retry logic per Section 5. |
| `parseBatchResponse(response)` | Parses JSON array of `EnrichedCard` objects from LLM output. |
| `parseSequentialResponse(response)` | Parses single `EnrichedCard` JSON object from LLM output. |
| `mergeBatchResults(batch, enriched)` | Matches each `EnrichedCard` to its `ExtractedProduct` by product name and copies fields. |
| `mergeEnrichment(product, enriched)` | Copies fields from one `EnrichedCard` to one `ExtractedProduct`. |
| `validateCategory(product)` | Checks `product.category` is in `VALID_CATEGORIES`. If not, sets category to null and appends warning to an enrichment notes field. |
| `checkShariaCompliance(product)` | Scans `description`, `keyBenefits`, and `category` for terms in `SHARIA_VIOLATIONS`. If found, builds a `reviewNotes` string listing specific violations. |
| `markBatchFailed(batch)` | Sets `confidenceScore=0.0` and populates a review note on every product in the batch. |
| `markProductFailed(product)` | Sets `confidenceScore=0.0` and populates a review note on the product. |
| `partition(list, size)` | Splits a list into sublists of at most `size` elements. |

### 3.4 ScrapeJobStore

In-memory store for tracking active and recently completed scrape-and-enrich jobs.

```java
package com.smartguide.scraper.service;

import com.smartguide.scraper.dto.EnrichmentProgressEvent;
import com.smartguide.scraper.dto.ScrapeJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ScrapeJobStore {

    private final Map<String, ScrapeJob> jobs = new ConcurrentHashMap<>();

    // Also index by URL to detect duplicate in-flight jobs
    private final Map<String, String> urlToActiveJobId = new ConcurrentHashMap<>();

    /** Max completed jobs to retain in memory (FIFO eviction). */
    private static final int MAX_COMPLETED_JOBS = 50;

    /**
     * Create a new job. Returns null if a job for this URL is already in progress.
     */
    public ScrapeJob createJob(String url) {
        // Check for duplicate in-flight
        String existingJobId = urlToActiveJobId.get(normalizeUrl(url));
        if (existingJobId != null) {
            ScrapeJob existing = jobs.get(existingJobId);
            if (existing != null && existing.isActive()) {
                log.warn("Duplicate scrape request for URL '{}' -- job '{}' already in progress", url, existingJobId);
                return null; // signals duplicate
            }
        }

        String jobId = generateJobId();
        ScrapeJob job = ScrapeJob.builder()
                .jobId(jobId)
                .url(url)
                .status(ScrapeJob.Status.IN_PROGRESS)
                .phase(EnrichmentProgressEvent.Phase.SCRAPING)
                .startedAt(LocalDateTime.now())
                .events(Collections.synchronizedList(new ArrayList<>()))
                .cancelled(false)
                .build();

        jobs.put(jobId, job);
        urlToActiveJobId.put(normalizeUrl(url), jobId);
        evictOldJobs();

        return job;
    }

    public ScrapeJob getJob(String jobId) {
        return jobs.get(jobId);
    }

    /** Returns the existing active job for the given URL, or null. */
    public ScrapeJob getActiveJobForUrl(String url) {
        String jobId = urlToActiveJobId.get(normalizeUrl(url));
        if (jobId == null) return null;
        ScrapeJob job = jobs.get(jobId);
        return (job != null && job.isActive()) ? job : null;
    }

    public void addEvent(String jobId, EnrichmentProgressEvent event) {
        ScrapeJob job = jobs.get(jobId);
        if (job != null) {
            job.getEvents().add(event);
        }
    }

    public void completeJob(String jobId, ScrapeJob.Status finalStatus) {
        ScrapeJob job = jobs.get(jobId);
        if (job != null) {
            job.setStatus(finalStatus);
            job.setCompletedAt(LocalDateTime.now());
            urlToActiveJobId.remove(normalizeUrl(job.getUrl()));
        }
    }

    public void requestCancellation(String jobId) {
        ScrapeJob job = jobs.get(jobId);
        if (job != null && job.isActive()) {
            job.setCancelled(true);
        }
    }

    private String generateJobId() {
        return "enrich-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().substring(0, 6);
    }

    private String normalizeUrl(String url) {
        // Strip trailing slash for dedup
        return url != null ? url.replaceAll("/+$", "").toLowerCase() : "";
    }

    private void evictOldJobs() {
        if (jobs.size() > MAX_COMPLETED_JOBS + 10) {
            jobs.entrySet().removeIf(e ->
                    !e.getValue().isActive()
                    && e.getValue().getCompletedAt() != null
                    && e.getValue().getCompletedAt().isBefore(LocalDateTime.now().minusHours(2)));
        }
    }
}
```

### 3.5 ScrapeJob DTO

```java
package com.smartguide.scraper.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ScrapeJob {

    public enum Status { IN_PROGRESS, COMPLETED, FAILED, CANCELLED }

    private String jobId;
    private String url;
    private Status status;
    private EnrichmentProgressEvent.Phase phase;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String error;

    // Counters
    private int totalProducts;
    private int totalBatches;
    private int completedBatches;
    private int productsProcessed;
    private int savedCount;
    private int highConfidenceCount;
    private int needsReviewCount;
    private int enrichmentFailureCount;

    // Progress log
    private List<EnrichmentProgressEvent> events;

    // Cancellation flag (checked by enrichment loop)
    private boolean cancelled;

    public boolean isActive() {
        return status == Status.IN_PROGRESS;
    }

    public String buildSummary() {
        return String.format(
            "%d products scraped. %d categorised with high confidence. %d flagged for manual review. %d enrichment failures.",
            totalProducts, highConfidenceCount, needsReviewCount, enrichmentFailureCount
        );
    }
}
```

---

## 4. LLM Prompt Design

### 4.1 BATCH enrichment prompt

The prompt is constructed by `CardEnrichmentService.buildBatchPrompt()`.

```
You are an Islamic banking product classification assistant.

For each product below, determine:
1. The Islamic banking category (EXACTLY one of: COVERED_CARDS, DEBIT_CARDS, CHARGE_CARDS, HOME_FINANCE, PERSONAL_FINANCE, AUTO_FINANCE, TAKAFUL, SAVINGS, CURRENT_ACCOUNTS, INVESTMENTS)
2. Your confidence in the category assignment (0.0 to 1.0)
3. Up to 10 searchable keywords (lowercase, no duplicates)
4. The Islamic financing structure if identifiable (Murabaha, Ijarah, Tawarruq, Wakala, Musharakah, etc.)
5. The target customer segment (1 sentence)
6. Any Sharia non-compliant terminology found in the product text:
   - "interest" should be "profit rate"
   - "loan" should be "finance"
   - "mortgage" should be "home finance"
   - "insurance" should be "Takaful"
   List each violation found. If none, return an empty array.

PRODUCTS TO CLASSIFY:

1. Name: "Al Islami Classic Charge Card"
   Description: "A premium charge card for everyday spending"
   Benefits: ["Cashback on dining", "Airport lounge access", "No annual fee first year"]

2. Name: "Home Finance Murabaha"
   Description: "Sharia-compliant home financing based on Murabaha structure"
   Benefits: ["Fixed profit rate", "Up to 25 years", "UAE nationals and residents"]

3. Name: "Auto Finance Ijarah"
   Description: "Vehicle financing with competitive interest rates and easy repayment"
   Benefits: ["Low monthly payments", "Insurance included", "Quick approval"]

Return ONLY a JSON array with one object per product, in the SAME ORDER as the input:
[
  {
    "product_name": "exact name from input",
    "category": "CATEGORY_NAME",
    "confidence": 0.85,
    "keywords": ["keyword1", "keyword2"],
    "islamic_structure": "Murabaha",
    "target_customer": "Premium customers seeking daily spending rewards",
    "sharia_violations": []
  }
]

JSON OUTPUT:
```

**Notes on the prompt:**
- Product names are quoted exactly as received from CSS extraction to enable reliable matching.
- The 10 valid categories are listed explicitly to constrain the LLM.
- The Sharia violation check is embedded in the prompt so the LLM flags issues during classification rather than requiring a separate post-processing pass.
- `Benefits` are included because they provide the strongest signal for category classification (e.g., "cashback" signals a card product, "fixed profit rate" signals financing).

### 4.2 SEQUENTIAL enrichment prompt

Identical structure but for a single product:

```
You are an Islamic banking product classification assistant.

Classify this product:

Name: "Al Islami Classic Charge Card"
Description: "A premium charge card for everyday spending"
Benefits: ["Cashback on dining", "Airport lounge access", "No annual fee first year"]

Determine:
1. Category: EXACTLY one of COVERED_CARDS, DEBIT_CARDS, CHARGE_CARDS, HOME_FINANCE, PERSONAL_FINANCE, AUTO_FINANCE, TAKAFUL, SAVINGS, CURRENT_ACCOUNTS, INVESTMENTS
2. Confidence (0.0 to 1.0)
3. Up to 10 searchable keywords (lowercase)
4. Islamic financing structure if identifiable
5. Target customer (1 sentence)
6. Sharia non-compliant terms found ("interest" -> "profit rate", "loan" -> "finance", "mortgage" -> "home finance", "insurance" -> "Takaful"). Empty array if none.

Return ONLY a JSON object:
{
  "product_name": "exact name from input",
  "category": "CATEGORY_NAME",
  "confidence": 0.85,
  "keywords": ["keyword1", "keyword2"],
  "islamic_structure": null,
  "target_customer": "...",
  "sharia_violations": []
}

JSON OUTPUT:
```

### 4.3 Response parsing and validation

#### Parsing strategy

1. Strip markdown code fences (`\`\`\`json ... \`\`\`` or `\`\`\` ... \`\`\``).
2. For BATCH mode: extract the outermost JSON array using the existing `extractJsonArray()` bracket-depth algorithm (already proven in `AIProductExtractor`).
3. For SEQUENTIAL mode: extract the outermost JSON object using `{`...`}` bracket-depth matching.
4. Use a lenient `ObjectMapper` (same settings as `AIProductExtractor` -- allow comments, single quotes, unquoted field names, ignore unknown properties).
5. Deserialise into `List<Map<String, Object>>` (batch) or `Map<String, Object>` (sequential), then map to `EnrichedCard` using safe type-conversion helpers.

#### Fallback for truncated batch response

Reuse the per-object salvage algorithm from `AIProductExtractor.parseProductsFromResponse()` (lines 380-414). Extract individually complete `{...}` blocks and parse each independently.

#### Validation rules applied after parsing

| Field | Validation | Action on failure |
|---|---|---|
| `category` | Must be in `VALID_CATEGORIES` | Set `category` to `null`, set `aiConfidence` to `0.0`, append to review notes: `"AI returned invalid category '[value]' -- manual categorisation required"` |
| `confidence` | Must be `>= 0.0` and `<= 1.0` | Clamp to `[0.0, 1.0]` range. `< 0.0` becomes `0.0`, `> 1.0` becomes `1.0`. |
| `keywords` | Must be a list of strings | If not a list, attempt to split a comma-separated string. If unparsable, set to empty list. |
| `product_name` | Must match an input product name (fuzzy, case-insensitive, whitespace-normalised) | If no match found, log a warning and skip the enrichment entry. |
| `sharia_violations` | Must be a list of strings | If not a list, wrap single string in a list. If null, treat as empty. |

---

## 5. Negative Cases and Error Handling

### a. LLM timeout

**Timeout value:** `scraper.enrichment.llm-timeout-seconds` (default 120 seconds).

**Implementation:** The `callLLM()` method uses `WebClient` with `.timeout(Duration.ofSeconds(config.getLlmTimeoutSeconds()))` on the `Mono`. On `TimeoutException`:

1. If `retryOnFailure` is true and retries remain, wait 2 seconds and retry once.
2. If retries exhausted, throw `LlmEnrichmentException` with message `"LLM call timed out after {N}s"`.
3. The calling batch/sequential handler catches this and calls `markBatchFailed()` / `markProductFailed()`.

```java
private String callLLM(String prompt) {
    int attempts = config.isRetryOnFailure() ? config.getMaxRetries() + 1 : 1;

    for (int attempt = 1; attempt <= attempts; attempt++) {
        try {
            Map<String, Object> request = Map.of(
                "model", ollamaModel,
                "prompt", prompt,
                "stream", false,
                "options", Map.of(
                    "temperature", 0.1,
                    "num_predict", maxTokens
                )
            );

            Map<String, Object> response = webClient.post()
                .uri(ollamaHost + "/api/generate")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(config.getLlmTimeoutSeconds()))
                .block();

            if (response != null && response.containsKey("response")) {
                return response.get("response").toString();
            }
            log.warn("LLM returned empty response on attempt {}/{}", attempt, attempts);
        } catch (Exception e) {
            log.error("LLM call failed on attempt {}/{}: {}", attempt, attempts, e.getMessage());
            if (attempt < attempts) {
                log.info("Retrying LLM call in 2 seconds...");
                try { Thread.sleep(2000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during LLM retry", ie);
                }
            } else {
                throw new RuntimeException("LLM enrichment failed after " + attempts + " attempts: " + e.getMessage(), e);
            }
        }
    }
    throw new RuntimeException("LLM enrichment failed: no response received");
}
```

### b. LLM returns malformed JSON

1. Primary parse attempt (full array or object).
2. If that fails, run the per-object salvage algorithm (extract individually complete `{...}` blocks).
3. For each salvaged object, attempt to map to `EnrichedCard`.
4. If zero objects are salvaged, the entire batch is marked as failed via `markBatchFailed()`.

### c. LLM returns an invalid category

In `validateCategory()`:

```java
void validateCategory(ExtractedProduct product) {
    String category = product.getCategory();
    if (category == null || !VALID_CATEGORIES.contains(category.toUpperCase())) {
        String original = category;
        product.setCategory(null);
        product.setConfidenceScore(0.0);
        String note = String.format("AI returned invalid category '%s' -- manual categorisation required", original);
        appendReviewNote(product, note);
        log.warn("Invalid category '{}' for product '{}' -- cleared", original, product.getProductName());
    }
}
```

### d. Confidence outside 0.0-1.0

Clamped silently during merge:

```java
double confidence = enriched.getConfidence() != null ? enriched.getConfidence() : 0.0;
confidence = Math.max(0.0, Math.min(1.0, confidence));
product.setConfidenceScore(confidence);
```

### e. LLM call fails for one batch

The `enrichBatch()` loop catches the exception for that batch, calls `markBatchFailed(batch)`, and **continues** to the next batch:

```java
private void markBatchFailed(List<ExtractedProduct> batch) {
    for (ExtractedProduct product : batch) {
        markProductFailed(product);
    }
}

private void markProductFailed(ExtractedProduct product) {
    product.setConfidenceScore(0.0);
    // Leave category as-is (null from CSS extraction)
    appendReviewNote(product, "AI enrichment failed -- manual categorisation required");
}
```

The progress event for that batch includes `error` set to the failure reason on each product.

### f. All LLM calls fail

All products are marked as failed (confidence 0.0, review note set). They are still saved to staging via `StagingProductService.saveExtractedProducts()`. The job completes with `enrichmentFailureCount == totalProducts`.

The frontend receives a completion event with `enrichmentFailureCount > 0` and surfaces it in the summary.

### g. Playwright scraping succeeds but returns 0 cards

The existing check in `EnhancedScraperService` (line 63: `if (!structuredCards.isEmpty())`) already handles this -- the method falls through to the generic LLM flow. No enrichment is triggered for the configured-bank path when zero cards are found.

In the new async `scrapeAndEnrich()` method, if zero cards are found:
1. Emit a SCRAPING phase event with message `"Configured extraction returned 0 cards. Falling back to generic LLM flow."`.
2. Delegate to `runGenericFlow()` as before (that flow already does its own LLM enrichment).
3. Complete the job.

### h. Playwright scraping fails entirely

`BasicScraperService.scrapeUrlWithConfig()` returns a `ScrapeResponse` with `status="error"`. The async handler:
1. Sets the job status to `FAILED`.
2. Sets `job.error` to the error message from the `ScrapeResponse`.
3. Emits a `FAILED` phase event.
4. No enrichment or staging save occurs.

### i. Staging save fails for one product

The existing `StagingProductService.saveExtractedProducts()` (scraper service, line 46-55) already catches per-product save exceptions and continues. The `savedCount` returned reflects only successful saves. The job records `savedCount` vs `totalProducts` so the frontend can display the discrepancy.

### j. Client disconnects mid-stream or stops polling

With the polling approach, this is a non-issue. The job runs on a server-side thread (`CompletableFuture.runAsync()` or a dedicated executor) independent of any HTTP connection. The `ScrapeJobStore` retains job state for 2 hours after completion. The client can reconnect and poll at any time.

### k. Concurrent scrape requests for same URL

`ScrapeJobStore.createJob()` checks `urlToActiveJobId` before creating a new job. If an active job exists for the normalised URL, the controller returns **409 Conflict** with the existing job ID so the client can poll that job instead.

```java
// In ScraperController:
ScrapeJob existingJob = jobStore.getActiveJobForUrl(request.getUrl());
if (existingJob != null) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
        "jobId", existingJob.getJobId(),
        "url", existingJob.getUrl(),
        "status", "IN_PROGRESS",
        "message", "A scrape job for this URL is already in progress"
    ));
}
```

### l. Batch size configured as 0 or negative

Handled by `EnrichmentConfig.validate()` at startup:

```java
if (batchSize <= 0) {
    log.warn("scraper.enrichment.batch-size={} is invalid, defaulting to 7", batchSize);
    batchSize = 7;
}
```

### m. sourceUrl is null or blank after enrichment

During the merge step in `CardEnrichmentService`, `sourceUrl` is not modified -- it retains whatever was set by `convertStructuredCards()` (which copies `card.href()`). However, the `href` field on `StructuredCard` is never blank (filtered by `ConfigurableCardExtractor`).

As an additional safety net in `StagingProductService.buildStagingRequest()` (scraper service, line 144), the existing fallback already handles this:

```java
data.put("sourceUrl", product.getSourceUrl() != null ? product.getSourceUrl() : listingUrl);
```

If `sourceUrl` is blank (empty string but not null), add a check:

```java
String sourceUrl = product.getSourceUrl();
if (sourceUrl == null || sourceUrl.isBlank()) {
    sourceUrl = listingUrl;
    appendReviewNote(product, "Product detail URL was missing -- listing URL used as fallback");
}
data.put("sourceUrl", sourceUrl);
```

### n. Sharia non-compliant term detected

`checkShariaCompliance()` runs after enrichment on every product, scanning the `description` and `keyBenefits` text:

```java
void checkShariaCompliance(ExtractedProduct product) {
    List<String> violations = new ArrayList<>();

    // Check description
    String description = product.getDescription();
    if (description != null) {
        for (Map.Entry<String, String> entry : SHARIA_VIOLATIONS.entrySet()) {
            // Word-boundary match to avoid false positives
            // e.g., "interest" matches but "interesting" does not
            if (description.toLowerCase().matches(".*\\b" + entry.getKey() + "\\b.*")) {
                violations.add("'" + entry.getKey() + "' found in description (should be '" + entry.getValue() + "')");
            }
        }
    }

    // Check key benefits
    if (product.getKeyBenefits() != null) {
        for (String benefit : product.getKeyBenefits()) {
            for (Map.Entry<String, String> entry : SHARIA_VIOLATIONS.entrySet()) {
                if (benefit.toLowerCase().matches(".*\\b" + entry.getKey() + "\\b.*")) {
                    violations.add("'" + entry.getKey() + "' found in benefits (should be '" + entry.getValue() + "')");
                }
            }
        }
    }

    // Also check LLM-reported violations from the enrichment response
    // (these come from the EnrichedCard.shariaViolations field)
    // Merge without duplicates

    if (!violations.isEmpty()) {
        String note = "SHARIA COMPLIANCE: " + String.join("; ", violations);
        appendReviewNote(product, note);
    }
}

private void appendReviewNote(ExtractedProduct product, String note) {
    // ExtractedProduct needs a new field: reviewNotes (see Section 8)
    String existing = product.getReviewNotes();
    if (existing == null || existing.isBlank()) {
        product.setReviewNotes(note);
    } else {
        product.setReviewNotes(existing + " | " + note);
    }
}
```

---

## 6. Frontend Contract

### 6.1 New API function in `frontend/src/services/api.js`

```javascript
export const scraperApi = {
  // ... existing methods unchanged ...

  // Scrape and enrich with AI (async, returns job ID)
  scrapeAndEnrich: (url) =>
    api.post('http://localhost:8081/api/scraper/scrape-and-enrich', { url }),

  // Poll job status
  getEnrichmentJobStatus: (jobId) =>
    api.get(`http://localhost:8081/api/scraper/jobs/${jobId}/status`),

  // Cancel a job
  cancelEnrichmentJob: (jobId) =>
    api.delete(`http://localhost:8081/api/scraper/jobs/${jobId}`),
};
```

### 6.2 Frontend polling strategy

The `ScrapeForm.jsx` component should poll every 2 seconds while the job is in progress:

```javascript
const POLL_INTERVAL_MS = 2000;

const startScrapeAndEnrich = async (url) => {
  const { data } = await scraperApi.scrapeAndEnrich(url);
  const jobId = data.jobId;
  setJobId(jobId);
  setPhase('SCRAPING');

  // Start polling
  const interval = setInterval(async () => {
    try {
      const { data: status } = await scraperApi.getEnrichmentJobStatus(jobId);
      setJobStatus(status);
      setPhase(status.phase);
      setEvents(status.events || []);

      if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(status.status)) {
        clearInterval(interval);
        setPolling(false);
      }
    } catch (err) {
      // Network error -- keep polling, do not stop the job
      console.error('Poll failed, will retry:', err.message);
    }
  }, POLL_INTERVAL_MS);

  // Cleanup on unmount
  return () => clearInterval(interval);
};
```

**Retry/reconnect logic:** The polling `setInterval` continues even if individual poll requests fail (network blip). It only stops when a terminal status is received.

### 6.3 Handling partial failure events

When the frontend receives a progress event where some products have `error != null`:

1. Display those products in the live results table with a red error chip instead of a category chip.
2. At completion, the summary shows `enrichmentFailureCount` and the "Review in Staging" button still appears.
3. The admin sees failed products in staging with `reviewNotes = "AI enrichment failed -- manual categorisation required"`.

### 6.4 Progress event payload shape (JSON)

See the full response examples in Section 2.3. The key nested shape is:

```typescript
// TypeScript interface for reference (not shipped)
interface EnrichmentProgressEvent {
  timestamp: string;        // ISO 8601
  phase: 'SCRAPING' | 'ENRICHING' | 'SAVING' | 'DONE' | 'FAILED' | 'CANCELLED';
  message: string;
  batchNumber?: number;     // present during ENRICHING
  totalBatches?: number;
  productsProcessed?: number;
  products?: ProductResult[];
}

interface ProductResult {
  productName: string;
  category: string | null;
  aiConfidence: number;
  keywordsCount: number;
  shariaFlag: boolean;
  error: string | null;
}
```

---

## 7. Configuration Reference

Add the following to `product-scraper-service/src/main/resources/application.yml`:

```yaml
scraper:
  # Existing bank-sources config unchanged
  bank-sources:
    - bank: DIB
      url: https://dib.ae/personal/cards
      card-container-selector: "div.card-list-item.show[data-categories]"
      name-selector: ".card-title-info a"
      url-selector: ".card-title-info a"
      description-selector: ".card-desc-info"
      benefits-selector: ".card-feature-info li"
      skip-if-label-contains: "COVERED CARDS BENEFITS"

  # NEW: AI enrichment configuration
  enrichment:
    # BATCH = N cards per LLM call; SEQUENTIAL = one card per LLM call
    mode: BATCH

    # Number of products per LLM call in BATCH mode. Ignored in SEQUENTIAL mode.
    # Valid range: 1+. Invalid values default to 7.
    batch-size: 7

    # Timeout in seconds for a single LLM API call.
    # Covers the entire round-trip including LLM inference time.
    llm-timeout-seconds: 120

    # Whether to retry a failed LLM call once before marking the batch as failed.
    retry-on-failure: true

    # Maximum number of retry attempts per failed LLM call.
    # Only applies when retry-on-failure is true.
    max-retries: 1
```

### Environment variable overrides

All properties can be overridden via environment variables:

| Property | Environment variable | Default |
|---|---|---|
| `scraper.enrichment.mode` | `SCRAPER_ENRICHMENT_MODE` | `BATCH` |
| `scraper.enrichment.batch-size` | `SCRAPER_ENRICHMENT_BATCH_SIZE` | `7` |
| `scraper.enrichment.llm-timeout-seconds` | `SCRAPER_ENRICHMENT_LLM_TIMEOUT_SECONDS` | `120` |
| `scraper.enrichment.retry-on-failure` | `SCRAPER_ENRICHMENT_RETRY_ON_FAILURE` | `true` |
| `scraper.enrichment.max-retries` | `SCRAPER_ENRICHMENT_MAX_RETRIES` | `1` |

---

## 8. Existing Classes to Modify

### 8.1 EnhancedScraperService (minimal changes)

**File:** `product-scraper-service/src/main/java/com/smartguide/scraper/service/EnhancedScraperService.java`

**Change 1:** Add `CardEnrichmentService` as a new constructor-injected dependency.

```java
// Add to existing fields (line 33-36):
private final CardEnrichmentService cardEnrichmentService;
```

**Change 2:** Insert enrichment call in the configured-bank flow (between line 66 and line 70).

The existing code at lines 64-76:

```java
if (!structuredCards.isEmpty()) {
    List<ExtractedProduct> products = convertStructuredCards(structuredCards);
    List<ExtractedProduct> deduplicated = deduplicateByName(products);

    log.info("Configured extraction: saving {} product(s) to staging for bank='{}'",
            deduplicated.size(), config.bank());
    int savedCount = stagingProductService.saveExtractedProducts(
            deduplicated, url, configuredResult.getTextContent());
    // ...
}
```

Becomes:

```java
if (!structuredCards.isEmpty()) {
    List<ExtractedProduct> products = convertStructuredCards(structuredCards);
    List<ExtractedProduct> deduplicated = deduplicateByName(products);

    // NEW: AI enrichment before staging save
    log.info("Enriching {} product(s) with AI for bank='{}'", deduplicated.size(), config.bank());
    cardEnrichmentService.enrich(deduplicated, null, () -> false);

    log.info("Configured extraction: saving {} enriched product(s) to staging for bank='{}'",
            deduplicated.size(), config.bank());
    int savedCount = stagingProductService.saveExtractedProducts(
            deduplicated, url, configuredResult.getTextContent());
    // ... rest unchanged
}
```

Note: When called from the existing synchronous endpoint (`scrapeAndAnalyze`), the `progressCallback` is `null` and `cancellationFlag` always returns `false`. The async endpoint uses a `ScrapeJob`-aware callback and cancellation supplier.

**Change 3:** Add a new public method for the async scrape-and-enrich flow.

```java
/**
 * Asynchronous scrape-and-enrich flow with progress tracking.
 * Called by ScraperController for the new /api/scraper/scrape-and-enrich endpoint.
 *
 * @param url     the URL to scrape
 * @param job     the ScrapeJob for progress tracking
 */
public void scrapeAndEnrichAsync(String url, ScrapeJob job) {
    // This method is invoked on a background thread.
    // It follows the same logic as scrapeAndAnalyze() but with:
    // 1. Progress events emitted to job.getEvents()
    // 2. Cancellation checks between batches
    // 3. Job status updates on completion/failure

    try {
        Optional<BankSourceConfig> bankConfig = scraperProperties.findConfigForUrl(url);
        if (bankConfig.isPresent()) {
            BankSourceConfig config = bankConfig.get();
            ScrapeResponse configuredResult = basicScraperService.scrapeUrlWithConfig(url, config);

            if (!"success".equals(configuredResult.getStatus())) {
                job.setError(configuredResult.getMessage());
                job.setStatus(ScrapeJob.Status.FAILED);
                job.setPhase(EnrichmentProgressEvent.Phase.FAILED);
                job.setCompletedAt(LocalDateTime.now());
                return;
            }

            List<StructuredCard> structuredCards = configuredResult.getStructuredCards();
            if (structuredCards.isEmpty()) {
                // Fallback to generic flow
                job.addEvent(scrapingEvent("Configured extraction returned 0 cards. Falling back to generic flow."));
                ScrapeResponse result = runGenericFlow(url, configuredResult);
                completeJobFromSyncResult(job, result);
                return;
            }

            // Emit scraping-complete event
            job.addEvent(scrapingEvent("Found " + structuredCards.size() + " product cards via CSS selectors."));
            job.setPhase(EnrichmentProgressEvent.Phase.ENRICHING);

            List<ExtractedProduct> products = convertStructuredCards(structuredCards);
            List<ExtractedProduct> deduplicated = deduplicateByName(products);

            job.setTotalProducts(deduplicated.size());
            int batchSize = /* from config */;
            job.setTotalBatches((int) Math.ceil((double) deduplicated.size() / batchSize));

            // Enrich with progress callback
            Consumer<EnrichmentProgressEvent> callback = event -> {
                job.getEvents().add(event);
                job.setCompletedBatches(event.getBatchNumber());
                job.setProductsProcessed(event.getProductsProcessed());
            };

            cardEnrichmentService.enrich(deduplicated, callback, job::isCancelled);

            // Save to staging
            job.setPhase(EnrichmentProgressEvent.Phase.SAVING);
            job.addEvent(savingEvent("Saving " + deduplicated.size() + " enriched products to staging..."));

            int savedCount = stagingProductService.saveExtractedProducts(
                    deduplicated, url, configuredResult.getTextContent());

            // Compute summary counts
            job.setSavedCount(savedCount);
            computeSummaryCounts(job, deduplicated);
            job.setPhase(EnrichmentProgressEvent.Phase.DONE);
            job.setStatus(job.isCancelled() ? ScrapeJob.Status.CANCELLED : ScrapeJob.Status.COMPLETED);
            job.setCompletedAt(LocalDateTime.now());
        } else {
            // Generic flow (unchanged logic, wrapped with job tracking)
            // ...
        }
    } catch (Exception e) {
        job.setError(e.getMessage());
        job.setStatus(ScrapeJob.Status.FAILED);
        job.setPhase(EnrichmentProgressEvent.Phase.FAILED);
        job.setCompletedAt(LocalDateTime.now());
    }
}
```

### 8.2 ScraperController

**File:** `product-scraper-service/src/main/java/com/smartguide/scraper/controller/ScraperController.java`

**Add three new endpoints** (the existing endpoints are unchanged):

```java
private final ScrapeJobStore jobStore;

@PostMapping("/scrape-and-enrich")
@Operation(summary = "Start async scrape with AI enrichment (returns job ID)")
public ResponseEntity<?> scrapeAndEnrich(@RequestBody ScrapeRequest request) {
    // Check for duplicate in-flight job
    ScrapeJob existingJob = jobStore.getActiveJobForUrl(request.getUrl());
    if (existingJob != null) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "jobId", existingJob.getJobId(),
            "url", existingJob.getUrl(),
            "status", "IN_PROGRESS",
            "message", "A scrape job for this URL is already in progress"
        ));
    }

    ScrapeJob job = jobStore.createJob(request.getUrl());

    // Run asynchronously
    CompletableFuture.runAsync(() -> {
        try {
            enhancedScraperService.scrapeAndEnrichAsync(request.getUrl(), job);
        } catch (Exception e) {
            job.setError(e.getMessage());
            job.setStatus(ScrapeJob.Status.FAILED);
            job.setCompletedAt(LocalDateTime.now());
        }
    });

    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
        "jobId", job.getJobId(),
        "url", request.getUrl(),
        "status", "STARTED",
        "message", "Scrape and enrich job started"
    ));
}

@GetMapping("/jobs/{jobId}/status")
@Operation(summary = "Get status and progress of a scrape-and-enrich job")
public ResponseEntity<?> getJobStatus(@PathVariable String jobId) {
    ScrapeJob job = jobStore.getJob(jobId);
    if (job == null) {
        return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(job);
}

@DeleteMapping("/jobs/{jobId}")
@Operation(summary = "Cancel an in-progress scrape-and-enrich job")
public ResponseEntity<?> cancelJob(@PathVariable String jobId) {
    ScrapeJob job = jobStore.getJob(jobId);
    if (job == null) {
        return ResponseEntity.notFound().build();
    }
    if (!job.isActive()) {
        return ResponseEntity.badRequest().body(Map.of(
            "jobId", jobId,
            "status", job.getStatus().name(),
            "message", "Job is not active and cannot be cancelled"
        ));
    }
    jobStore.requestCancellation(jobId);
    return ResponseEntity.ok(Map.of(
        "jobId", jobId,
        "status", "CANCELLING",
        "message", "Cancellation requested. Already-processed products will be saved."
    ));
}
```

### 8.3 ExtractedProduct DTO

**File:** `product-scraper-service/src/main/java/com/smartguide/scraper/dto/ExtractedProduct.java`

**Add two new fields:**

```java
// Add after existing fields (line 40):
private List<String> keywords;       // AI-generated searchable keywords
private String reviewNotes;          // Pre-populated review notes (Sharia flags, enrichment failures)
```

These fields need to be carried through to the staging save.

### 8.4 StagingProductService (scraper service)

**File:** `product-scraper-service/src/main/java/com/smartguide/scraper/service/StagingProductService.java`

**Modify `buildStagingRequest()`** to include the new fields from `ExtractedProduct`:

```java
// Add after existing AI metadata block (after line 141):

// Keywords
if (product.getKeywords() != null && !product.getKeywords().isEmpty()) {
    data.put("keywords", product.getKeywords());
}

// Review notes (Sharia flags, enrichment failures)
if (product.getReviewNotes() != null && !product.getReviewNotes().isBlank()) {
    data.put("reviewNotes", product.getReviewNotes());
}

// sourceUrl blank-check safety net
String sourceUrl = product.getSourceUrl();
if (sourceUrl == null || sourceUrl.isBlank()) {
    sourceUrl = listingUrl;
    // Note: reviewNotes already populated by CardEnrichmentService if sourceUrl was missing
}
data.put("sourceUrl", sourceUrl);
```

Also, the existing line 144 that sets `sourceUrl` should be replaced by the above block.

### 8.5 application.yml (scraper service)

See Section 7 for the full YAML additions. The only change to the existing file is appending the `scraper.enrichment` block under the existing `scraper` key.

---

## 9. Test Scenarios

### 9.1 CardEnrichmentService -- Unit Tests

Create: `product-scraper-service/src/test/java/com/smartguide/scraper/service/CardEnrichmentServiceTest.java`

| # | Test name | Description |
|---|---|---|
| 1 | `enrich_batchMode_enrichesAllProducts` | Given 14 products and batchSize=7, verify 2 LLM calls are made, all products receive category/keywords/confidence. |
| 2 | `enrich_sequentialMode_enrichesAllProducts` | Given 3 products in SEQUENTIAL mode, verify 3 LLM calls, each product enriched independently. |
| 3 | `enrich_emptyList_returnsEmpty` | Given empty list, no LLM call made, returns empty list. |
| 4 | `enrich_nullList_returnsEmpty` | Given null, returns empty list without NPE. |
| 5 | `enrich_batchMode_oneBatchFails_otherBatchesSucceed` | Mock LLM to fail on batch 2, succeed on batches 1 and 3. Verify batch 2 products have confidence=0 and review note; batches 1 and 3 are enriched. |
| 6 | `enrich_allBatchesFail_productsMarkedFailed` | Mock all LLM calls to throw. All products should have confidence=0 and review note. |
| 7 | `enrich_malformedJson_fallbackSalvage` | Mock LLM to return a truncated JSON array. Verify salvageable objects are parsed; remainder are marked failed. |
| 8 | `enrich_invalidCategory_clearedWithNote` | Mock LLM to return `"category": "MORTGAGE"`. Verify category is set to null and review note mentions the invalid value. |
| 9 | `enrich_confidenceOutOfRange_clamped` | Mock LLM to return confidence=1.5. Verify it is clamped to 1.0. |
| 10 | `enrich_confidenceNegative_clamped` | Mock LLM to return confidence=-0.3. Verify it is clamped to 0.0. |
| 11 | `enrich_shariaViolationDetected_flaggedInReviewNotes` | Give a product with description containing "interest rate". Verify reviewNotes contains the Sharia compliance warning. |
| 12 | `enrich_shariaViolationInBenefits_flaggedInReviewNotes` | Give a product with a benefit containing "insurance included". Verify reviewNotes mentions "insurance" -> "Takaful". |
| 13 | `enrich_noShariaViolation_noReviewNotes` | Give a clean product. Verify reviewNotes is null. |
| 14 | `enrich_llmTimeout_retriedOnce_thenFailed` | Mock LLM to timeout twice. Verify retry occurred, then product marked as failed. |
| 15 | `enrich_llmTimeout_retriedOnce_secondAttemptSucceeds` | Mock LLM to timeout once, then succeed. Verify product is enriched. |
| 16 | `enrich_cancellation_stopsAfterCurrentBatch` | Given 3 batches, set cancellation flag after batch 1 completes. Verify only batch 1 products are enriched, batches 2-3 are not processed. |
| 17 | `enrich_progressCallbackInvoked` | Verify the progress callback receives one event per batch with correct batch number, totalBatches, productsProcessed. |
| 18 | `buildBatchPrompt_includesAllProductNames` | Verify the prompt string contains all product names and benefits from the batch. |
| 19 | `buildSequentialPrompt_includesSingleProduct` | Verify the prompt contains the single product's name, description, and benefits. |
| 20 | `validateCategory_validCategory_unchanged` | Given `COVERED_CARDS`, verify it passes validation unchanged. |
| 21 | `validateCategory_nullCategory_remains_null` | Given null category, verify no error (it stays null, already handled by markFailed earlier). |

### 9.2 ScrapeJobStore -- Unit Tests

Create: `product-scraper-service/src/test/java/com/smartguide/scraper/service/ScrapeJobStoreTest.java`

| # | Test name | Description |
|---|---|---|
| 1 | `createJob_returnsNewJob` | Verify a new job is created with IN_PROGRESS status and a generated job ID. |
| 2 | `createJob_duplicateUrl_returnsNull` | Create a job for URL X, then try creating another for URL X. Verify second call returns null. |
| 3 | `createJob_duplicateUrl_afterCompletion_succeeds` | Create, complete, then create again for same URL. Verify second creation succeeds. |
| 4 | `getJob_existingJob_returnsJob` | Create a job, verify `getJob(jobId)` returns it. |
| 5 | `getJob_unknownId_returnsNull` | Verify `getJob("nonexistent")` returns null. |
| 6 | `requestCancellation_setsFlag` | Create a job, request cancellation, verify `isCancelled()` returns true. |
| 7 | `addEvent_appendsToList` | Create a job, add 3 events, verify all 3 are in `job.getEvents()`. |
| 8 | `completeJob_removesFromActiveUrlIndex` | Complete a job, verify same URL can be used for a new job. |

### 9.3 ScraperController -- Integration Tests

Create: `product-scraper-service/src/test/java/com/smartguide/scraper/controller/ScraperControllerEnrichmentTest.java`

| # | Test name | Description |
|---|---|---|
| 1 | `scrapeAndEnrich_returns202WithJobId` | POST `/api/scraper/scrape-and-enrich`, verify 202 response with jobId. |
| 2 | `scrapeAndEnrich_duplicateUrl_returns409` | Start a job, immediately POST again for same URL, verify 409 with existing jobId. |
| 3 | `getJobStatus_unknownJobId_returns404` | GET `/api/scraper/jobs/nonexistent/status`, verify 404. |
| 4 | `getJobStatus_existingJob_returns200` | Start a job, GET its status, verify 200 with IN_PROGRESS or COMPLETED. |
| 5 | `cancelJob_activeJob_returns200` | Start a job, DELETE it, verify 200 with CANCELLING status. |
| 6 | `cancelJob_completedJob_returns400` | Start and wait for completion, DELETE it, verify 400 "not active". |
| 7 | `cancelJob_unknownJob_returns404` | DELETE `/api/scraper/jobs/nonexistent`, verify 404. |

### 9.4 LLM Response Parsing -- Unit Tests (in CardEnrichmentServiceTest)

| # | Test name | Description |
|---|---|---|
| 1 | `parseBatchResponse_validJson_returnsAllCards` | Given well-formed JSON array with 3 objects, verify 3 `EnrichedCard` objects returned. |
| 2 | `parseBatchResponse_markdownWrapped_strippedAndParsed` | Given `` ```json [...] ``` ``, verify fences stripped, content parsed. |
| 3 | `parseBatchResponse_truncatedArray_salvagesComplete` | Given `[{...}, {... (truncated)`, verify first complete object is salvaged. |
| 4 | `parseBatchResponse_emptyResponse_returnsEmpty` | Given `""`, returns empty list. |
| 5 | `parseBatchResponse_noJsonArray_returnsEmpty` | Given prose with no `[`, returns empty list. |
| 6 | `parseSequentialResponse_validJson_returnsCard` | Given `{...}`, verify single `EnrichedCard` returned. |
| 7 | `parseSequentialResponse_extraFields_ignored` | Given JSON with extra fields, verify no exception and known fields parsed. |
| 8 | `parseBatchResponse_singleQuotes_parsed` | Given JSON with single quotes (common LLM output), verify lenient parser handles it. |

---

## Appendix A: Sharia Compliance Check

The existing `AIProductExtractor.buildExtractionPrompt()` (line 274) already instructs the LLM to use Sharia-compliant terms. However, for CSS-extracted cards, the source text comes directly from the bank's website -- which may itself use conventional terminology (e.g., "interest rates" on a bank page).

The enrichment pipeline handles this at two levels:

1. **In the LLM prompt** (Section 4): The LLM is instructed to flag violations and report them in the `sharia_violations` array.
2. **In post-processing** (Section 5, item n): `checkShariaCompliance()` scans the raw text fields independently as a safety net, because the LLM may miss violations or hallucinate clean results.

Both layers contribute to the `reviewNotes` field, which the admin sees in the staging review UI.

## Appendix B: Existing endpoints preserved

| Endpoint | Change |
|---|---|
| `POST /api/scraper/scrape-url` | None |
| `POST /api/scraper/scrape-url-enhanced` | The underlying `EnhancedScraperService.scrapeAndAnalyze()` now includes enrichment in the configured-bank path. This is an improvement, not a breaking change -- the response shape is identical, but `category`/`keywords`/`aiConfidence` are now populated. |
| `POST /api/scraper/trigger/{websiteId}` | None |
| `GET /api/scraper/status/{jobId}` | None (this is the old job status endpoint, unrelated to the new `/jobs/{jobId}/status`) |
| All main-service endpoints | None |

## Appendix C: Files inventory

### New files to create

| File | Purpose |
|---|---|
| `product-scraper-service/src/main/java/com/smartguide/scraper/service/CardEnrichmentService.java` | Core enrichment logic (batch + sequential modes) |
| `product-scraper-service/src/main/java/com/smartguide/scraper/service/ScrapeJobStore.java` | In-memory async job state management |
| `product-scraper-service/src/main/java/com/smartguide/scraper/config/EnrichmentConfig.java` | YAML-bound enrichment configuration |
| `product-scraper-service/src/main/java/com/smartguide/scraper/dto/EnrichmentProgressEvent.java` | Progress event DTO |
| `product-scraper-service/src/main/java/com/smartguide/scraper/dto/EnrichedCard.java` | LLM enrichment result DTO |
| `product-scraper-service/src/main/java/com/smartguide/scraper/dto/ScrapeJob.java` | Job state DTO |
| `product-scraper-service/src/test/java/com/smartguide/scraper/service/CardEnrichmentServiceTest.java` | Unit tests |
| `product-scraper-service/src/test/java/com/smartguide/scraper/service/ScrapeJobStoreTest.java` | Unit tests |
| `product-scraper-service/src/test/java/com/smartguide/scraper/controller/ScraperControllerEnrichmentTest.java` | Integration tests |

### Existing files to modify

| File | Changes |
|---|---|
| `product-scraper-service/src/main/java/com/smartguide/scraper/service/EnhancedScraperService.java` | Add `CardEnrichmentService` dependency; insert enrichment call in configured-bank flow; add `scrapeAndEnrichAsync()` method |
| `product-scraper-service/src/main/java/com/smartguide/scraper/controller/ScraperController.java` | Add `ScrapeJobStore` dependency; add 3 new endpoints |
| `product-scraper-service/src/main/java/com/smartguide/scraper/dto/ExtractedProduct.java` | Add `keywords` and `reviewNotes` fields |
| `product-scraper-service/src/main/java/com/smartguide/scraper/service/StagingProductService.java` | Pass `keywords` and `reviewNotes` to staging API; add sourceUrl blank-check |
| `product-scraper-service/src/main/resources/application.yml` | Add `scraper.enrichment.*` config block |
| `frontend/src/services/api.js` | Add `scrapeAndEnrich()`, `getEnrichmentJobStatus()`, `cancelEnrichmentJob()` |
| `frontend/src/pages/ScrapeForm.jsx` | Replace spinner with MUI Stepper + progress bar + live results table (full UI redesign of this component) |
