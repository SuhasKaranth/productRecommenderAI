# Azure AI Foundry Integration — Architect Specification

**Project**: Smart Guide — Banking Product Recommendation Engine
**Prepared by**: Solution Architect
**Date**: 2026-02-24
**Relates to**: `docs/requirements/azure-foundry-roadmap.md`

---

## 1. Azure AI Foundry — Platform Overview

Azure AI Foundry (previously called Azure AI Studio) is Microsoft's unified platform for building, deploying, and managing AI applications. For this project, it is relevant at three levels:

**Management plane** — The AI Foundry Hub and Project provide a governed workspace for organising model deployments, tracking usage, and managing access. It does not add latency or cost to inference calls.

**Azure OpenAI Service** — Deploys OpenAI models (GPT-4o, GPT-4o-mini, o1, text-embedding-3-small) in your Azure subscription. This is the primary inference path. The API is fully compatible with the existing `extractIntentAzure`, `generateKeywordsAzure`, and `rankProductsAzure` methods in `LLMService.java` — the endpoint URL format is identical.

**Model Catalogue (Serverless Inference)** — Deploys non-OpenAI models (Microsoft Phi-4, Meta Llama 3.3, Mistral Large 2411, Cohere Command R+) as managed endpoints billed per token. These endpoints use the **Azure AI Model Inference API** — a unified REST API compatible with all catalogue models — which has a slightly different URL structure from Azure OpenAI.

### Key Distinction from Current "Azure" Config

The existing codebase already targets Azure OpenAI Service (`{endpoint}/openai/deployments/{model}/chat/completions?api-version=...`). Deploying that same resource through Azure AI Foundry Hub changes nothing about the API call — only the provisioning method. The new capability this document adds is optional support for the Model Inference API for catalogue models (`{endpoint}/models/{model}/chat/completions` or `{endpoint}/chat/completions` for serverless).

---

## 2. Deployment Options and Cost Analysis

For a POC, the goal is minimum cost while maintaining production-quality inference. The relevant options are:

### Option A — Azure OpenAI Service (Recommended for POC)

Provision an Azure OpenAI resource of tier **S0** (Standard). This is pay-per-token with no reservation or minimum spend.

| Model | Input (per 1M tokens) | Output (per 1M tokens) | Context Window | JSON Mode | Arabic Quality |
|---|---|---|---|---|---|
| GPT-4o-mini | ~$0.15 | ~$0.60 | 128K | Yes | Excellent |
| GPT-4o | ~$2.50 | ~$10.00 | 128K | Yes | Excellent |
| o3-mini | ~$1.10 | ~$4.40 | 200K | Yes | Very Good |

**Recommended for POC: GPT-4o-mini** — sufficient quality for intent extraction and keyword generation at a fraction of GPT-4o cost. The existing 200-token output budget per call makes this very cheap.

**Estimated monthly cost for POC workload** (1,000 recommendations/day, 500 keyword generations/day):

| Operation | Calls/day | Avg input tokens | Avg output tokens | Monthly tokens | Monthly cost (GPT-4o-mini) |
|---|---|---|---|---|---|
| Intent extraction | 1,000 | 800 | 200 | 30M in / 6M out | $4.50 + $3.60 = $8.10 |
| Keyword generation | 500 | 400 | 150 | 6M in / 2.25M out | $0.90 + $1.35 = $2.25 |
| Product ranking | 200 | 3,000 | 800 | 18M in / 4.8M out | $2.70 + $2.88 = $5.58 |
| **Total** | | | | | **~$16/month** |

At 10× load (pre-production load testing), cost scales to ~$160/month — still well within POC budget thresholds.

### Option B — Model Catalogue Serverless (Alternative / Supplement)

Serverless endpoints for catalogue models are billed per token through Azure AI Foundry. No deployment reservation needed.

| Model | Input (per 1M tokens) | Output (per 1M tokens) | Notes |
|---|---|---|---|
| Microsoft Phi-4 | ~$0.07 | ~$0.14 | Best cost/quality ratio; strong multilingual; small context (16K) |
| Meta Llama 3.3 70B | ~$0.23 | ~$0.77 | Strong Arabic; 128K context; comparable to GPT-4o-mini |
| Mistral Large 2411 | ~$2.00 | ~$6.00 | Strong, but expensive for POC |

