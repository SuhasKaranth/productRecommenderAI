# Azure AI Foundry Integration — Product Owner Roadmap

**Project**: Smart Guide — Banking Product Recommendation Engine
**Prepared by**: Product Owner
**Date**: 2026-02-24
**Scope**: Migration from Ollama-only LLM to a configurable multi-provider model including Azure AI Foundry

---

## Executive Summary

The Smart Guide POC currently uses Ollama (a locally hosted LLM) for three AI-driven capabilities: intent extraction from user queries, keyword generation for banking products, and LLM-based product ranking. While Ollama is valuable for local development and offline testing, it cannot satisfy the performance, reliability, latency, and data-residency requirements for a production-grade banking recommendation engine serving digital banking customers in the UAE.

This roadmap defines the business case, phased delivery plan, and success criteria for introducing Azure AI Foundry as a fully configurable LLM provider — running alongside Ollama without breaking any existing functionality. The integration is designed to be an enabler for the broader production readiness upgrade already underway.

---

## 1. Business Context

### 1.1 Why This Matters Now

The system is transitioning from a POC to a production banking product. As the team scales, several limitations of the current Ollama-only setup become blockers:

**Performance gap.** Ollama runs on developer laptops or a single VM. Response times for ranking 10 products can exceed 30 seconds with a 7B-parameter model. For a mobile banking app, a recommendation API call must return in under 3 seconds.

**Infrastructure gap.** Deploying and maintaining an on-premises GPU server in a bank's infrastructure requires procurement timelines, security hardening, and specialised DevOps skills that are not part of this project's scope.

**Model capability gap.** State-of-the-art cloud models (GPT-4o, Phi-4, Llama 3.3) significantly outperform smaller 7B models on structured JSON output, bilingual (Arabic + English) reasoning, and financial domain understanding — all critical for this product.

**Data residency.** UAE data residency requirements mandate that processing happens in Azure UAE North. Cloud LLM providers with UAE region support (Azure AI Foundry in UAE North) provide a compliant path that a locally-managed Ollama instance does not.

**Future capabilities.** Azure AI Foundry's model catalogue, embeddings endpoints, and content safety filters are prerequisites for planned production features including vector semantic search (pgvector), Arabic language support, and Sharia compliance content scanning.

### 1.2 What Azure AI Foundry Provides

Azure AI Foundry is Microsoft's unified AI platform that combines Azure OpenAI Service (GPT-4o, GPT-4o-mini, o3-mini) with an open model catalogue (Meta Llama, Microsoft Phi, Mistral, Cohere) under a single management plane. From a product standpoint, it delivers:

- **Production-grade SLA**: 99.9% uptime guarantee with SLAs covering API availability
- **UAE North region hosting**: All inference stays within UAE for data residency compliance
- **Pay-per-use pricing**: No GPU servers to procure or maintain; cost tracks actual usage
- **Model flexibility**: Swap underlying model via configuration without touching application code
- **Content safety filters**: Built-in filtering for harmful content (relevant for public-facing API)
- **Monitoring and audit**: Token usage, latency, and error tracking through Azure Monitor
- **Managed scaling**: Auto-scales with demand; no capacity planning needed for the POC phase

### 1.3 What This Change Is NOT

This is not a rip-and-replace of the existing LLM integration. The `app.llm.provider` configuration key already exists in `application.yml` and `LLMService.java` already contains an Azure OpenAI code path. This initiative **completes and validates** the Azure path, formally introduces Azure AI Foundry as the provisioning and management layer, and ensures parity between providers so either can be used in any environment.

---

## 2. Stakeholder Map

| Stakeholder | Role | Interest |
|---|---|---|
| Dev Team | Implementation | Clear provider abstraction, no regression in Ollama path |
| InfoSec / Compliance | Approval gate | Data residency, key management, no secrets in code |
| Banking Operations | Future admin user | Stable API; no downtime during switchover |
| Product Management | This roadmap owner | Feature parity, cost control, delivery timeline |
| Finance / Procurement | Budget approval | Azure subscription cost visibility, no surprise overruns |

