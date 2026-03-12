# Gap Report & Sprint Planner
> Generated: 2026-02-24
> Source: /analyze-gaps
> Re-run this command at any time to refresh. Completed sprint statuses are preserved across runs.

---

## P1 — Security

### SEC-1: JWT Token Validation
- **Status**: ❌ MISSING
- **Current State**: Only API key auth exists (`security/ApiKeyAuthenticationFilter.java`, `ApiKeyService.java`). Keys are validated from in-memory cache loaded from `application.yml`. No JWT/OAuth2 integration.
- **Gap**: No JWT validation against the bank's OAuth server. No `spring-security-oauth2-resource-server` dependency. No `JwtDecoder` bean or JWKS endpoint configuration.
- **Files to Modify**: `pom.xml`, `src/main/java/com/smartguide/poc/security/SecurityConfig.java`
- **Files to Create**: `src/main/java/com/smartguide/poc/security/JwtAuthenticationConverter.java`, `src/main/java/com/smartguide/poc/config/OAuth2Config.java`
- **Migration**: n/a
- **Complexity**: L
- **Dependencies**: none
- **Priority**: P1

---

### SEC-2: Role-Based Access Control (Reviewer / Approver / Super Admin)
- **Status**: 🟡 PARTIAL
- **Current State**: `ApiKeyScope` enum has ADMIN/USER scopes. `SecurityConfig` uses `SCOPE_admin:*` for admin endpoints. `@EnableMethodSecurity` is active.
- **Gap**: No Reviewer/Approver/Super Admin role hierarchy. All admin API keys get the same full admin access. No `@PreAuthorize` annotations on individual endpoints. Admin dashboard has no role enforcement per action (e.g., only Approver can approve, Reviewer can only flag).
- **Files to Modify**: `src/main/java/com/smartguide/poc/security/ApiKeyScope.java`, `src/main/java/com/smartguide/poc/admin/controller/AdminStagingController.java`, `src/main/java/com/smartguide/poc/security/SecurityConfig.java`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: SEC-1
- **Priority**: P1

---

### SEC-3: Encryption at Rest and TLS
- **Status**: 🟡 PARTIAL
- **Current State**: TLS is infrastructure-level (not application-level in this codebase). No AES-256 at-rest encryption configured for database columns. Sensitive fields stored as plain text.
- **Gap**: No column-level encryption for any sensitive fields. No TLS enforcement in `application.yml` (`server.ssl.*` not configured). Docker compose uses plain postgres with no ssl mode.
- **Files to Modify**: `src/main/resources/application.yml`, `docker-compose.yml`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: none
- **Priority**: P1

---

### SEC-4: No Customer PII Collected or Stored
- **Status**: 🟡 PARTIAL
- **Current State**: No explicit customer profile tables. `UserContext` DTO accepts `minIncome`, `creditScore`, `age`, `currentProducts` but these are request-scoped (not persisted).
- **Gap**: `staging_products.reviewed_by` stores admin username (acceptable). `RecommendationRequest` accepts income/credit score in request body — verify these are never logged at DEBUG level. SLF4J debug logs in `RecommendationController` log the user input which could contain personal information.
- **Files to Modify**: `src/main/java/com/smartguide/poc/controller/RecommendationController.java`, `src/main/java/com/smartguide/poc/service/LLMService.java`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P1

---

### SEC-5: API Rate Limiting
- **Status**: ❌ MISSING
- **Current State**: No rate limiting anywhere in the codebase or configuration. No gateway-level throttling configured.
- **Gap**: No `@RateLimiter` annotations, no Bucket4j dependency, no gateway config. Per the requirements this should be at gateway level — no gateway (nginx/API Gateway) config exists.
- **Files to Modify**: `pom.xml`
- **Files to Create**: `src/main/java/com/smartguide/poc/config/RateLimitingConfig.java`
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: none
- **Priority**: P1

---

### SEC-6: Input Validation and Sanitization
- **Status**: 🟡 PARTIAL
- **Current State**: `@Valid` on `RecommendationRequest` in `RecommendationController`. `GlobalExceptionHandler` handles `MethodArgumentNotValidException`. Bean Validation annotations on DTOs.
- **Gap**: No HTML/script sanitization (XSS prevention) on free-text `userInput` field. No max-length enforcement on scraper URL inputs. `UrlValidator.java` exists in scraper but not wired into main app. No sanitization before passing user input to LLM prompts (prompt injection risk).
- **Files to Modify**: `src/main/java/com/smartguide/poc/dto/RecommendationRequest.java`, `src/main/java/com/smartguide/poc/service/LLMService.java`
- **Files to Create**: `src/main/java/com/smartguide/poc/util/InputSanitizer.java`
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P1

---

### SEC-7: SQL Injection Prevention
- **Status**: ✅ DONE
- **Current State**: `ProductService.queryProducts()` uses JPA `CriteriaBuilder` with parameterized predicates. All repository methods use Spring Data JPA. No native queries found with string concatenation. Flyway migrations use static SQL.
- **Gap**: None — parameterized queries used throughout.
- **Files to Modify**: n/a
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P1

---

### SEC-8: Secrets Management
- **Status**: 🟡 PARTIAL
- **Current State**: `application.yml` uses `${ENV_VAR:default}` pattern. `spring-dotenv` dependency loaded for `.env` files. No hardcoded keys found in main app code. Azure OpenAI API key loaded from env.
- **Gap**: API keys stored as plain env vars (no HashiCorp Vault or Azure Key Vault integration). Default values in `application.yml` (`username: postgres`, `password: postgres`) would be used if env vars missing. `CorsConfig` hardcodes localhost URLs. No secret rotation mechanism.
- **Files to Modify**: `src/main/resources/application.yml`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: none
- **Priority**: P1

---

### SEC-9: CORS Restricted to Bank Domains
- **Status**: ❌ MISSING
- **Current State**: `CorsConfig.java` allows only `http://localhost:3000` and `http://localhost:3001`. This is dev-mode only.
- **Gap**: No production bank domain configured. `allowedHeaders("*")` and `exposedHeaders("*")` are too permissive for production. No profile-based CORS config (dev vs prod). The bank's actual mobile app and internet banking domains are not configured.
- **Files to Modify**: `src/main/java/com/smartguide/poc/config/CorsConfig.java`, `src/main/resources/application.yml`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P1

---

### SEC-10: Security Headers (CSP, X-Frame-Options, HSTS)
- **Status**: ❌ MISSING
- **Current State**: `SecurityConfig` disables CSRF but adds no security headers. No `HeadersConfigurer` usage. No Content Security Policy, X-Frame-Options, or HSTS headers returned.
- **Gap**: Spring Security's default header configuration is not enabled. No `http.headers()` block in `SecurityConfig`. Missing: `Content-Security-Policy`, `X-Frame-Options: DENY`, `Strict-Transport-Security`, `X-Content-Type-Options`.
- **Files to Modify**: `src/main/java/com/smartguide/poc/security/SecurityConfig.java`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P1

---

## P2 — Test Coverage

### TEST-1: Unit Tests — Service Classes
- **Status**: ❌ MISSING
- **Current State**: No test files found in `src/test/`. H2 and `reactor-test` dependencies exist in pom.xml (test scope). 0% coverage.
- **Gap**: No unit tests for `LLMService`, `ProductService`, `RulesEngine`, `StagingProductService`. No mocking of WebClient for LLM calls.
- **Files to Modify**: n/a
- **Files to Create**: `src/test/java/com/smartguide/poc/service/LLMServiceTest.java`, `src/test/java/com/smartguide/poc/service/ProductServiceTest.java`, `src/test/java/com/smartguide/poc/service/RulesEngineTest.java`, `src/test/java/com/smartguide/poc/admin/service/StagingProductServiceTest.java`
- **Migration**: n/a
- **Complexity**: L
- **Dependencies**: none
- **Priority**: P1

---