**Recommended for POC evaluation**: Phi-4 for keyword generation (low cost, 16K context is sufficient); GPT-4o-mini for ranking (needs large context for multi-product prompt).

### Option C — Provisioned Throughput (PTU)

PTU reserves dedicated capacity and significantly reduces per-token cost at high volume. Minimum unit is 1 PTU ≈ $2,016/month (GPT-4o). **Not appropriate for a POC** — use S0 pay-per-token instead. Revisit when monthly token spend exceeds $500.

### Option D — Ollama (Existing — Dev/Offline Only)

Retain Ollama for local development and CI environments where no internet or Azure subscription is available. The `LLM_PROVIDER=ollama` path must continue to work unchanged.

---

## 3. Infrastructure Architecture

### 3.1 Resource Topology

```
Azure Subscription (smartguide-poc)
│
├── Resource Group: smartguide-poc-rg (region: UAE North)
│   │
│   ├── Azure AI Foundry Hub: smartguide-hub
│   │   └── Project: smartguide-project
│   │       ├── Connection → Azure OpenAI Service
│   │       └── Connection → Azure Key Vault
│   │
│   ├── Azure OpenAI Service: smartguide-openai
│   │   ├── Deployment: gpt-4o-mini (Standard, 10M TPM)
│   │   └── Deployment: text-embedding-3-small (for future pgvector)
│   │
│   ├── Azure Key Vault: smartguide-kv
│   │   ├── Secret: AZURE-OPENAI-API-KEY
│   │   ├── Secret: DB-PASSWORD
│   │   └── Secret: API-KEY-ADMIN-1
│   │
│   └── (Future) Azure Container Apps / AKS — application runtime
│
└── Resource Group: smartguide-dev-rg (region: UAE North)
    └── Azure OpenAI Service: smartguide-openai-dev
        └── Deployment: gpt-4o-mini (Standard, 1M TPM — developer quota)
```

Separate resource groups for dev and production allow:
- Independent quota management (cap dev at 1M TPM to prevent accidental cost overrun)
- Independent RBAC assignments
- Cost reporting per environment tag

### 3.2 Network Considerations

For the POC phase, Azure OpenAI is accessed over the public endpoint with API key authentication. The Key Vault is also accessible over public endpoints with RBAC.

For production hardening (post-POC):
- Enable Azure Private Endpoints on Azure OpenAI and Key Vault
- Route traffic through Azure Virtual Network
- Restrict Key Vault to VNET only

### 3.3 Authentication Flow

**POC / Developer setup**: API key stored as a Key Vault secret, read by the application at startup via the Azure SDK's `SecretClient`. The key is never in environment variables on the server.

**Production target**: Managed Identity assigned to the application's hosting environment (Azure Container Apps or AKS Pod Identity). The application calls Key Vault using the Managed Identity — no static credentials anywhere.

```
App (Managed Identity) ──GET secret──▶ Azure Key Vault ──returns──▶ AZURE_OPENAI_API_KEY
App ──POST /chat/completions──▶ Azure OpenAI (api-key header)
```

---

## 4. Terraform Setup Scripts

The following Terraform configuration provisions the minimum POC infrastructure. All resources go to UAE North.

### 4.1 Directory Structure

```
infra/
├── terraform/
│   ├── poc/
│   │   ├── main.tf          # Core resources
│   │   ├── variables.tf     # Input variables
│   │   ├── outputs.tf       # Endpoint URLs, key vault name
│   │   ├── terraform.tfvars # Local values (gitignored)
│   │   └── providers.tf     # AzureRM provider config
│   └── modules/
│       ├── openai/          # Reusable Azure OpenAI module
│       └── keyvault/        # Reusable Key Vault module
```

### 4.2 `providers.tf`

```hcl
terraform {
  required_version = ">= 1.7.0"
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 3.110"
    }
    azapi = {
      source  = "azure/azapi"
      version = "~> 1.14"
    }
  }
  # Recommended: store state in Azure Blob Storage for team use
  # backend "azurerm" {
  #   resource_group_name  = "terraform-state-rg"
  #   storage_account_name = "smartguidetfstate"
  #   container_name       = "tfstate"
  #   key                  = "poc/terraform.tfstate"
  # }
}

provider "azurerm" {
  features {
    key_vault {
      purge_soft_delete_on_destroy    = false
      recover_soft_deleted_key_vaults = true
    }
    cognitive_account {
      purge_soft_delete_on_destroy = false
    }
  }
}

provider "azapi" {}
```