---

## 3. Current State Assessment

The codebase already has partial Azure OpenAI support:

- `LLMConfig.java` — configuration properties for both `azure` and `ollama` providers
- `LLMService.java` — `if/else` branching for `azure` vs `ollama` in intent extraction, keyword generation, and product ranking
- `application.yml` — `app.llm.provider` environment variable (`LLM_PROVIDER`), with Azure endpoint, key, deployment name, and API version all externally configurable

**What works today**: If you set `LLM_PROVIDER=azure`, `AZURE_OPENAI_ENDPOINT`, `AZURE_OPENAI_API_KEY`, `AZURE_OPENAI_DEPLOYMENT`, and `AZURE_OPENAI_API_VERSION` in the environment, the application will already call Azure OpenAI Service. This was implemented during POC but **was never tested end-to-end against a live Azure deployment**, and there is no provisioned Azure resource to point it at.

**What is missing**:

- No Azure AI Foundry Hub or Project has been provisioned
- No model has been deployed in Azure
- No secrets are stored in Azure Key Vault (keys are expected as environment variables only)
- The `LLMConfig.AzureConfig` class does not support the Azure AI Foundry serverless inference API (model catalogue deployments use a different URL pattern than Azure OpenAI)
- No integration test validates the Azure code path
- No monitoring or alerting on Azure-side token usage or errors
- No provider health check endpoint to confirm which LLM is live

---

## 4. Business Requirements for This Initiative

### BR-1: Multi-Provider Configurability (Must Have)
The system must support at minimum two LLM providers — Ollama (local/dev) and Azure AI Foundry (staging/production) — selectable via a single configuration value with no code changes required to switch between them.

### BR-2: Zero Regression on Existing Functionality (Must Have)
All three AI-driven features — intent extraction, keyword generation, product ranking — must produce functionally equivalent results whether running on Ollama or Azure AI Foundry. Existing API contracts must not change.

### BR-3: UAE Data Residency Compliance (Must Have)
All Azure AI Foundry resources must be provisioned in the **UAE North** region. If UAE North does not yet carry the required model (e.g., certain model catalogue entries), the fallback is **Sweden Central** as an interim until UAE North availability is confirmed.

> **Note for InfoSec review**: Model inference requests leave the UAE network boundary if hosted outside UAE North. This requires explicit InfoSec sign-off for non-UAE regions.

### BR-4: Secret Management (Must Have)
Azure API keys must not exist as plain-text environment variables in production. They must be stored in **Azure Key Vault** and injected at runtime. The Spring Boot application must be able to resolve secrets from Key Vault without any code change to the feature implementation.

### BR-5: Cost Transparency (Must Have)
Usage must be trackable per environment (dev, staging, production) using Azure resource tags and separate resource groups. Monthly token consumption reports must be exportable from Azure Monitor.

### BR-6: Graceful Failover (Should Have)
If the Azure AI Foundry endpoint returns a 5xx or timeout, the application must fall back to its existing keyword-based fallback logic (`getFallbackIntent`, `getFallbackKeywords`) without surfacing an error to the API consumer. This already exists in `LLMService.java` but must be validated against real Azure error responses.

### BR-7: Model Catalogue Support (Nice to Have — POC Phase 2)
Beyond Azure OpenAI models (GPT-4o, GPT-4o-mini), the configuration should be extensible to support Azure AI Foundry's serverless model catalogue deployments (Meta Llama 3.3, Microsoft Phi-4, Mistral Large). These use a different API endpoint pattern and would enable cost comparison between models.

### BR-8: Bilingual Model Validation (Nice to Have — aligned with I18N requirements)
The chosen Azure model must be validated for Arabic-language intent extraction quality. This feeds directly into the bilingual support requirement (I18N-1 through I18N-7) and affects model selection.

---

