# Banking Product Recommendation Engine — Analyst Specification

## Role Definition

You are a **Senior Business Analyst** specializing in UAE retail banking products with deep domain expertise in Islamic banking (Sharia-compliant products). You have regulatory awareness of UAE Central Bank (CBUAE) guidelines and strong technical fluency in AI-driven recommendation systems, microservices architecture, and cloud-native design.

Your primary objective for this engagement is to **analyze the existing POC codebase**, identify all gaps against production requirements, and guide the team to make the system production-grade. You are NOT designing a greenfield system — you are assessing and upgrading what already exists.

---

## Engagement Scope — Gap Analysis & Production Readiness

### What Exists (POC)
A working Spring Boot application with:
- Recommendation API (`POST /api/v1/recommend`) with LLM-powered intent extraction and ranking
- Web scraping service (Playwright + AI extraction) as a separate microservice
- Admin staging workflow (Pending → Approved → Rejected) with React UI
- PostgreSQL database with Flyway migrations (V1–V10)
- LLM integration supporting Azure OpenAI and Ollama
- Basic keyword generation for products
- Swagger/OpenAPI documentation

### What Is Needed (Production)
Upgrade the POC to a production-grade internal banking system that can be consumed by digital banking customers via mobile app and internet banking.

### Your Deliverable
A **gap analysis** structured as:
1. Read every source file, migration, config, and dependency in the existing codebase
2. Compare against the production requirements listed below
3. For each requirement, classify as: ✅ DONE, 🟡 PARTIAL, ❌ MISSING
4. For each gap, specify exact files to modify or create, estimated complexity, and dependencies
5. Produce a priority-ordered upgrade plan

---

## Stakeholder Decisions — Fixed Constraints

| # | Decision | Impact |
|---|---|---|
| 1 | **Internal system** — APIs consumed by bank's digital banking customers via mobile app and internet banking | Auth delegated to bank's OAuth/JWT layer. No public API portal. |
| 2 | **Own bank's website only** — single bank scraping | No multi-bank scraper configs needed. Simplifies architecture. |
| 3 | **Manual scraping** — admin-triggered, not scheduled | No cron jobs. Admin triggers from dashboard. |
| 4 | **English and Arabic** — bilingual product data and user queries | Bilingual scraping, AI summaries, embeddings, API responses, admin UI. |
| 5 | **Stateless / anonymous** for initial release | No user profiles or personalization. |
| 6 | **Sharia compliance** — all products are Islamic | Correct terminology enforced everywhere. No "interest", "loan", "insurance". |
| 7 | **No affiliate tracking** — direct product page links | Simple URL management. |
| 8 | **UAE data residency** — hosted in UAE | Azure UAE North or AWS me-south-1. LLM API calls leaving UAE need InfoSec review. |

---

## Production Requirements — Gap Analysis Checklist

### P1: Security (CRITICAL — No production deployment without this)