### 4.3 `variables.tf`

```hcl
variable "environment" {
  description = "Deployment environment: poc, staging, prod"
  type        = string
  default     = "poc"
}

variable "location" {
  description = "Azure region. UAE North is required for data residency."
  type        = string
  default     = "uaenorth"
}

variable "project_name" {
  description = "Short project name used in resource naming"
  type        = string
  default     = "smartguide"
}

variable "subscription_id" {
  description = "Azure subscription ID"
  type        = string
  sensitive   = true
}

variable "tenant_id" {
  description = "Azure AD tenant ID (for Key Vault access policies)"
  type        = string
  sensitive   = true
}

variable "developer_object_ids" {
  description = "Azure AD object IDs of developers who need Key Vault read access"
  type        = list(string)
  default     = []
}

variable "openai_sku" {
  description = "Azure OpenAI SKU. S0 is the only option for pay-per-use."
  type        = string
  default     = "S0"
}

variable "gpt4o_mini_tpm_quota" {
  description = "Tokens per minute quota for GPT-4o-mini deployment (in thousands)"
  type        = number
  default     = 10000  # 10M TPM — adjust based on expected load
}
```

### 4.4 `main.tf`

```hcl
locals {
  prefix = "${var.project_name}-${var.environment}"
  tags = {
    Project     = "SmartGuide"
    Environment = var.environment
    ManagedBy   = "Terraform"
    DataClass   = "Internal"
  }
}

# ── Resource Group ──────────────────────────────────────────────────────────
resource "azurerm_resource_group" "main" {
  name     = "${local.prefix}-rg"
  location = var.location
  tags     = local.tags
}

# ── Azure OpenAI Service ────────────────────────────────────────────────────
resource "azurerm_cognitive_account" "openai" {
  name                = "${local.prefix}-openai"
  resource_group_name = azurerm_resource_group.main.name
  location            = azurerm_resource_group.main.location
  kind                = "OpenAI"
  sku_name            = var.openai_sku
  tags                = local.tags

  # Disable public network access post-POC; enable private endpoint instead
  public_network_access_enabled = true

  identity {
    type = "SystemAssigned"
  }
}

# ── GPT-4o-mini Deployment ─────────────────────────────────────────────────
resource "azurerm_cognitive_deployment" "gpt4o_mini" {
  name                 = "gpt-4o-mini"
  cognitive_account_id = azurerm_cognitive_account.openai.id

  model {
    format  = "OpenAI"
    name    = "gpt-4o-mini"
    version = "2024-07-18"
  }

  scale {
    type     = "Standard"
    capacity = var.gpt4o_mini_tpm_quota
  }
}

# ── Text Embedding Deployment (for future pgvector use) ─────────────────────
resource "azurerm_cognitive_deployment" "embeddings" {
  name                 = "text-embedding-3-small"
  cognitive_account_id = azurerm_cognitive_account.openai.id

  model {
    format  = "OpenAI"
    name    = "text-embedding-3-small"
    version = "1"
  }

  scale {
    type     = "Standard"
    capacity = 120  # 120K TPM — sufficient for batch embedding generation
  }
}

# ── Azure AI Foundry Hub ────────────────────────────────────────────────────
# Uses azapi provider as azurerm does not yet have full AI Foundry Hub support
resource "azapi_resource" "ai_hub" {
  type      = "Microsoft.MachineLearningServices/workspaces@2024-04-01"
  name      = "${local.prefix}-hub"
  parent_id = azurerm_resource_group.main.id
  location  = azurerm_resource_group.main.location
  tags      = local.tags

  body = jsonencode({
    kind = "Hub"
    sku  = { name = "Basic" }
    identity = { type = "SystemAssigned" }
    properties = {
      description         = "SmartGuide AI Foundry Hub - ${var.environment}"
      publicNetworkAccess = "Enabled"  # Restrict post-POC
    }
  })

  response_export_values = ["identity.principalId"]
}

# ── Azure AI Foundry Project ────────────────────────────────────────────────
resource "azapi_resource" "ai_project" {
  type      = "Microsoft.MachineLearningServices/workspaces@2024-04-01"
  name      = "${local.prefix}-project"
  parent_id = azurerm_resource_group.main.id
  location  = azurerm_resource_group.main.location
  tags      = local.tags

  body = jsonencode({
    kind = "Project"
    sku  = { name = "Basic" }
    identity = { type = "SystemAssigned" }
    properties = {
      description  = "SmartGuide Recommendation Engine - ${var.environment}"
      hubResourceId = azapi_resource.ai_hub.id
    }
  })
}

# ── Azure Key Vault ─────────────────────────────────────────────────────────
data "azurerm_client_config" "current" {}

resource "azurerm_key_vault" "main" {
  name                        = "${local.prefix}-kv"
  resource_group_name         = azurerm_resource_group.main.name
  location                    = azurerm_resource_group.main.location
  sku_name                    = "standard"
  tenant_id                   = var.tenant_id
  soft_delete_retention_days  = 7
  purge_protection_enabled    = false  # Set true for production
  tags                        = local.tags

  # Enable RBAC authorization (preferred over legacy access policies)
  enable_rbac_authorization = true
}

# ── Key Vault — Developer Access ────────────────────────────────────────────
resource "azurerm_role_assignment" "kv_dev_access" {
  for_each             = toset(var.developer_object_ids)
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = each.value
}

# ── Key Vault — Terraform deployer access (to write secrets) ────────────────
resource "azurerm_role_assignment" "kv_terraform_officer" {
  scope                = azurerm_key_vault.main.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = data.azurerm_client_config.current.object_id
}

# ── Store OpenAI API Key in Key Vault ───────────────────────────────────────
resource "azurerm_key_vault_secret" "openai_api_key" {
  name         = "AZURE-OPENAI-API-KEY"
  value        = azurerm_cognitive_account.openai.primary_access_key
  key_vault_id = azurerm_key_vault.main.id

  depends_on = [azurerm_role_assignment.kv_terraform_officer]
}

# ── Budget Alert ────────────────────────────────────────────────────────────
resource "azurerm_consumption_budget_resource_group" "poc_budget" {
  name              = "${local.prefix}-monthly-budget"
  resource_group_id = azurerm_resource_group.main.id
  amount            = 100   # USD 100/month alert threshold
  time_grain        = "Monthly"

  time_period {
    start_date = formatdate("YYYY-MM-01'T'00:00:00Z", timestamp())
  }

  notification {
    enabled        = true
    threshold      = 80
    operator       = "GreaterThan"
    threshold_type = "Actual"
    contact_emails = ["devops@yourbank.ae"]
  }

  notification {
    enabled        = true
    threshold      = 100
    operator       = "GreaterThan"
    threshold_type = "Forecasted"
    contact_emails = ["devops@yourbank.ae"]
  }
}
```