### TEST-2: Unit Tests — Controllers (MockMvc)
- **Status**: ❌ MISSING
- **Current State**: No controller tests exist.
- **Gap**: No MockMvc tests for `RecommendationController`, `ProductController`, `AdminStagingController`, `HealthController`.
- **Files to Modify**: n/a
- **Files to Create**: `src/test/java/com/smartguide/poc/controller/RecommendationControllerTest.java`, `src/test/java/com/smartguide/poc/controller/ProductControllerTest.java`, `src/test/java/com/smartguide/poc/admin/controller/AdminStagingControllerTest.java`
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: TEST-1
- **Priority**: P1

---

### TEST-3: Integration Tests — Testcontainers + PostgreSQL
- **Status**: ❌ MISSING
- **Current State**: Only H2 in-memory db dependency exists. No Testcontainers dependency.
- **Gap**: No Testcontainers setup. No `@DataJpaTest` with PostgreSQL container. No repository-level integration tests.
- **Files to Modify**: `pom.xml`
- **Files to Create**: `src/test/java/com/smartguide/poc/repository/ProductRepositoryIntegrationTest.java`, `src/test/java/com/smartguide/poc/repository/StagingProductRepositoryIntegrationTest.java`
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: none
- **Priority**: P1

---

### TEST-4: End-to-End Recommendation Flow Test
- **Status**: ❌ MISSING
- **Current State**: No E2E tests exist.
- **Gap**: No test covering the full flow: query → LLM intent extraction → RulesEngine category mapping → ProductService ranking → response.
- **Files to Modify**: n/a
- **Files to Create**: `src/test/java/com/smartguide/poc/e2e/RecommendationFlowTest.java`
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: TEST-3
- **Priority**: P2

---

### TEST-5: Sharia Compliance Test
- **Status**: ❌ MISSING
- **Current State**: No automated Sharia term scanning in test suite.
- **Gap**: No test that scans all Java source files, SQL migrations, and AI prompt strings for forbidden terms (interest, loan, mortgage, insurance, conventional).
- **Files to Modify**: n/a
- **Files to Create**: `src/test/java/com/smartguide/poc/compliance/ShariaCcomplianceTest.java`
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P1

---

### TEST-6: API Contract Tests
- **Status**: ❌ MISSING
- **Current State**: OpenAPI spec exists (Springdoc) but no contract verification tests.
- **Gap**: No Spring Cloud Contract or REST Assured tests verifying response shape matches OpenAPI spec.
- **Files to Modify**: n/a
- **Files to Create**: `src/test/java/com/smartguide/poc/contract/RecommendationApiContractTest.java`
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: TEST-2
- **Priority**: P2

---

### TEST-7: Flyway Migration Tests
- **Status**: ❌ MISSING
- **Current State**: No migration tests. Flyway runs on startup but no isolated test for clean-apply.
- **Gap**: No test that applies all migrations V1–V10 on an empty PostgreSQL container and validates schema state.
- **Files to Modify**: n/a
- **Files to Create**: `src/test/java/com/smartguide/poc/migration/FlywayMigrationTest.java`
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: TEST-3
- **Priority**: P2

---

### TEST-8: Error Handling Tests
- **Status**: ❌ MISSING
- **Current State**: `GlobalExceptionHandler` exists and handles multiple exception types. No tests verify correct HTTP status codes and response shapes.
- **Gap**: No tests for 400 (validation), 401 (missing/invalid API key), 403 (insufficient scope), 500 (LLM failure) responses.
- **Files to Modify**: n/a
- **Files to Create**: `src/test/java/com/smartguide/poc/controller/ErrorHandlingTest.java`
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: TEST-2
- **Priority**: P2

---

### TEST-9: Coverage Target Tracking
- **Status**: ❌ MISSING
- **Current State**: No JaCoCo plugin in pom.xml. No coverage reports generated.
- **Gap**: No JaCoCo configuration. No coverage threshold enforcement. Current coverage: 0%.
- **Files to Modify**: `pom.xml`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: TEST-1
- **Priority**: P2

---

## P3 — Bilingual Support

### I18N-1: Bilingual Database Columns (_ar variants)
- **Status**: ❌ MISSING
- **Current State**: All text columns (product_name, description, key_benefits, keywords) are English-only. No `_ar` columns in any migration.
- **Gap**: No Arabic column variants in `products` or `staging_products` tables. No `_en`/`_ar` suffix pattern established.
- **Files to Modify**: `src/main/java/com/smartguide/poc/entity/Product.java`, `src/main/java/com/smartguide/poc/entity/StagingProduct.java`
- **Files to Create**: n/a
- **Migration**: `V11__Add_bilingual_columns.sql`
- **Complexity**: M
- **Dependencies**: DATA-2
- **Priority**: P2

---

### I18N-2: Scraper — Bilingual Page Scraping
- **Status**: ❌ MISSING
- **Current State**: Scraper scrapes a single URL. `TriggerScrapeRequest` has no language parameter.
- **Gap**: No mechanism to scrape Arabic product pages. Arabic URLs not stored in `scrape_sources`. No language routing in `EnhancedScraperService`.
- **Files to Modify**: `product-scraper-service/src/main/java/com/smartguide/scraper/service/EnhancedScraperService.java`, `product-scraper-service/src/main/java/com/smartguide/scraper/dto/TriggerScrapeRequest.java`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: L
- **Dependencies**: I18N-1
- **Priority**: P2

---

### I18N-3: AI Enrichment in Both Languages
- **Status**: ❌ MISSING
- **Current State**: `LLMService` keyword generation and intent extraction handle a `language` parameter but generate English output only.
- **Gap**: No Arabic keyword generation prompt. No bilingual summary generation. LLM prompts don't instruct Arabic output for AR requests.
- **Files to Modify**: `src/main/java/com/smartguide/poc/service/LLMService.java`, `src/main/resources/application.yml`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: I18N-1
- **Priority**: P2

---

### I18N-4: API Language Detection and Routing
- **Status**: 🟡 PARTIAL
- **Current State**: `RecommendationRequest` has a `language` field. `LLMService.extractIntent()` receives language. But response is always in English regardless of language.
- **Gap**: Language is passed but not used to select Arabic product data or generate Arabic explanations. No `lang=auto` detection based on script analysis of user input. No Arabic response fields in `RecommendationResponse`.
- **Files to Modify**: `src/main/java/com/smartguide/poc/controller/RecommendationController.java`, `src/main/java/com/smartguide/poc/dto/RecommendationResponse.java`, `src/main/java/com/smartguide/poc/dto/ProductRecommendation.java`
- **Files to Create**: `src/main/java/com/smartguide/poc/util/LanguageDetector.java`
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: I18N-1, I18N-3
- **Priority**: P2

---

### I18N-5: Admin Dashboard EN/AR Toggle with RTL
- **Status**: ❌ MISSING
- **Current State**: React frontend is English-only. No RTL support. No language toggle component.
- **Gap**: No i18n library (react-i18next) integrated. No Arabic translations for UI labels. No RTL CSS/MUI direction switching.
- **Files to Modify**: `frontend/src/App.jsx`
- **Files to Create**: `frontend/src/i18n/ar.json`, `frontend/src/i18n/en.json`, `frontend/src/components/LanguageToggle.jsx`
- **Migration**: n/a
- **Complexity**: L
- **Dependencies**: I18N-1
- **Priority**: P3

---

### I18N-6: Modern Standard Arabic (فصحى)
- **Status**: ❌ MISSING
- **Current State**: No Arabic content anywhere in the system.
- **Gap**: When Arabic content is generated (I18N-3), LLM prompts must explicitly require Modern Standard Arabic (MSA/فصحى), not colloquial dialect. This is a prompt engineering requirement.
- **Files to Modify**: `src/main/java/com/smartguide/poc/service/LLMService.java`, `src/main/resources/application.yml`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: I18N-3
- **Priority**: P2

---

### I18N-7: Standard Arabic Islamic Finance Terms
- **Status**: ❌ MISSING
- **Current State**: Islamic contract names (Murabaha, Tawarruq, etc.) stored in English transliteration only.
- **Gap**: No Arabic script equivalents (مرابحة, تورّق, إجارة, وكالة, مضاربة, تكافل) stored or used anywhere. LLM prompts don't enforce Arabic terminology.
- **Files to Modify**: `src/main/java/com/smartguide/poc/service/LLMService.java`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: I18N-6
- **Priority**: P2

