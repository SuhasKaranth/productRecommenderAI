# AI Enrichment at Scrape Time

## Problem Statement

The CSS selector scraping path (used for known banks like DIB) extracts structurally clean product data — name, URL, description, benefits — but produces no semantic metadata. `category`, `aiSuggestedCategory`, `aiConfidence`, and `keywords` all arrive as NULL in `staging_products`. Because `products.category` has a NOT NULL database constraint, every approval attempt fails. Products are stuck in staging indefinitely.

## Goal

Introduce an AI enrichment step immediately after CSS extraction and before saving to staging, so that every scraped product arrives in staging fully enriched and ready for admin review and approval.

---

## Backend Requirements

### Enrichment pipeline

- After CSS selector extraction produces a list of structured cards, run AI enrichment on each card before persisting to staging.
- Enrichment must be **synchronous** (blocking) — products must not be saved to staging until enrichment completes, to prevent NULL category entries that block approval.
- Enrichment runs in **batches of 5–8 cards per LLM call** to balance latency, token budget, and output quality consistency.

### Per-card enrichment output

For each card the LLM must produce:

| Field | Description |
|---|---|
| `category` | Islamic banking category from the existing taxonomy (see below) |
| `aiSuggestedCategory` | Same value — preserved as the AI's original suggestion even if admin later overrides `category` |
| `aiConfidence` | Decimal 0.0–1.0 representing the LLM's confidence in the category assignment |
| `keywords` | List of searchable keywords generated from product name, description, and benefits |

### Islamic banking category taxonomy

The LLM must classify into one of these categories (aligned with `RulesEngine`):

- `COVERED_CARDS`
- `DEBIT_CARDS`
- `CHARGE_CARDS`
- `HOME_FINANCE`
- `PERSONAL_FINANCE`
- `AUTO_FINANCE`
- `TAKAFUL`
- `SAVINGS`
- `CURRENT_ACCOUNTS`
- `INVESTMENTS`

### Sharia compliance check (per card)

During enrichment, the LLM must flag any product description or benefits text that contains non-Sharia-compliant terminology:

- `interest` → should be `profit rate`
- `loan` → should be `finance`
- `mortgage` → should be `home finance`
- `insurance` → should be `Takaful`

Flagged products are saved to staging with `reviewNotes` pre-populated with the specific terms found, so the admin can correct before approving.

### Failure handling

- If the LLM call fails for a batch, **save those cards to staging anyway** — never discard scraped data due to an LLM outage.
- Failed cards are saved with `aiConfidence = 0` and `reviewNotes = "AI enrichment failed — manual categorisation required"`.
- The enrichment failure must be reflected in the scrape response so the frontend can surface it in the progress log.

---

## Admin review workflow change

With AI enrichment in place, the admin's workflow changes from data entry to quality assurance:

| Before | After |
|---|---|
| Every product has NULL category — cannot approve any | Every product has a suggested category and confidence score |
| Admin must manually categorise each product | Admin confirms high-confidence items, intervenes only on low-confidence ones |
| Keywords must be generated separately per product | Keywords are pre-generated — product is searchable immediately after approval |
| No audit trail for categorisation decisions | `aiSuggestedCategory` + `aiConfidence` + admin override preserved for audit |

### Confidence thresholds

| `aiConfidence` | Admin action |
|---|---|
| ≥ 0.85 | Auto-highlighted as "ready to approve" — minimal review needed |
| 0.60 – 0.84 | Shown normally — admin should review category before approving |
| < 0.60 | Flagged with amber indicator — admin must verify before approving |

---

## Frontend Requirements

### Scrape dialog — multi-step progress UI

Replace the current spinner with a structured multi-step progress interface.

#### Step indicator

Use a **MUI Stepper** (horizontal) with three steps:

1. **Scraping** — Playwright fetches the bank page and extracts cards via CSS selectors
2. **AI Analysis** — LLM enriches each card with category, confidence score, and keywords
3. **Saving to Staging** — Enriched products are persisted

The active step is highlighted. Completed steps show a checkmark.

#### Step 1 — Scraping detail messages

Display these messages sequentially as they occur:

- "Connecting to [bank name] website..."
- "Loading cards listing page..."
- "Found [N] product cards via CSS selectors."

#### Step 2 — AI Analysis detail

This is the longest step. Show:

- **Determinate MUI LinearProgress bar** — advances as each batch completes. For N products in batches of 7, the bar moves in discrete jumps (e.g. 33% → 66% → 100% for 3 batches).
- **Scrollable log panel** with real-time messages naming the actual products:
  - `"Analysing batch 1 of 3: Al Islami Classic Charge Card, Cashback Covered Card, Premium Debit Card..."`
  - `"Batch 1 complete. 6 high confidence, 1 needs review."`
  - `"Analysing batch 2 of 3: Home Finance Murabaha, Auto Finance Ijarah..."`
  - `"AI enrichment failed for batch 3 — products saved without categorisation."`

#### Step 2 — Live results table

As each batch completes, add those products to a results table visible below the progress bar. The admin sees results arriving in real time while the process is still running.

**Columns:**

| Column | Details |
|---|---|
| Product Name | Clickable link — opens the product's `sourceUrl` in a new browser tab |
| Category | AI-suggested category |
| Confidence | Colour-coded MUI Chip: green (≥ 0.85), amber (0.60–0.84), red (< 0.60) |
| Keywords | Count of generated keywords (e.g. "8 keywords") |
| Sharia Flag | Warning icon if non-compliant terminology was detected |

#### Open product in new tab

Clicking the **product name** in the live results table (or in the staging review list) opens the product's `sourceUrl` in a new browser tab. This lets the admin verify the AI's categorisation directly against the bank's own product page without leaving the Smart Guide application.

- The link must open with `target="_blank"` and `rel="noopener noreferrer"`.
- If `sourceUrl` is null or blank, the product name is rendered as plain text (not a link).
- The feature applies in two places:
  1. The live results table during the scrape/AI analysis dialog
  2. The staging products list and detail view

#### Step 3 — Saving detail messages

- "Saving [N] enriched products to staging..."
- "Complete."

#### Cancellation

- A **Cancel** button is visible alongside the progress bar throughout Steps 1–3.
- On cancel: save any products already enriched to staging; discard unprocessed cards; show message: `"Cancelled. [N] of [total] products were processed and saved. [M] products were not processed."`

#### Completion state

On successful completion, replace the progress UI with a **MUI Alert (success)** summary card:

```
21 products scraped.
18 categorised with high confidence.
3 flagged for manual review.
0 enrichment failures.
```

With a prominent **"Review in Staging"** button that navigates to the staging products list pre-filtered to the current scrape batch (by `scrapedAt` timestamp or a batch ID).

---

## Audit and compliance

- `aiSuggestedCategory` is never overwritten after save — it preserves the AI's original suggestion permanently.
- `category` on the staging product is what the admin edits during review.
- On approval, both `aiSuggestedCategory` and `category` are copied to the `products` table, making the AI suggestion and the admin decision visible side-by-side in audit queries.
- Sharia compliance flags in `reviewNotes` are also copied to the approved product for downstream audit.

---

## Out of scope for this feature

- Changing the generic LLM flow (used for banks without CSS selector config) — that flow already enriches via AI.
- Real-time streaming of LLM token output to the frontend — batch completion events are sufficient.
- Automatic approval of high-confidence products — all products still require explicit admin approval.