## 5. Phased Delivery Roadmap

### Phase 0 — Foundation (Pre-work, 1–2 days)
**Goal**: Provision the minimum Azure infrastructure for a working POC environment. No code changes.

Deliverables:
- Azure resource group created (`smartguide-poc-rg`) in UAE North
- Azure AI Foundry Hub provisioned
- Azure OpenAI Service (S0 tier) deployed under the Hub
- GPT-4o-mini model deployed (minimum cost; 128K context; JSON mode supported)
- Azure Key Vault created; Azure OpenAI API key stored as a secret
- Environment variable file (`.env.azure`) created locally for developer testing
- Confirmation that existing Azure code path in `LLMService.java` works against the live endpoint

Owner: DevOps / Infrastructure
Blocker: Azure subscription access, InfoSec approval for cloud LLM processing

---

### Phase 1 — Validated Azure Integration (Sprint 1, 3–5 days)
**Goal**: The application runs end-to-end in Azure mode with all three LLM features working and validated.

Deliverables:
- Integration test suite covering all three LLM operations (intent extraction, keyword generation, ranking) against a real Azure endpoint using a test API key (separate from production key)
- `LLMConfig` extended to hold a `timeout` field for Azure (currently missing — Ollama has it but Azure path uses hardcoded 30s)
- API version confirmed working with `gpt-4o-mini` deployment on `2024-08-01-preview` or `2025-01-01-preview`
- A `/api/admin/llm/status` endpoint (or equivalent health check) reporting which provider is active and its last-call status
- Application profile `azure` in `application-azure.yml` with all Azure-specific defaults
- Smoke test: `POST /api/v1/recommend` with `LLM_PROVIDER=azure` returns valid ranked results

Owner: Backend Team
Depends on: Phase 0 complete

---

### Phase 2 — Secret Management & Security Hardening (Sprint 1–2, 2–3 days)
**Goal**: Remove all API keys from environment variables in non-local environments; route through Azure Key Vault.

Deliverables:
- Spring Boot configured to read `AZURE_OPENAI_API_KEY` from Azure Key Vault using Managed Identity (no client secret in code)
- Azure Managed Identity assigned to the application's hosting environment (App Service or AKS)
- Key Vault access policy grants `get` and `list` permissions on secrets to the Managed Identity
- No API keys in `.env` files committed to version control
- InfoSec review checklist completed and signed off

Owner: Backend Team + InfoSec
Depends on: Phase 1 complete

---

### Phase 3 — Model Catalogue / Provider Extensibility (Sprint 2–3, 3–5 days)
**Goal**: Add support for Azure AI Foundry serverless model catalogue endpoints (non-OpenAI models), enabling model switching without code changes.

Deliverables:
- `LLMConfig` extended with a new `AzureFoundryConfig` section supporting the AI Model Inference API endpoint format
- `LLMService` refactored: the Azure implementation extracted into a dedicated `AzureOpenAIProvider` class; a new `AzureFoundryInferenceProvider` class added for model catalogue endpoints
- A strategy/factory pattern allows the provider to be selected at startup based on `app.llm.provider` value (`ollama` | `azure-openai` | `azure-foundry`)
- At least one model catalogue model tested: Microsoft Phi-4 (strong multilingual, very low cost, available in most regions)
- Documentation: a model comparison table covering GPT-4o-mini, Phi-4, and Llama 3.3 on cost, Arabic quality, JSON reliability, and latency

Owner: Backend Team
Depends on: Phase 2; Azure model catalogue access approved in subscription

---

### Phase 4 — Observability & Cost Governance (Sprint 3, 2 days)
**Goal**: Production operations team can monitor LLM usage, cost, and failure rates.

Deliverables:
- Azure Monitor diagnostic settings enabled on the Azure OpenAI resource
- Token usage logged per API call (input tokens, output tokens, model name) in application structured logs
- Spring Micrometer metric: `llm.call.duration`, `llm.tokens.used`, `llm.provider.name`
- Azure Cost Management budget alert set at 80% of monthly threshold
- Monthly cost estimate spreadsheet for: 10K recommendation queries/day, 500 keyword generations/day