### 4.5 `outputs.tf`

```hcl
output "openai_endpoint" {
  description = "Azure OpenAI Service endpoint URL — use as AZURE_OPENAI_ENDPOINT"
  value       = azurerm_cognitive_account.openai.endpoint
}

output "openai_resource_name" {
  description = "Azure OpenAI resource name"
  value       = azurerm_cognitive_account.openai.name
}

output "key_vault_name" {
  description = "Azure Key Vault name"
  value       = azurerm_key_vault.main.name
}

output "key_vault_uri" {
  description = "Azure Key Vault URI"
  value       = azurerm_key_vault.main.vault_uri
}

output "gpt4o_mini_deployment_name" {
  description = "GPT-4o-mini deployment name — use as AZURE_OPENAI_DEPLOYMENT"
  value       = azurerm_cognitive_deployment.gpt4o_mini.name
}

output "ai_hub_id" {
  description = "Azure AI Foundry Hub resource ID"
  value       = azapi_resource.ai_hub.id
}

output "recommended_api_version" {
  description = "Recommended API version for GPT-4o-mini"
  value       = "2025-01-01-preview"
}
```

### 4.6 `terraform.tfvars` (example — gitignore this file)

```hcl
# DO NOT commit this file. Add to .gitignore.
environment          = "poc"
location             = "uaenorth"
project_name         = "smartguide"
subscription_id      = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
tenant_id            = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
developer_object_ids = ["xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"]
gpt4o_mini_tpm_quota = 10000
```