---

## P4 — AI & Search Upgrades

### AI-1: Vector Embeddings (pgvector)
- **Status**: ❌ MISSING
- **Current State**: PostgreSQL 15 used but without pgvector extension. No embedding columns in `products`. No embedding generation in any service.
- **Gap**: No `CREATE EXTENSION vector` in migrations. No `embedding_vector_en` / `embedding_vector_ar` columns. No embedding model integration. No vector similarity search.
- **Files to Modify**: `pom.xml`, `src/main/java/com/smartguide/poc/entity/Product.java`, `src/main/java/com/smartguide/poc/entity/StagingProduct.java`
- **Files to Create**: `src/main/java/com/smartguide/poc/service/EmbeddingService.java`
- **Migration**: `V11__Add_pgvector_and_embeddings.sql`
- **Complexity**: XL
- **Dependencies**: DATA-3
- **Priority**: P2

---

### AI-2: Hybrid Search (Vector + Keyword + Structured)
- **Status**: ❌ MISSING
- **Current State**: `ProductService.queryProducts()` uses pure structured filtering (category, income, credit score). `checkSpecificKeywordMatch()` does basic string matching.
- **Gap**: No vector similarity scoring. No weighted hybrid scoring combining semantic score + keyword match + structured filter. No pgvector `<=>` operator usage.
- **Files to Modify**: `src/main/java/com/smartguide/poc/service/ProductService.java`, `src/main/java/com/smartguide/poc/repository/ProductRepository.java`
- **Files to Create**: `src/main/java/com/smartguide/poc/service/HybridSearchService.java`
- **Migration**: n/a (depends on AI-1 migration)
- **Complexity**: XL
- **Dependencies**: AI-1
- **Priority**: P2

---

### AI-3: Bilingual Embedding Model
- **Status**: ❌ MISSING
- **Current State**: No embedding model integrated.
- **Gap**: No evaluation or integration of Voyage AI or multilingual-e5-large for Arabic+English embeddings. Choice of model is a key architectural decision.
- **Files to Modify**: `pom.xml`, `src/main/resources/application.yml`
- **Files to Create**: `src/main/java/com/smartguide/poc/config/EmbeddingConfig.java`
- **Migration**: n/a
- **Complexity**: L
- **Dependencies**: AI-1
- **Priority**: P2

---

### AI-4: Sharia Terminology Validation on AI Output
- **Status**: ❌ MISSING
- **Current State**: No automated scan of AI-generated content before storage. Keywords and summaries are stored as-is from LLM responses.
- **Gap**: No post-processing validation step in `LLMService` or `StagingProductService` that checks generated content for forbidden terms. No rejection/flagging mechanism.
- **Files to Modify**: `src/main/java/com/smartguide/poc/service/LLMService.java`, `src/main/java/com/smartguide/poc/admin/service/StagingProductService.java`
- **Files to Create**: `src/main/java/com/smartguide/poc/util/ShariaCcomplianceChecker.java`
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P1

---

### AI-5: Sharia Guidelines Baked Into All LLM Prompts
- **Status**: 🟡 PARTIAL
- **Current State**: `application.yml` keyword system prompt has some Islamic banking instructions. But `SYSTEM_PROMPT` in `LLMService` uses "LOAN", "INSURANCE", "travel insurance", "car loan" — explicit Sharia violations in the system prompt sent to the LLM.
- **Gap**: Main intent extraction system prompt uses forbidden terms: "LOAN" (line 35), "INSURANCE" (line 38), "car loan" (line 48, 54). These must be replaced with "FINANCE", "TAKAFUL", "auto finance" respectively. Keyword generation prompt is acceptable.
- **Files to Modify**: `src/main/java/com/smartguide/poc/service/LLMService.java`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P1

---

### AI-6: AI-Generated Match Explanation (2-3 sentences)
- **Status**: 🟡 PARTIAL
- **Current State**: `reason` field exists in `ProductRecommendation`. LLM ranking in `rankProductsWithLLM()` returns a `reason` string. Formula-based fallback uses template strings (`generateReason()` method).
- **Gap**: Template-based reasons are generic (e.g., "Perfect for travelers with Murabaha structure - ..."). Full AI-generated personalized explanations only work when LLM ranking is enabled. When LLM is disabled or fails, generic templates are used. No minimum 2-3 sentence enforcement.
- **Files to Modify**: `src/main/java/com/smartguide/poc/service/LLMService.java`, `src/main/java/com/smartguide/poc/service/ProductService.java`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: AI-5
- **Priority**: P2

---

### AI-7: Query Intent Classification (Recommendation vs Comparison vs Lookup)
- **Status**: 🟡 PARTIAL
- **Current State**: `LLMService.extractIntent()` classifies into product-type intents (TRAVEL, SAVINGS, CAR, etc.) but not query-type (recommendation vs comparison vs lookup vs ambiguous).
- **Gap**: No `query_type` field in intent response. Comparison queries and product lookup queries get treated as recommendation requests. No routing based on query type.
- **Files to Modify**: `src/main/java/com/smartguide/poc/service/LLMService.java`, `src/main/java/com/smartguide/poc/dto/IntentData.java`, `src/main/java/com/smartguide/poc/controller/RecommendationController.java`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: none
- **Priority**: P2

---

### AI-8: Cross-Sell Suggestions
- **Status**: ❌ MISSING
- **Current State**: No complementary product recommendation logic. Each recommendation is independent.
- **Gap**: No cross-sell engine. No `complementary_products` mapping table. No logic to suggest Takaful alongside auto finance, or savings account alongside current account.
- **Files to Modify**: `src/main/java/com/smartguide/poc/service/ProductService.java`
- **Files to Create**: `src/main/java/com/smartguide/poc/service/CrossSellService.java`
- **Migration**: n/a
- **Complexity**: L
- **Dependencies**: AI-7
- **Priority**: P3

---

### AI-9: Graceful Degradation (LLM Unavailable)
- **Status**: 🟡 PARTIAL
- **Current State**: `ProductService.rankProducts()` catches LLM ranking exceptions and falls back to formula-based results. `LLMService.extractIntent()` has a `getFallbackIntent()` keyword-based fallback.
- **Gap**: Fallback intent detection is basic keyword matching (e.g., "mortgage" in fallback list — Sharia violation). Formula-based ranking relies on `userInput` being present. No pre-computed summary field for zero-LLM serving. No circuit breaker — if LLM is slow (not failed), it blocks for the full timeout.
- **Files to Modify**: `src/main/java/com/smartguide/poc/service/LLMService.java`, `src/main/java/com/smartguide/poc/service/ProductService.java`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: OBS-6
- **Priority**: P2

---

## P5 — API Enhancements

### API-1: Comparison Endpoint (POST /api/v1/compare)
- **Status**: ❌ MISSING
- **Current State**: No comparison endpoint. `RecommendationController` only has `POST /api/v1/recommend`.
- **Gap**: No `POST /api/v1/compare` accepting 2-5 product IDs and returning structured feature comparison table.
- **Files to Modify**: n/a
- **Files to Create**: `src/main/java/com/smartguide/poc/controller/ComparisonController.java`, `src/main/java/com/smartguide/poc/dto/ComparisonRequest.java`, `src/main/java/com/smartguide/poc/dto/ComparisonResponse.java`, `src/main/java/com/smartguide/poc/service/ComparisonService.java`
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: none
- **Priority**: P2

---

### API-2: Product Detail Endpoint with Language Support
- **Status**: 🟡 PARTIAL
- **Current State**: `GET /api/products/{id}` exists in `ProductController`. Returns full product entity.
- **Gap**: No `lang` parameter. Returns same English data regardless of language. No bilingual field selection. Not under `/api/v1/` versioned path (admin endpoint, not public).
- **Files to Modify**: `src/main/java/com/smartguide/poc/controller/ProductController.java`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: I18N-1
- **Priority**: P2

---

### API-3: Categories Endpoint (GET /api/v1/categories)
- **Status**: ❌ MISSING
- **Current State**: No categories listing endpoint under `/api/v1/`.
- **Gap**: No `GET /api/v1/categories` endpoint with product counts per category in requested language.
- **Files to Modify**: `src/main/java/com/smartguide/poc/controller/RecommendationController.java` (or new controller)
- **Files to Create**: `src/main/java/com/smartguide/poc/dto/CategoryResponse.java`
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P2