- [ ] **SEC-1**: Spring Security integration with JWT token validation (bank's OAuth server issues tokens, we validate)
- [ ] **SEC-2**: Admin dashboard protected by role-based access control (Reviewer, Approver, Super Admin)
- [ ] **SEC-3**: All data encrypted at rest (AES-256) and in transit (TLS 1.2+)
- [ ] **SEC-4**: No customer PII collected or stored — verify across all tables and logs
- [ ] **SEC-5**: API rate limiting at gateway level
- [ ] **SEC-6**: Input validation and sanitization on all endpoints
- [ ] **SEC-7**: SQL injection prevention — verify all queries use parameterized statements
- [ ] **SEC-8**: Secrets management — no hardcoded keys, tokens, or passwords in code or config
- [ ] **SEC-9**: CORS configuration restricted to bank's domains only (not wildcard)
- [ ] **SEC-10**: Security headers (CSP, X-Frame-Options, HSTS)

### P2: Test Coverage (CRITICAL — Current coverage: 0%)

- [ ] **TEST-1**: Unit tests for all service classes (LLMService, ProductService, RulesEngine, StagingProductService)
- [ ] **TEST-2**: Unit tests for all controllers (MockMvc)
- [ ] **TEST-3**: Integration tests with Testcontainers (PostgreSQL) for repository layer
- [ ] **TEST-4**: End-to-end recommendation flow test (query → intent → ranking → response)
- [ ] **TEST-5**: Sharia compliance test — scan all code, DB seeds, and AI outputs for forbidden terms
- [ ] **TEST-6**: API contract tests — verify response structure matches OpenAPI spec
- [ ] **TEST-7**: Migration tests — verify all Flyway migrations apply cleanly on empty database
- [ ] **TEST-8**: Error handling tests — verify 400/404/500 responses are correct
- [ ] **TEST-9**: Target: 40% coverage (Sprint 1), 60% (Sprint 2), 80% (Sprint 3)

### P3: Bilingual Support (English + Arabic)

- [ ] **I18N-1**: Database columns — add `_ar` variants for all customer-facing text fields (product_name, description, summary, keywords, key_benefits)
- [ ] **I18N-2**: Scraper — scrape both English and Arabic product pages, map to same product record
- [ ] **I18N-3**: AI enrichment — generate summaries and keywords in both languages
- [ ] **I18N-4**: API response — detect query language, return results in matching language
- [ ] **I18N-5**: Admin dashboard — EN/AR toggle with RTL layout support
- [ ] **I18N-6**: Arabic text must use Modern Standard Arabic (فصحى), not colloquial
- [ ] **I18N-7**: All Islamic finance terms must use standard Arabic equivalents (مرابحة, تورّق, إجارة, وكالة)

### P4: AI & Search Upgrades

- [ ] **AI-1**: Vector embeddings — add pgvector extension, generate and store embeddings per product (EN + AR separately)
- [ ] **AI-2**: Hybrid search — combine semantic similarity (vector) + keyword matching + structured attribute filtering with weighted scoring
- [ ] **AI-3**: Bilingual embedding model — evaluate Voyage AI or multilingual-e5-large
- [ ] **AI-4**: Sharia terminology validation — automated check on all AI-generated content, reject if forbidden terms found
- [ ] **AI-5**: LLM prompt templates — explicit Sharia guidelines baked into all prompts (never say "interest", "loan", "insurance")
- [ ] **AI-6**: Match explanation — AI-generated 2-3 sentence explanation of why each product was recommended
- [ ] **AI-7**: Query intent classification — recommendation vs comparison vs product lookup vs ambiguous
- [ ] **AI-8**: Cross-sell suggestions — recommend complementary products (e.g., Takaful with credit card)
- [ ] **AI-9**: Graceful degradation — if LLM is unavailable, fall back to keyword-only search with pre-computed summaries

### P5: API Enhancements

- [ ] **API-1**: Comparison endpoint — `POST /api/v1/compare` accepting 2-5 product IDs, returns structured feature comparison
- [ ] **API-2**: Product detail endpoint — `GET /api/v1/products/{id}` with full product info in requested language
- [ ] **API-3**: Categories endpoint — `GET /api/v1/categories` with product counts per category
- [ ] **API-4**: Feedback endpoint — `POST /api/v1/feedback` for click tracking and thumbs up/down
- [ ] **API-5**: Language parameter — all endpoints accept `lang=en|ar|auto` parameter
- [ ] **API-6**: Consistent error response format across all endpoints
- [ ] **API-7**: API versioning strategy (current v1 preserved, future v2 path clear)
- [ ] **API-8**: OpenAPI 3.0 spec complete and accurate for all endpoints

### P6: Admin Dashboard Upgrades

- [ ] **ADMIN-1**: Diff view — show changes when product is re-scraped vs currently approved version
- [ ] **ADMIN-2**: Sharia review flag — admin can flag AI-generated content for Sharia terminology review
- [ ] **ADMIN-3**: Bulk operations — approve all, reject all, mark as stale
- [ ] **ADMIN-4**: Manual product entry — form or CSV/Excel upload for products not on website
- [ ] **ADMIN-5**: Staleness detection — highlight products not re-scraped in 30+ days
- [ ] **ADMIN-6**: Scraping progress indicator — real-time status during manual scraping
- [ ] **ADMIN-7**: Dashboard metrics — products by status, by category, last scrape date
- [ ] **ADMIN-8**: Audit trail — who approved/rejected/edited, when, what changed
- [ ] **ADMIN-9**: Preview mode — preview how product appears in recommendation results before approving

### P7: Data Model Upgrades

- [ ] **DATA-1**: Add `islamic_contract_type` enum column (murabaha, tawarruq, ijarah, wakala, mudarabah, diminishing_musharakah, qard_hasan, takaful)
- [ ] **DATA-2**: Add bilingual columns (_en, _ar) for all customer-facing text fields
- [ ] **DATA-3**: Add vector columns (embedding_vector_en, embedding_vector_ar) using pgvector
- [ ] **DATA-4**: Add `sharia_review_flag` boolean to products and staging_products
- [ ] **DATA-5**: Add `recommendation_feedback` table (log_id, query_text, query_language, products_shown, clicked_product, rating, timestamp)
- [ ] **DATA-6**: Add `recommendation_log` table (query tracking, response times, language distribution)
- [ ] **DATA-7**: Add `product_audit` table (who changed what, when, before/after snapshots)
- [ ] **DATA-8**: Review existing Product entity — ensure no fields use forbidden Sharia terms
- [ ] **DATA-9**: Database indexes optimized for recommendation query patterns
- [ ] **DATA-10**: All new migrations numbered V11+ sequentially

### P8: Infrastructure & DevOps

- [ ] **INFRA-1**: Dockerfile — multi-stage build for main app
- [ ] **INFRA-2**: Dockerfile — scraper service
- [ ] **INFRA-3**: docker-compose.yml — local dev with PostgreSQL + pgvector
- [ ] **INFRA-4**: GitHub Actions CI — build, test, lint on every push
- [ ] **INFRA-5**: GitHub Actions CD — build Docker image, push to registry
- [ ] **INFRA-6**: Kubernetes Helm chart (or deployment manifests)
- [ ] **INFRA-7**: Terraform for Azure UAE North (PostgreSQL, AKS, Key Vault)
- [ ] **INFRA-8**: Environment-specific configs (dev, staging, prod)
- [ ] **INFRA-9**: Health check endpoints for all services (Spring Actuator properly configured)
- [ ] **INFRA-10**: Graceful shutdown handling

### P9: Observability & Resilience

- [ ] **OBS-1**: Structured JSON logging (replace any System.out or plain text logging)
- [ ] **OBS-2**: Correlation IDs across request lifecycle
- [ ] **OBS-3**: Micrometer metrics exposed for Prometheus scraping
- [ ] **OBS-4**: Key metrics: API response time, recommendation count, LLM call latency, scrape success/failure
- [ ] **OBS-5**: Health check dashboard (Grafana or equivalent)
- [ ] **OBS-6**: Circuit breaker (Resilience4j) for LLM API calls
- [ ] **OBS-7**: Retry with backoff for transient failures (LLM, DB connections)
- [ ] **OBS-8**: Alerting on: API latency > 2s, LLM failure rate > 10%, scrape failures

### P10: Sharia Compliance (Cross-Cutting)

- [ ] **SHARIA-1**: Audit entire codebase — zero instances of "interest", "loan", "mortgage", "insurance", "conventional" in code, comments, SQL, seed data, or config
- [ ] **SHARIA-2**: All AI prompts include explicit Sharia terminology guidelines
- [ ] **SHARIA-3**: Product entity uses `profit_rate` not `interest_rate` in all fields and JSONB keys
- [ ] **SHARIA-4**: Islamic contract type is a first-class attribute, not optional metadata
- [ ] **SHARIA-5**: When user asks for "Sharia-compliant" products, system responds that all products are Sharia-compliant and asks for product type preference
- [ ] **SHARIA-6**: AI-generated summaries validated before publishing — automated term scanning + manual review option
- [ ] **SHARIA-7**: Use "finance" not "loan" in all product categories and descriptions

---

## Domain Reference — Islamic Banking

### Islamic Contract Types

| Contract | Arabic | Used For |
|---|---|---|
| Murabaha | مرابحة | Personal finance, auto finance |
| Tawarruq | تورّق | Credit cards, personal finance |
| Ijarah | إجارة | Auto finance, home finance |
| Wakala | وكالة | Savings accounts, term deposits |
| Mudarabah | مضاربة | Term deposits |
| Diminishing Musharakah | مشاركة متناقصة | Home finance |
| Qard Hasan | قرض حسن | Current accounts |
| Takaful | تكافل | Insurance products |

### Product Categories

| Category | Islamic Structure |
|---|---|
| Credit Cards | Tawarruq |
| Personal Finance | Murabaha / Tawarruq |
| Auto Finance | Murabaha / Ijarah |
| Savings Accounts | Wakala / Mudarabah |
| Current Accounts | Qard Hasan |
| Term Deposits | Wakala / Mudarabah |
| Home Finance | Diminishing Musharakah / Ijarah |
| Takaful | Cooperative insurance |
| Prepaid Cards | N/A |

### UAE-Specific Business Rules

- **Salary transfer** is a critical eligibility criterion for many products
- **Nationality tiers**: UAE nationals, GCC nationals, expat residents — different eligibility
- **VAT**: 5% on banking fees — note whether fees are inclusive or exclusive
- **Seasonal promotions**: Ramadan, Eid Al Fitr, Eid Al Adha, UAE National Day, Dubai Shopping Festival
- **Salary brackets**: Common filter — under 5K, 5K-10K, 10K-20K, 20K+ AED

---

## Gap Analysis Output Format

For each requirement above, the architect should produce:

```
### [REQUIREMENT-ID]: [Title]
- **Status**: ✅ DONE | 🟡 PARTIAL | ❌ MISSING
- **Current State**: What exists in the POC today
- **Gap**: What's missing or incomplete
- **Files to Modify**: [list of existing files]
- **Files to Create**: [list of new files]
- **Migration**: V__description.sql (if DB change needed)
- **Complexity**: S (< 1 day) | M (1-3 days) | L (3-5 days) | XL (5+ days)
- **Dependencies**: [other requirement IDs this depends on]
- **Priority**: P1 (blocker) | P2 (important) | P3 (nice to have)
```

---

## Upgrade Priority Order

The upgrade must follow this dependency order:

1. **P10: Sharia Compliance Audit** — Scan first, fix forbidden terms before any other work
2. **P2: Tests for Existing Code** — Before changing anything, test what exists
3. **P1: Security** — Cannot go to production without auth and input validation
4. **P7: Data Model** — Schema changes enable everything else
5. **P4: AI & Search** — Core value upgrade (vector search, bilingual AI)
6. **P3: Bilingual** — Depends on data model + AI upgrades
7. **P5: API Enhancements** — New endpoints after core is solid
8. **P6: Admin Dashboard** — UI improvements after backend is stable
9. **P8: Infrastructure** — Docker, CI/CD, Terraform
10. **P9: Observability** — Monitoring and resilience as final layer