### 4.7 Terraform Execution Sequence

```bash
# 1. Authenticate to Azure
az login
az account set --subscription "<your-subscription-id>"

# 2. Initialise Terraform
cd infra/terraform/poc
terraform init

# 3. Review the plan
terraform plan -var-file="terraform.tfvars" -out=poc.tfplan

# 4. Apply (creates all resources in ~3–5 minutes)
terraform apply poc.tfplan

# 5. Capture outputs for application configuration
terraform output -json > poc-outputs.json

# 6. Verify OpenAI endpoint
ENDPOINT=$(terraform output -raw openai_endpoint)
API_KEY=$(az keyvault secret show \
  --vault-name $(terraform output -raw key_vault_name) \
  --name AZURE-OPENAI-API-KEY \
  --query value -o tsv)

curl -X POST "${ENDPOINT}openai/deployments/gpt-4o-mini/chat/completions?api-version=2025-01-01-preview" \
  -H "api-key: ${API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"test"}],"max_tokens":10}'
```

---

## 5. Region Availability Notes

As of early 2026, Azure AI Foundry (UAE North) availability:

| Model | UAE North | Notes |
|---|---|---|
| GPT-4o-mini | ✅ Available | S0 Standard deployment |
| GPT-4o | ✅ Available | S0 Standard deployment |
| text-embedding-3-small | ✅ Available | Required for future pgvector work |
| o3-mini | 🟡 Check availability | May require Sweden Central as interim |
| Phi-4 (Model Catalogue) | 🟡 Check availability | Serverless; region availability varies |
| Llama 3.3 70B (Serverless) | 🟡 Check availability | May require Sweden Central |

> Always verify current availability at: `az cognitiveservices account list-skus --kind OpenAI --location uaenorth`

---

## 6. Application Integration Architecture

### 6.1 Current State in Code

The existing `LLMService.java` uses a flat `if/else` conditional on `llmConfig.getProvider()` checking for the string `"azure"` or `"ollama"`. The `LLMConfig.java` holds:

- `AzureConfig` — endpoint, apiKey, deploymentName, apiVersion
- `OllamaConfig` — host, model, timeout

**The Azure code path is already functionally correct for Azure OpenAI Service.** The endpoint format it constructs matches what Terraform will provision:

```
{endpoint}/openai/deployments/{deploymentName}/chat/completions?api-version={apiVersion}
```

### 6.2 Changes Required for POC Phase (Minimal — No Refactoring)

Only `application.yml` and environment variables need to change for the Azure OpenAI path to work with Foundry-provisioned resources:

```yaml
# application-azure.yml (new profile — no change to existing application.yml)
app:
  llm:
    provider: ${LLM_PROVIDER:azure}
    azure:
      endpoint: ${AZURE_OPENAI_ENDPOINT}           # from terraform output
      api-key: ${AZURE_OPENAI_API_KEY}             # from Key Vault
      deployment-name: ${AZURE_OPENAI_DEPLOYMENT:gpt-4o-mini}
      api-version: ${AZURE_OPENAI_API_VERSION:2025-01-01-preview}
      timeout-seconds: ${AZURE_OPENAI_TIMEOUT:30}  # currently hardcoded — should be added to AzureConfig
```

**Identified gap**: `LLMConfig.AzureConfig` does not have a `timeoutSeconds` field. The Azure code path uses a hardcoded `Duration.ofSeconds(30)` whereas Ollama has a configurable `timeout` field. This should be added when implementing.

### 6.3 Future State — Provider Abstraction (Phase 3)

For Phase 3 (Model Catalogue support), the architecture should evolve to a strategy pattern. This is a design recommendation — **do not implement until Phase 1 is validated**.

```
LLMService (orchestrator)
│
├── LLMProvider (interface)
│   ├── extractIntent(userInput, language)
│   ├── generateKeywords(prompt)
│   └── rankProducts(prompt)
│
├── OllamaProvider implements LLMProvider
├── AzureOpenAIProvider implements LLMProvider
└── AzureFoundryInferenceProvider implements LLMProvider
    (uses AI Model Inference API for model catalogue deployments)
```