---

### API-4: Feedback Endpoint (POST /api/v1/feedback)
- **Status**: ❌ MISSING
- **Current State**: No feedback endpoint. No feedback table.
- **Gap**: No `POST /api/v1/feedback` for click tracking and thumbs up/down. No `recommendation_feedback` table.
- **Files to Modify**: n/a
- **Files to Create**: `src/main/java/com/smartguide/poc/controller/FeedbackController.java`, `src/main/java/com/smartguide/poc/dto/FeedbackRequest.java`, `src/main/java/com/smartguide/poc/entity/RecommendationFeedback.java`, `src/main/java/com/smartguide/poc/service/FeedbackService.java`
- **Migration**: `V12__Add_feedback_and_log_tables.sql`
- **Complexity**: M
- **Dependencies**: DATA-5, DATA-6
- **Priority**: P2

---

### API-5: Language Parameter on All Endpoints
- **Status**: 🟡 PARTIAL
- **Current State**: `RecommendationRequest` has `language` field. No other endpoints accept language.
- **Gap**: `GET /api/v1/categories`, `POST /api/v1/compare`, `GET /api/v1/products/{id}` need `lang=en|ar|auto` query parameter. No auto-detection based on `Accept-Language` header.
- **Files to Modify**: multiple controllers (after API-1, API-3 created)
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: I18N-4, API-1, API-3
- **Priority**: P2

---

### API-6: Consistent Error Response Format
- **Status**: 🟡 PARTIAL
- **Current State**: `GlobalExceptionHandler` returns `ErrorResponse` for most cases. `RecommendationController` has its own error handling returning 500 with a `RecommendationResponse` (not `ErrorResponse`).
- **Gap**: Recommendation errors return `RecommendationResponse` with `status: "error"` — inconsistent with `ErrorResponse` from `GlobalExceptionHandler`. LLM timeout errors may return different shapes.
- **Files to Modify**: `src/main/java/com/smartguide/poc/controller/RecommendationController.java`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P2

---

### API-7: API Versioning Strategy
- **Status**: 🟡 PARTIAL
- **Current State**: `POST /api/v1/recommend` is versioned. Product and admin endpoints are `/api/products/**` and `/api/admin/**` — no version prefix.
- **Gap**: No documented versioning strategy. Admin and product endpoints not under `/api/v1/`. No clear path for v2. No deprecation headers.
- **Files to Modify**: n/a (documentation/architecture decision)
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P3

---

### API-8: Complete OpenAPI 3.0 Spec
- **Status**: 🟡 PARTIAL
- **Current State**: `springdoc-openapi-starter-webmvc-ui 2.3.0` configured. `RecommendationController` has `@Tag`, `@Operation`, `@ApiResponses`. Other controllers lack Swagger annotations.
- **Gap**: `ProductController` and `AdminStagingController` have no OpenAPI annotations. Security scheme (`X-API-Key` header) not defined in OpenAPI spec. Response examples not included.
- **Files to Modify**: `src/main/java/com/smartguide/poc/controller/ProductController.java`, `src/main/java/com/smartguide/poc/admin/controller/AdminStagingController.java`
- **Files to Create**: `src/main/java/com/smartguide/poc/config/OpenApiConfig.java`
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P2

---

## P6 — Admin Dashboard

### ADMIN-1: Diff View for Re-scraped Products
- **Status**: ❌ MISSING
- **Current State**: No diff comparison between staging product and currently approved product.
- **Gap**: No backend endpoint returning field-level diff. No frontend diff viewer component.
- **Files to Modify**: `src/main/java/com/smartguide/poc/admin/controller/AdminStagingController.java`
- **Files to Create**: `src/main/java/com/smartguide/poc/admin/service/ProductDiffService.java`, `src/main/java/com/smartguide/poc/admin/dto/ProductDiffResponse.java`, `frontend/src/components/ProductDiff.jsx`
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: none
- **Priority**: P3

---

### ADMIN-2: Sharia Review Flag
- **Status**: ❌ MISSING
- **Current State**: No `sharia_review_flag` column in either `products` or `staging_products`. No admin UI for flagging.
- **Gap**: Needs new boolean column, endpoint to flag/unflag, and UI element.
- **Files to Modify**: `src/main/java/com/smartguide/poc/entity/StagingProduct.java`, `src/main/java/com/smartguide/poc/entity/Product.java`, `src/main/java/com/smartguide/poc/admin/controller/AdminStagingController.java`
- **Files to Create**: n/a
- **Migration**: `V11__Add_sharia_review_flag.sql` (or combined migration)
- **Complexity**: S
- **Dependencies**: DATA-4
- **Priority**: P2

---

### ADMIN-3: Bulk Operations — Mark as Stale
- **Status**: 🟡 PARTIAL
- **Current State**: Bulk approve, reject, and delete exist in `AdminStagingController`. No "mark as stale" operation.
- **Gap**: No `stale` status value in `ApprovalStatus` enum. No bulk stale marking endpoint. No staleness concept in data model.
- **Files to Modify**: `src/main/java/com/smartguide/poc/entity/StagingProduct.java`, `src/main/java/com/smartguide/poc/admin/controller/AdminStagingController.java`
- **Files to Create**: n/a
- **Migration**: n/a (enum change)
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P3

---

### ADMIN-4: Manual Product Entry (Form + CSV/Excel Upload)
- **Status**: 🟡 PARTIAL
- **Current State**: `POST /api/admin/staging` creates a staging product manually. No CSV/Excel upload endpoint.
- **Gap**: No CSV/Excel file upload handler. No batch import. Frontend `StagingReview.jsx` has no manual entry form.
- **Files to Modify**: `src/main/java/com/smartguide/poc/admin/controller/AdminStagingController.java`
- **Files to Create**: `src/main/java/com/smartguide/poc/admin/service/ProductImportService.java`, `frontend/src/pages/ManualProductEntry.jsx`
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: none
- **Priority**: P3

---

### ADMIN-5: Staleness Detection
- **Status**: ❌ MISSING
- **Current State**: `scraped_at` timestamp exists in both `products` and `staging_products`. No staleness check or UI highlighting.
- **Gap**: No query filtering products with `scraped_at < NOW() - INTERVAL '30 days'`. No UI indicator for stale products.
- **Files to Modify**: `src/main/java/com/smartguide/poc/repository/ProductRepository.java`, `src/main/java/com/smartguide/poc/admin/controller/AdminStagingController.java`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P3

---

### ADMIN-6: Scraping Progress Indicator
- **Status**: 🟡 PARTIAL
- **Current State**: Scraper service has `/api/scraper/status/{jobId}` endpoint. Frontend `ScrapeForm.jsx` exists. `ScrapeLog` entity tracks job state.
- **Gap**: No real-time WebSocket/SSE push for progress. Frontend would need to poll the scraper service directly (hardcoded `localhost:8081` in `api.js`). Polling is implemented but no live progress bar component.
- **Files to Modify**: `frontend/src/pages/ScrapeForm.jsx`, `frontend/src/services/api.js`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: none
- **Priority**: P3

---

### ADMIN-7: Dashboard Metrics
- **Status**: 🟡 PARTIAL
- **Current State**: `GET /api/admin/staging/stats` returns pending/approved/rejected counts for staging. No broader metrics (by category, last scrape date, products per bank website).
- **Gap**: No metrics for approved products by category. No last-scrape-date per source. No recommendation count metrics. Frontend `Dashboard.jsx` likely shows basic counts only.
- **Files to Modify**: `src/main/java/com/smartguide/poc/admin/controller/AdminStagingController.java`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: DATA-6
- **Priority**: P3

---