Owner: DevOps + Backend Team
Depends on: Phase 1 complete

---

## 6. Acceptance Criteria Summary

| Phase | Acceptance Criteria |
|---|---|
| 0 | `curl -X POST {azure-endpoint}/openai/deployments/gpt-4o-mini/chat/completions` returns 200 with valid JSON |
| 1 | `POST /api/v1/recommend` with Azure provider returns a ranked product list with `reason` fields in under 5 seconds |
| 1 | Ollama path still works after all Phase 1 changes |
| 2 | Application starts and calls Azure API successfully using Managed Identity (no API key in environment or config file) |
| 3 | Switching `app.llm.provider` to `azure-foundry` and pointing at a Phi-4 serverless endpoint returns valid intent extraction results |
| 4 | Azure Monitor shows per-request token usage; Prometheus scrape shows `llm.call.duration` metric |

---

## 7. Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| UAE North region does not yet support required model (e.g., GPT-4o-mini) | Low | High | Use Sweden Central as interim; document as InfoSec exception; re-evaluate quarterly |
| Azure subscription quota limit on OpenAI TPM (tokens per minute) too low for load | Medium | Medium | Request quota increase during Phase 0; GPT-4o-mini default quota is 10M TPM in most regions |
| Managed Identity setup takes longer than expected due to bank IT policies | Medium | Medium | Use API key in Azure Key Vault as fallback during integration testing; Managed Identity as production target |
| Arabic language quality on GPT-4o-mini insufficient | Low | High | Evaluate Phi-4 as alternative in Phase 3; both support Modern Standard Arabic well |
| Cost overrun during development due to test traffic | Low | Low | Create a separate Azure OpenAI deployment (`smartguide-dev`) with a strict TPM quota for developer use |

---

## 8. Cost Guidance (POC Phase)

The architect's document contains detailed cost analysis. As a product framing: the minimum viable Azure AI Foundry setup for this POC requires only two billable Azure resources — Azure OpenAI Service (pay-per-token, no reservation) and Azure Key Vault (very low fixed cost). There are no GPUs to reserve, no containers to maintain, and no minimum monthly commitment.

For a POC with 1,000 recommendation queries per day (each using ~2,000 tokens input + ~300 tokens output on GPT-4o-mini), the monthly cost is approximately **USD 18–25**. This is substantially lower than the cost of a GPU VM capable of running a 13B-parameter Ollama model reliably.

---

## 9. Dependencies on Other Roadmap Items

This initiative feeds directly into the following items from the production upgrade plan (`analyst.md`):

- **AI-1 (Vector Embeddings)**: Azure AI Foundry provides the `text-embedding-3-small` model for generating pgvector embeddings. Phase 3 of this roadmap enables that capability.
- **AI-3 (Bilingual Embedding Model)**: Model validation in Phase 3 directly informs Arabic embedding model selection.
- **INFRA-7 (Terraform for Azure UAE North)**: The Terraform scripts documented in the architect's document are the foundation for all future Azure infrastructure (AKS, PostgreSQL, Key Vault).
- **SEC-8 (Secrets Management)**: Phase 2 of this roadmap directly satisfies SEC-8.
- **OBS-3 (Micrometer Metrics)**: Phase 4 metrics output aligns with OBS-3 and OBS-4.

---

## 10. Definition of Done

This initiative is complete when:

1. The application can switch between Ollama and Azure AI Foundry by changing a single environment variable (`LLM_PROVIDER`) with no code change and no restart-with-code-rebuild required
2. All three LLM features work end-to-end in both Azure and Ollama modes
3. Azure API keys are never stored as plain text in version control or environment variable files in staging/production
4. Integration tests cover both provider paths and run in CI
5. A developer README section documents how to configure and test each provider locally