The `LLMProviderFactory` (or Spring `@ConditionalOnProperty` beans) selects the correct implementation at startup based on `app.llm.provider`. Values would be: `ollama` | `azure-openai` | `azure-foundry`.

**Azure AI Model Inference API endpoint format** (for catalogue models):

```
# Standard Azure OpenAI endpoint (existing):
{endpoint}/openai/deployments/{deployment}/chat/completions?api-version={version}

# Azure AI Model Inference API (model catalogue):
{endpoint}/chat/completions          # for serverless deployments
# OR
{endpoint}/models/{model}/chat/completions  # for managed compute

# Authentication: "Authorization: Bearer {key}" instead of "api-key: {key}"
```

The request body format is identical (OpenAI-compatible schema), so the JSON building code in `LLMService.java` is reusable with only the URL and auth header changed.

### 6.4 Spring Boot Configuration for Key Vault (Phase 2)

To read secrets from Azure Key Vault at application startup, add the Spring Cloud Azure starter. This requires **no changes to `LLMService.java` or `LLMConfig.java`** — it injects Key Vault secrets as environment properties transparently.

**Maven dependency to add** (in `pom.xml`):
```xml
<dependency>
    <groupId>com.azure.spring</groupId>
    <artifactId>spring-cloud-azure-starter-keyvault-secrets</artifactId>
    <version>5.18.0</version>
</dependency>
```

**Configuration** (in `application-azure.yml`):
```yaml
spring:
  cloud:
    azure:
      keyvault:
        secret:
          enabled: true
          endpoint: ${AZURE_KEYVAULT_ENDPOINT}  # from terraform output key_vault_uri
          # Uses DefaultAzureCredential — works with Managed Identity in production
          # and with az login / environment variables locally
```

With this in place, `${AZURE_OPENAI_API_KEY}` in application properties is resolved from Key Vault automatically. The application's code is unchanged.

---

## 7. Environment Variable Reference

The following environment variables must be configured per environment. For production, all sensitive values come from Azure Key Vault and are never set directly.

| Variable | Description | POC (local) | Staging / Production |
|---|---|---|---|
| `LLM_PROVIDER` | Active LLM provider | `azure` | `azure` |
| `AZURE_OPENAI_ENDPOINT` | Azure OpenAI endpoint URL | From `terraform output` | From Key Vault or Config |
| `AZURE_OPENAI_API_KEY` | Azure OpenAI API key | From Key Vault (local CLI) | Via Managed Identity + Key Vault |
| `AZURE_OPENAI_DEPLOYMENT` | Model deployment name | `gpt-4o-mini` | `gpt-4o-mini` |
| `AZURE_OPENAI_API_VERSION` | API version string | `2025-01-01-preview` | `2025-01-01-preview` |
| `AZURE_KEYVAULT_ENDPOINT` | Key Vault URI | From `terraform output` | From environment config |
| `LLM_RANKING_TIMEOUT` | Ranking call timeout (ms) | `30000` | `20000` |

---

## 8. Security Architecture

### 8.1 Secrets Never in Code or Git

The following controls must be in place before any Azure credentials are used:

- `terraform.tfvars` must be in `.gitignore` (it contains subscription IDs)
- `.env.azure` (local developer config file) must be in `.gitignore`
- Azure OpenAI primary key is written to Key Vault by Terraform; no developer should need to view it directly
- CI/CD pipelines should use Managed Identity or federated credentials — never a static API key in pipeline variables

### 8.2 API Version Pinning

Always pin the `api-version` query parameter to a specific version, not `latest`. The current config has `2024-02-15-preview` which is outdated. Use `2025-01-01-preview` for GPT-4o-mini to get structured outputs support and the latest safety filters.

### 8.3 Content Safety

Azure OpenAI Service includes built-in content safety filters that run on every request. For a banking context:
- Default safety level is appropriate for POC
- Review Azure AI Content Safety dashboard monthly for any flagged inputs
- Sharia compliance checking (SHARIA-4 in analyst.md) is an application-level concern handled in `LLMService` prompts — content safety filters are a separate, complementary control

### 8.4 Data Residency