### ADMIN-8: Audit Trail
- **Status**: 🟡 PARTIAL
- **Current State**: `staging_products` stores `reviewed_by`, `reviewed_at`, `review_notes`. No comprehensive audit table tracking all changes.
- **Gap**: No `product_audit` table. No before/after snapshot on edits. No tracking of field-level changes to staging products before approval.
- **Files to Modify**: `src/main/java/com/smartguide/poc/admin/service/StagingProductService.java`
- **Files to Create**: `src/main/java/com/smartguide/poc/entity/ProductAudit.java`, `src/main/java/com/smartguide/poc/repository/ProductAuditRepository.java`
- **Migration**: `V13__Add_product_audit_table.sql`
- **Complexity**: M
- **Dependencies**: DATA-7
- **Priority**: P2

---

### ADMIN-9: Preview Mode
- **Status**: ❌ MISSING
- **Current State**: No endpoint that simulates a recommendation result for a staging product before approval.
- **Gap**: No preview endpoint. No frontend preview panel in `StagingReview.jsx`.
- **Files to Modify**: `src/main/java/com/smartguide/poc/admin/controller/AdminStagingController.java`
- **Files to Create**: `frontend/src/components/ProductPreview.jsx`
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: none
- **Priority**: P3

---

## P7 — Data Model

### DATA-1: islamic_contract_type Enum Column
- **Status**: ❌ MISSING
- **Current State**: `islamic_structure` column is `VARCHAR(50)` — free text. Seed data uses inconsistent values: "Murabaha", "Ijara", "Musharakah", "Mudarabah", "Takaful", "Wakala".
- **Gap**: No PostgreSQL enum type for Islamic contract. No Java enum for `IslamicContractType`. Column is not a first-class typed attribute.
- **Files to Modify**: `src/main/java/com/smartguide/poc/entity/Product.java`, `src/main/java/com/smartguide/poc/entity/StagingProduct.java`
- **Files to Create**: n/a
- **Migration**: `V11__Add_islamic_contract_type_enum.sql`
- **Complexity**: M
- **Dependencies**: none
- **Priority**: P2

---

### DATA-2: Bilingual Text Columns (_en / _ar)
- **Status**: ❌ MISSING
- **Current State**: No `_en` or `_ar` suffix columns in any entity or migration.
- **Gap**: Need `product_name_en`, `product_name_ar`, `description_en`, `description_ar`, `key_benefits_en`, `key_benefits_ar`, `keywords_en`, `keywords_ar` columns in both `products` and `staging_products`.
- **Files to Modify**: `src/main/java/com/smartguide/poc/entity/Product.java`, `src/main/java/com/smartguide/poc/entity/StagingProduct.java`
- **Files to Create**: n/a
- **Migration**: `V11__Add_bilingual_columns.sql`
- **Complexity**: M
- **Dependencies**: none
- **Priority**: P2

---

### DATA-3: Vector Columns (pgvector)
- **Status**: ❌ MISSING
- **Current State**: PostgreSQL 15 used without pgvector. `docker-compose.yml` uses `postgres:15-alpine` (no pgvector image). No vector columns anywhere.
- **Gap**: Need `CREATE EXTENSION IF NOT EXISTS vector`. Need `embedding_vector_en vector(1536)` and `embedding_vector_ar vector(1536)` columns. Docker compose needs `pgvector/pgvector:pg15` image.
- **Files to Modify**: `src/main/java/com/smartguide/poc/entity/Product.java`, `docker-compose.yml`
- **Files to Create**: n/a
- **Migration**: `V11__Add_pgvector_extension_and_columns.sql`
- **Complexity**: M
- **Dependencies**: none
- **Priority**: P2

---

### DATA-4: sharia_review_flag Column
- **Status**: ❌ MISSING
- **Current State**: No `sharia_review_flag` column in `products` or `staging_products`.
- **Gap**: Boolean flag needed for admin Sharia review workflow.
- **Files to Modify**: `src/main/java/com/smartguide/poc/entity/Product.java`, `src/main/java/com/smartguide/poc/entity/StagingProduct.java`
- **Files to Create**: n/a
- **Migration**: `V11__Add_sharia_review_flag.sql`
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P2

---

### DATA-5: recommendation_feedback Table
- **Status**: ❌ MISSING
- **Current State**: No feedback tracking anywhere.
- **Gap**: Need `recommendation_feedback` table: `id`, `query_text`, `query_language`, `products_shown`, `clicked_product_id`, `rating`, `timestamp`.
- **Files to Modify**: n/a
- **Files to Create**: `src/main/java/com/smartguide/poc/entity/RecommendationFeedback.java`, `src/main/java/com/smartguide/poc/repository/RecommendationFeedbackRepository.java`
- **Migration**: `V12__Add_feedback_and_log_tables.sql`
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P2

---

### DATA-6: recommendation_log Table
- **Status**: ❌ MISSING
- **Current State**: No recommendation logging. Response times tracked only in-memory per request.
- **Gap**: Need `recommendation_log` table: `id`, `session_id`, `query_text`, `query_language`, `detected_intent`, `products_returned`, `llm_latency_ms`, `total_latency_ms`, `timestamp`.
- **Files to Modify**: `src/main/java/com/smartguide/poc/controller/RecommendationController.java`
- **Files to Create**: `src/main/java/com/smartguide/poc/entity/RecommendationLog.java`, `src/main/java/com/smartguide/poc/repository/RecommendationLogRepository.java`, `src/main/java/com/smartguide/poc/service/RecommendationLogService.java`
- **Migration**: `V12__Add_feedback_and_log_tables.sql`
- **Complexity**: M
- **Dependencies**: none
- **Priority**: P2

---

### DATA-7: product_audit Table
- **Status**: ❌ MISSING
- **Current State**: No audit table. Only `reviewed_by`/`reviewed_at` on staging products.
- **Gap**: Need `product_audit` table: `id`, `product_id`, `product_type` (staging/production), `action` (CREATE/UPDATE/APPROVE/REJECT), `changed_by`, `changed_at`, `before_snapshot JSONB`, `after_snapshot JSONB`.
- **Files to Modify**: n/a
- **Files to Create**: `src/main/java/com/smartguide/poc/entity/ProductAudit.java`, `src/main/java/com/smartguide/poc/repository/ProductAuditRepository.java`
- **Migration**: `V13__Add_product_audit_table.sql`
- **Complexity**: M
- **Dependencies**: none
- **Priority**: P2

---

### DATA-8: Product Entity Sharia Term Audit
- **Status**: 🟡 PARTIAL
- **Current State**: `Product.java` has `annual_rate` column (should be `profit_rate`). Field `shariaCertified` is correctly named. `islamicStructure` is correct. No `interest_rate` or `loan_amount` fields.
- **Gap**: `annual_rate` field name is ambiguous — in Islamic banking context it should be named `profitRate` / `profit_rate`. The column name doesn't use forbidden terms but lacks clarity for domain compliance.
- **Files to Modify**: `src/main/java/com/smartguide/poc/entity/Product.java`, `src/main/java/com/smartguide/poc/entity/StagingProduct.java`
- **Files to Create**: n/a
- **Migration**: `V11__Rename_annual_rate_to_profit_rate.sql`
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P2

---

### DATA-9: Database Indexes for Recommendation Patterns
- **Status**: 🟡 PARTIAL
- **Current State**: Basic indexes on `category`, `active`, `sharia_certified` in V1 migration. Keywords array not indexed.
- **Gap**: No GIN index on `keywords` array for fast text search. No composite index on `(category, active, sharia_certified)`. No index on `profit_rate` for range queries. No vector index (IVFFlat/HNSW) after pgvector added.
- **Files to Modify**: n/a
- **Files to Create**: n/a
- **Migration**: `V11__Add_optimized_indexes.sql`
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P2

---

### DATA-10: All Migrations Numbered V11+
- **Status**: ✅ DONE
- **Current State**: Active migrations are V1–V6, V8–V10. V7 intentionally skipped (`.bak` file). Next migration is V11.
- **Gap**: None — numbering is correctly established. Development team is aware.
- **Files to Modify**: n/a
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P1

---

## P8 — Infrastructure

### INFRA-1: Dockerfile — Main App
- **Status**: ✅ DONE
- **Current State**: `Dockerfile` exists at project root. Multi-stage build (Maven builder + Alpine JRE runtime). Non-root user. Health check configured. JVM container flags set.
- **Gap**: None — Docker file is production-quality. Scraper Dockerfile is commented out in compose.
- **Files to Modify**: n/a
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P1

---

### INFRA-2: Dockerfile — Scraper Service
- **Status**: ❌ MISSING
- **Current State**: No `Dockerfile` in `product-scraper-service/`. Docker compose has scraper block commented out.
- **Gap**: Scraper needs its own multi-stage Dockerfile with Playwright browser dependencies (Chromium).
- **Files to Modify**: `docker-compose.yml`
- **Files to Create**: `product-scraper-service/Dockerfile`
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: INFRA-1
- **Priority**: P2

---

### INFRA-3: docker-compose.yml
- **Status**: 🟡 PARTIAL
- **Current State**: `docker-compose.yml` exists. PostgreSQL + main app configured. Scraper commented out. No pgvector image used (`postgres:15-alpine` instead of `pgvector/pgvector:pg15`).
- **Gap**: PostgreSQL image must be `pgvector/pgvector:pg15` for vector search. Scraper service block needs activating once INFRA-2 is done. No Redis service (future). No Ollama service for local LLM.
- **Files to Modify**: `docker-compose.yml`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: INFRA-2, DATA-3
- **Priority**: P2

---

### INFRA-4: GitHub Actions CI
- **Status**: ❌ MISSING
- **Current State**: No `.github/` directory. No CI workflows.
- **Gap**: No CI pipeline for build, test, and lint on every push/PR.
- **Files to Modify**: n/a
- **Files to Create**: `.github/workflows/ci.yml`
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: TEST-1
- **Priority**: P2

---

### INFRA-5: GitHub Actions CD
- **Status**: ❌ MISSING
- **Current State**: No CD workflow.
- **Gap**: No Docker image build and push to registry on merge to main.
- **Files to Modify**: n/a
- **Files to Create**: `.github/workflows/cd.yml`
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: INFRA-4
- **Priority**: P2

---

### INFRA-6: Kubernetes Helm Chart
- **Status**: ❌ MISSING
- **Current State**: No Helm charts or Kubernetes manifests.
- **Gap**: No `helm/` directory, no deployment/service/ingress YAML.
- **Files to Modify**: n/a
- **Files to Create**: `helm/Chart.yaml`, `helm/values.yaml`, `helm/templates/deployment.yaml`, `helm/templates/service.yaml`, `helm/templates/ingress.yaml`, `helm/templates/configmap.yaml`, `helm/templates/secret.yaml`
- **Migration**: n/a
- **Complexity**: XL
- **Dependencies**: INFRA-1, INFRA-5
- **Priority**: P3

---

### INFRA-7: Terraform for Azure UAE North
- **Status**: ❌ MISSING
- **Current State**: No Terraform files.
- **Gap**: No IaC for PostgreSQL Flexible Server, AKS, Azure Key Vault, Azure Container Registry in UAE North region.
- **Files to Modify**: n/a
- **Files to Create**: `terraform/main.tf`, `terraform/variables.tf`, `terraform/outputs.tf`, `terraform/modules/`
- **Migration**: n/a
- **Complexity**: XL
- **Dependencies**: INFRA-6
- **Priority**: P3

---

### INFRA-8: Environment-Specific Configs
- **Status**: 🟡 PARTIAL
- **Current State**: Single `application.yml` with `${ENV_VAR:default}` pattern. `SPRING_PROFILES_ACTIVE=docker` in compose but no `application-docker.yml` profile file exists.
- **Gap**: No `application-dev.yml`, `application-staging.yml`, `application-prod.yml` profile files. No profile-specific CORS, logging, or feature flag differentiation.
- **Files to Modify**: n/a
- **Files to Create**: `src/main/resources/application-dev.yml`, `src/main/resources/application-prod.yml`
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P2

---

### INFRA-9: Health Check / Spring Actuator
- **Status**: 🟡 PARTIAL
- **Current State**: Custom `/health` endpoint in `HealthController`. No Spring Boot Actuator configured. Docker and compose have health checks pointing to `/health`.
- **Gap**: No `spring-boot-starter-actuator` dependency. No `/actuator/health`, `/actuator/metrics`, `/actuator/info` endpoints. No liveness/readiness probe separation for Kubernetes.
- **Files to Modify**: `pom.xml`, `src/main/resources/application.yml`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P2

---

### INFRA-10: Graceful Shutdown
- **Status**: 🟡 PARTIAL
- **Current State**: Spring Boot 3.2 has graceful shutdown built in. Not explicitly configured in `application.yml`. No `server.shutdown=graceful` or `spring.lifecycle.timeout-per-shutdown-phase` configured.
- **Gap**: Graceful shutdown not explicitly enabled in `application.yml`. In-flight LLM calls (which can be 30s+) may be interrupted on shutdown.
- **Files to Modify**: `src/main/resources/application.yml`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P2

---

## P9 — Observability

### OBS-1: Structured JSON Logging
- **Status**: 🟡 PARTIAL
- **Current State**: SLF4J/Logback used throughout. `application.yml` configures console pattern `"%d{yyyy-MM-dd HH:mm:ss} - %msg%n"` — plain text, not JSON. No `logstash-logback-encoder` dependency.
- **Gap**: No JSON log output. Cannot be consumed by ELK/Splunk without custom parsing. No `logback-spring.xml` with JSON encoder.
- **Files to Modify**: `pom.xml`, `src/main/resources/application.yml`
- **Files to Create**: `src/main/resources/logback-spring.xml`
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P2

---

### OBS-2: Correlation IDs
- **Status**: ❌ MISSING
- **Current State**: No correlation ID generation or propagation. Each request has no traceable ID across log lines.
- **Gap**: No `X-Correlation-ID` / `X-Request-ID` header extraction. No MDC population with request ID. No correlation ID in responses.
- **Files to Modify**: n/a
- **Files to Create**: `src/main/java/com/smartguide/poc/filter/CorrelationIdFilter.java`
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: OBS-1
- **Priority**: P2

---

### OBS-3: Micrometer / Prometheus Metrics
- **Status**: ❌ MISSING
- **Current State**: No `micrometer-registry-prometheus` dependency. No `spring-boot-starter-actuator`.
- **Gap**: No `/actuator/prometheus` endpoint. No metrics exposition for Prometheus scraping.
- **Files to Modify**: `pom.xml`, `src/main/resources/application.yml`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: INFRA-9
- **Priority**: P2

---

### OBS-4: Key Business Metrics
- **Status**: ❌ MISSING
- **Current State**: `processingTimeMs` tracked per request in `RecommendationController` but not exported as a metric.
- **Gap**: No `Counter` for recommendation counts. No `Timer` for LLM call latency. No metric for scrape job success/failure. No metric for intent distribution.
- **Files to Modify**: `src/main/java/com/smartguide/poc/controller/RecommendationController.java`, `src/main/java/com/smartguide/poc/service/LLMService.java`
- **Files to Create**: `src/main/java/com/smartguide/poc/metrics/RecommendationMetrics.java`
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: OBS-3
- **Priority**: P2

---

### OBS-5: Health Dashboard (Grafana)
- **Status**: ❌ MISSING
- **Current State**: No Grafana configuration or dashboard JSON.
- **Gap**: No Grafana service in docker compose. No dashboard definitions for API latency, LLM call rates, product recommendation distribution.
- **Files to Modify**: `docker-compose.yml`
- **Files to Create**: `grafana/dashboards/smartguide.json`
- **Migration**: n/a
- **Complexity**: L
- **Dependencies**: OBS-3, OBS-4
- **Priority**: P3

---

### OBS-6: Circuit Breaker (Resilience4j)
- **Status**: ❌ MISSING
- **Current State**: LLM calls use `WebClient` with `.timeout()`. If LLM is slow but not failed, the request blocks for the full timeout (30s for intent, 30s for ranking = 60s total). No circuit breaker.
- **Gap**: No `resilience4j-spring-boot3` dependency. No `@CircuitBreaker` annotation on LLM service methods. No open/half-open state handling.
- **Files to Modify**: `pom.xml`, `src/main/java/com/smartguide/poc/service/LLMService.java`
- **Files to Create**: `src/main/resources/application-resilience.yml`
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: none
- **Priority**: P2

---