All Terraform resources target `uaenorth`. Verify post-deployment:

```bash
az cognitiveservices account show \
  --name smartguide-poc-openai \
  --resource-group smartguide-poc-rg \
  --query location
# Expected output: "uaenorth"
```

---

## 9. Diagnostic and Monitoring Setup

### 9.1 Azure Monitor Diagnostics (enable post-Phase 1)

```hcl
# Add to main.tf
resource "azurerm_monitor_diagnostic_setting" "openai" {
  name               = "openai-diagnostics"
  target_resource_id = azurerm_cognitive_account.openai.id
  log_analytics_workspace_id = azurerm_log_analytics_workspace.main.id  # create separately

  metric {
    category = "AllMetrics"
    enabled  = true
  }
}
```

### 9.2 Key Metrics to Monitor

From Azure Monitor:
- `TokenTransaction` — total tokens per model per hour (for cost tracking)
- `ServerErrors` — 5xx rate (trigger alert if > 1%)
- `RequestLatency` — P95 latency (alert if > 3 seconds)
- `RateLimitErrors` — 429 responses (indicates TPM quota needs increase)

From the Spring application (Micrometer — Phase 4):
- `llm.call.duration` tagged with `provider=azure`, `operation=intent|keywords|ranking`
- `llm.tokens.input` and `llm.tokens.output` (parsed from Azure response `usage` field)
- `llm.provider.active` gauge (1 = healthy, 0 = fallback mode active)

---

## 10. Quick-Start Checklist

Use this checklist to verify the Azure AI Foundry integration is operational end-to-end:

```
Phase 0 — Infrastructure
[ ] Azure subscription accessible; UAENorth quota confirmed
[ ] Terraform applied successfully; no errors
[ ] `terraform output openai_endpoint` returns a valid URL
[ ] API key stored in Key Vault; can be retrieved with `az keyvault secret show`
[ ] Direct curl to Azure OpenAI endpoint returns HTTP 200

Phase 1 — Application Integration
[ ] `application-azure.yml` profile created with correct values
[ ] Application starts with `--spring.profiles.active=azure`
[ ] `POST /api/v1/recommend` with Azure provider returns valid JSON
[ ] Ollama path still works (no regression)
[ ] LLM_PROVIDER can be toggled without rebuild

Phase 2 — Security
[ ] Spring Cloud Azure Key Vault starter added to pom.xml
[ ] Application resolves AZURE_OPENAI_API_KEY from Key Vault (not env var)
[ ] No API keys in any committed file
[ ] terraform.tfvars in .gitignore

Phase 3 — Model Catalogue (optional)
[ ] Phi-4 serverless endpoint created in AI Foundry Portal
[ ] New provider type `azure-foundry` tested for intent extraction
[ ] Model comparison results documented

Phase 4 — Observability
[ ] Azure Monitor diagnostics enabled
[ ] Token usage visible in Azure Portal
[ ] Micrometer metrics scraping confirmed
[ ] Budget alert configured
```

---

## 11. Known Limitations and Open Questions

**Model availability in UAE North**: Not all Azure AI Foundry model catalogue entries are available in UAE North. At the time of writing, Phi-4 and Llama 3.3 70B serverless may require Sweden Central. This must be verified against the live Azure Portal before committing to those models.

**API version lifecycle**: Azure OpenAI API versions have deprecation timelines. The `2024-02-15-preview` currently in `LLMConfig` was deprecated in late 2024. Update to `2025-01-01-preview` immediately. Set a calendar reminder to review the API version when planning each quarterly sprint.

**LLMConfig.AzureConfig missing timeout**: The `LLMConfig.AzureConfig` class does not have a `timeout` field — the timeout is hardcoded at 30 seconds in the Azure code paths. This is a small but important gap; cloud APIs benefit from tunable timeouts since the p99 latency can spike during service events. This must be added when implementing Phase 1.

**Structured output vs JSON mode**: The ranking prompt uses `"response_format": {"type": "json_object"}` which activates JSON mode (best-effort JSON but no schema enforcement). For production, consider the newer Structured Outputs feature (`"response_format": {"type": "json_schema", "json_schema": {...}}`) which enforces strict schema compliance. GPT-4o-mini supports this as of API version `2024-08-01-preview` and later.