### OBS-7: Retry with Backoff
- **Status**: ❌ MISSING
- **Current State**: `WebClient` calls fail immediately on error with no retry. Exception is caught and fallback used. No retry with exponential backoff.
- **Gap**: No Resilience4j `@Retry` or WebFlux `retry()` operator with backoff for transient LLM/DB failures.
- **Files to Modify**: `src/main/java/com/smartguide/poc/service/LLMService.java`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: OBS-6
- **Priority**: P2

---

### OBS-8: Alerting Rules
- **Status**: ❌ MISSING
- **Current State**: No alerting configured anywhere.
- **Gap**: No Prometheus alerting rules. No PagerDuty/Slack integration. No alert on API latency > 2s, LLM failure rate > 10%, scrape failures.
- **Files to Modify**: n/a
- **Files to Create**: `prometheus/alerts.yml`
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: OBS-3, OBS-5
- **Priority**: P3

---

## P10 — Sharia Compliance

### SHARIA-1: Full Codebase Audit for Forbidden Terms
- **Status**: 🟡 PARTIAL
- **Current State**: Violations found (see Step 5 scan below). Main violations in `LLMService.java` (system prompt), `ProductService.java` (intent map keys), SQL seed data (V2), frontend JSX, and scraper `AIProductExtractor.java`.
- **Gap**: Active violations must be fixed before production. See Sharia Compliance Scan section for full list.
- **Files to Modify**: `src/main/java/com/smartguide/poc/service/LLMService.java`, `src/main/java/com/smartguide/poc/service/ProductService.java`, `src/main/resources/db/migration/V2__Seed_data.sql`, `frontend/src/pages/TestRecommendations.jsx`, `frontend/src/pages/AllProducts.jsx`, `product-scraper-service/src/main/java/com/smartguide/scraper/service/AIProductExtractor.java`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: M
- **Dependencies**: none
- **Priority**: P1

---

### SHARIA-2: Sharia Guidelines in All LLM Prompts
- **Status**: 🟡 PARTIAL
- **Current State**: Keyword generation system prompt in `application.yml` mentions Islamic finance. Main intent extraction `SYSTEM_PROMPT` in `LLMService.java` lacks explicit Sharia terminology rules and uses forbidden terms.
- **Gap**: `SYSTEM_PROMPT` uses "LOAN", "INSURANCE", "travel insurance", "car loan". No prohibition statement in the system prompt (e.g., "Never use the words interest, loan, mortgage, insurance — use profit rate, finance, Takaful instead").
- **Files to Modify**: `src/main/java/com/smartguide/poc/service/LLMService.java`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P1

---

### SHARIA-3: profit_rate Instead of annual_rate/interest_rate
- **Status**: 🟡 PARTIAL
- **Current State**: Column named `annual_rate` in `products` and `staging_products`. Entity field is `annualRate`. No `interest_rate` found (good). But `annual_rate` is ambiguous.
- **Gap**: `annual_rate` should be renamed `profit_rate` in both the database column and Java entity field for Sharia clarity. Seed data in V2 references this column.
- **Files to Modify**: `src/main/java/com/smartguide/poc/entity/Product.java`, `src/main/java/com/smartguide/poc/entity/StagingProduct.java`
- **Files to Create**: n/a
- **Migration**: `V11__Rename_annual_rate_to_profit_rate.sql`
- **Complexity**: S
- **Dependencies**: none
- **Priority**: P2

---

### SHARIA-4: Islamic Contract Type as First-Class Attribute
- **Status**: ❌ MISSING
- **Current State**: `islamic_structure` is a free-text `VARCHAR(50)`. Seed data uses inconsistent spellings ("Ijara" vs "Ijarah", "Musharakah" vs "Diminishing Musharakah").
- **Gap**: No PostgreSQL enum for contract types. No Java enum `IslamicContractType`. Values not validated. Misspellings in seed data (should be standardized).
- **Files to Modify**: `src/main/java/com/smartguide/poc/entity/Product.java`, `src/main/java/com/smartguide/poc/entity/StagingProduct.java`
- **Files to Create**: `src/main/java/com/smartguide/poc/entity/IslamicContractType.java`
- **Migration**: `V11__Add_islamic_contract_type_enum.sql`
- **Complexity**: M
- **Dependencies**: DATA-1
- **Priority**: P2

---

### SHARIA-5: "All Products are Sharia-Compliant" Response Handling
- **Status**: ❌ MISSING
- **Current State**: When user asks "show me Sharia-compliant products", the system treats it as a GENERAL intent and returns all products. No special handling.
- **Gap**: No detection of "sharia-compliant" query. No response that clarifies all products are Sharia-compliant and redirects to product type selection.
- **Files to Modify**: `src/main/java/com/smartguide/poc/service/LLMService.java`, `src/main/java/com/smartguide/poc/controller/RecommendationController.java`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: AI-5
- **Priority**: P2

---

### SHARIA-6: AI-Generated Content Validation Before Publish
- **Status**: ❌ MISSING
- **Current State**: LLM-generated keywords and summaries are stored directly without Sharia term scanning.
- **Gap**: No `ShariaComplianceChecker` utility. No pre-save validation hook in `StagingProductService` or `LLMService`. No admin review flag set automatically when violations detected.
- **Files to Modify**: `src/main/java/com/smartguide/poc/admin/service/StagingProductService.java`
- **Files to Create**: `src/main/java/com/smartguide/poc/util/ShariaComplianceChecker.java`
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: AI-4
- **Priority**: P1

---

### SHARIA-7: "finance" Not "loan" in All Categories and Descriptions
- **Status**: 🟡 PARTIAL
- **Current State**: Intent category mapping uses "LOAN" as an intent key in seed data (V2) and in `LLMService` `validIntents` set and fallback keywords. Category name "FINANCING" is correctly used in products table.
- **Gap**: `LOAN` intent key in `intent_category_mapping` seed data should remain as an internal intent identifier (mapped from user input) but should never be exposed to customers. `LLMService.getFallbackIntent()` uses "loan" as a keyword match. Frontend shows `INSURANCE` as a product category.
- **Files to Modify**: `src/main/java/com/smartguide/poc/service/LLMService.java`, `frontend/src/pages/AllProducts.jsx`
- **Files to Create**: n/a
- **Migration**: n/a
- **Complexity**: S
- **Dependencies**: SHARIA-2
- **Priority**: P1

---

## Step 5: Sharia Compliance Scan

### Violations Found

**`src/main/java/com/smartguide/poc/service/LLMService.java`**
| Line | Matched Text | Correct Replacement |
|---|---|---|
| 34 | `travel insurance` | `travel Takaful` |
| 35 | `LOAN: General financing or loan requests` | `FINANCE: General financing requests` |
| 38 | `INSURANCE: Insurance or Takaful products` | `TAKAFUL: Takaful products` |
| 39 | `car loans, or vehicle purchase financing` | `auto financing or vehicle purchase financing` |
| 48 | `"car loan" or "auto financing"` | `"auto finance" or "vehicle financing"` |
| 54 | `"I need a car loan"` | `"I need auto finance"` |
| 212 | `"TRAVEL", "LOAN", ... "INSURANCE"` (validIntents) | Keep as internal codes but fix display |
| 253 | `"LOAN": Arrays.asList("loan", "finance", ...)` | `"FINANCE": Arrays.asList("finance", ...)` |
| 256 | `"INSURANCE": Arrays.asList("insurance", ...)` | `"TAKAFUL": Arrays.asList("takaful", ...)` |
| 258 | `"mortgage"` in fallback keywords | remove or replace with "home-finance" |

**`src/main/java/com/smartguide/poc/service/ProductService.java`**
| Line | Matched Text | Correct Replacement |
|---|---|---|
| 325 | `"LOAN": Arrays.asList("finance", "loan", ...)` | `"FINANCE": Arrays.asList("finance", ...)` |
| 329 | `"mortgage"` in HOME keywords | remove — replace with "home-finance" |
| 332 | `"INSURANCE": Arrays.asList(...)` | `"TAKAFUL": Arrays.asList(...)` |
| 354 | `"LOAN": "Flexible %s financing - %s"` | `"FINANCE": "Flexible %s financing - %s"` |
| 361 | `"INSURANCE": "Comprehensive protection..."` | `"TAKAFUL": "Comprehensive protection..."` |

**`src/main/resources/db/migration/V2__Seed_data.sql`**
| Line | Matched Text | Correct Replacement |
|---|---|---|
| 3 | `ARRAY['INSURANCE', 'SAVINGS']` | `ARRAY['TAKAFUL', 'SAVINGS']` |
| 4 | `('LOAN', 'FINANCING', ...)` | `('FINANCE', 'FINANCING', ...)` |
| 7 | `ARRAY['INSURANCE']` | `ARRAY['TAKAFUL']` |
| 8 | `ARRAY['INSURANCE', 'CASA']` | `ARRAY['TAKAFUL', 'CASA']` |
| 10 | `('INSURANCE', 'INSURANCE', ...)` | `('TAKAFUL', 'TAKAFUL', ...)` |
| 19 | `"Travel insurance up to $100,000"` | `"Travel Takaful up to $100,000"` |
| 26 | `"Insurance bundled"` in auto finance | `"Takaful bundled"` |
| 27 | `"Takaful insurance included"` | `"Takaful included"` (remove "insurance") |
| 33 | `"No mortgage required"` | `"No property pledge required"` |
| 40 | `"Health insurance discounts"` | `"Health Takaful discounts"` |
| 42 | Comment: `-- Insurance/Takaful Products` | `-- Takaful Products` |
| 44–48 | All `INSURANCE` category products | Category should be `TAKAFUL` |
| 46 | `"Comprehensive health insurance"` in description | `"Comprehensive health Takaful"` |

**`frontend/src/pages/TestRecommendations.jsx`**
| Line | Matched Text | Correct Replacement |
|---|---|---|
| 96 | `"I need a car loan"` (placeholder text) | `"I need auto financing"` |

**`frontend/src/pages/AllProducts.jsx`**
| Line | Matched Text | Correct Replacement |
|---|---|---|
| 193 | `'INSURANCE'` in categories array | `'TAKAFUL'` |

**`product-scraper-service/.../AIProductExtractor.java`**
| Line | Matched Text | Correct Replacement |
|---|---|---|
| 78 | `"loans, savings accounts, ... insurance"` in prompt | `"financing, savings accounts, ... Takaful"` |
| 98 | `"loans, savings accounts, ... insurance"` in prompt | `"financing, savings accounts, ... Takaful"` |
| 108 | `"INSURANCE"` in category enum string | `"TAKAFUL"` |

---

## Step 6: Summary

### Score Card

| Priority Group | ✅ DONE | 🟡 PARTIAL | ❌ MISSING | Total |
|---|---|---|---|---|
| P1 Security (SEC-1–10) | 1 | 4 | 5 | 10 |
| P2 Tests (TEST-1–9) | 0 | 0 | 9 | 9 |
| P3 Bilingual (I18N-1–7) | 0 | 1 | 6 | 7 |
| P4 AI & Search (AI-1–9) | 0 | 4 | 5 | 9 |
| P5 API (API-1–8) | 0 | 4 | 4 | 8 |
| P6 Admin (ADMIN-1–9) | 0 | 5 | 4 | 9 |
| P7 Data Model (DATA-1–10) | 2 | 2 | 6 | 10 |
| P8 Infrastructure (INFRA-1–10) | 1 | 4 | 5 | 10 |
| P9 Observability (OBS-1–8) | 0 | 1 | 7 | 8 |
| P10 Sharia (SHARIA-1–7) | 0 | 4 | 3 | 7 |
| **Total** | **4** | **29** | **54** | **87** |

---

### Quick Wins
S-complexity items with no blocking dependencies completable in < 1 day:

| ID | Title | Why Quick |
|---|---|---|
| SEC-4 | Remove PII from debug logs | 2-line log sanitization change |
| SEC-6 | Input sanitization | Add `@Size` constraints + sanitizer util |
| SEC-9 | CORS bank domain restriction | 1-file config change |
| SEC-10 | Security headers (CSP, HSTS) | 5-line change in SecurityConfig |
| TEST-5 | Sharia compliance test | Regex scan test, no mocking needed |
| TEST-9 | JaCoCo coverage plugin | pom.xml addition only |
| AI-4 / SHARIA-6 | Sharia term checker utility | Small utility class |
| AI-5 / SHARIA-2 | Fix system prompt forbidden terms | String edit in LLMService |
| SHARIA-3 | Rename annual_rate → profit_rate | Migration + entity field rename |
| SHARIA-5 | Sharia-compliant query handling | Intent detection + response tweak |
| SHARIA-7 | Fix frontend category names | One-line JSX array fix |
| OBS-1 | Structured JSON logging | pom.xml + logback-spring.xml |
| OBS-2 | Correlation ID filter | New servlet filter, small class |
| INFRA-8 | Profile-specific config files | New YAML files |
| INFRA-9 | Spring Actuator health | pom.xml + application.yml |
| INFRA-10 | Graceful shutdown config | 2-line application.yml addition |
| DATA-4 | sharia_review_flag column | Simple migration + entity field |
| DATA-9 | Add optimized indexes | New migration file |
| DATA-10 | Migration numbering | Already done ✅ |
| API-3 | Categories endpoint | ~20 lines in controller |
| API-6 | Consistent error format | Small RecommendationController fix |
| API-8 | OpenAPI config + annotations | Config class + annotations |
| ADMIN-5 | Staleness detection query | Repository method + endpoint |

---

### Critical Blockers
P1 items that must be resolved before any production deployment:

| ID | Title | Severity |
|---|---|---|
| **SEC-1** | JWT/OAuth2 validation | 🔴 BLOCKER — no user-level auth |
| **SEC-5** | Rate limiting | 🔴 BLOCKER — API can be DoS'd |
| **SEC-9** | CORS bank domains | 🔴 BLOCKER — allows any localhost origin |
| **SEC-10** | Security headers | 🔴 BLOCKER — no CSP/HSTS |
| **TEST-1** | 0% unit test coverage | 🔴 BLOCKER — no confidence in system |
| **TEST-5** | Sharia compliance test | 🔴 BLOCKER — audit requirement |
| **AI-5/SHARIA-2** | System prompt violations | 🔴 BLOCKER — LLM generating forbidden terms |
| **SHARIA-1** | Codebase forbidden terms | 🔴 BLOCKER — regulatory risk |
| **AI-4/SHARIA-6** | AI output validation | 🔴 BLOCKER — unvalidated LLM output in DB |
| **SEC-7** | SQL injection | ✅ Already DONE — no blocker |

---

### Recommended First Sprint
8–10 items ordered by P1 priority → unblock others → highest value:

| # | ID | Title | Rationale |
|---|---|---|---|
| 1 | **SHARIA-1 + SHARIA-2 + AI-5** | Fix all forbidden term violations + system prompt | Quickest, highest regulatory risk, unblocks AI-4 and TEST-5 |
| 2 | **AI-4 + SHARIA-6** | ShariaComplianceChecker utility | Prevents future violations, small class, unblocks TEST-5 |
| 3 | **TEST-5** | Sharia compliance automated test | Locks in compliance, fast to write after checker exists |
| 4 | **SEC-10** | Security headers (CSP, HSTS, X-Frame) | 5-line change in SecurityConfig, P1 blocker |
| 5 | **SEC-9** | CORS bank domain restriction | 1-file change, P1 blocker |
| 6 | **SEC-6** | Input validation + sanitization | Closes prompt injection risk; small util class |
| 7 | **TEST-9 + INFRA-9** | JaCoCo + Spring Actuator | Enables test coverage reporting and proper health checks; both S-complexity |
| 8 | **OBS-1 + OBS-2** | Structured JSON logging + Correlation IDs | Enables request tracing before adding more features; unblocks OBS-3 |
| 9 | **TEST-1** | Unit tests for core services | LLMService, ProductService, RulesEngine — foundational before any refactoring |
| 10 | **DATA-1 + DATA-4 + DATA-8** | Islamic contract enum + sharia_review_flag + profit_rate rename | Combined into one V11 migration; foundational data model cleanup |
