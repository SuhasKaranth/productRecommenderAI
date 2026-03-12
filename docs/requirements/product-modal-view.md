# Product Modal View — Requirements

**Feature ID**: ADMIN-UI-01
**Author**: Product Owner
**Date**: 2026-02-27
**Status**: Ready for architect design
**Related requirements**: ADMIN-1, ADMIN-9, AI-5, AI-6, SHARIA-2, SHARIA-6, TEST-2

---

## 1. User Story

**As a** product admin managing the banking product catalog,
**I want** to click any product row on the Product Listing Page and see an inline modal
with an editable summary and editable keywords — plus AI generation buttons for both —
**so that** I can review, enrich, and save product content without navigating away from
the listing.

### Acceptance Criteria

- [ ] Clicking any product row opens a modal/drawer. The listing remains visible beneath it.
- [ ] Modal displays the product name, category, and Islamic contract type as read-only context.
- [ ] Modal shows a `description` field (mapped to `Product.description`) as an editable multi-line text input labelled "Product Summary".
- [ ] Modal shows a keyword tag editor pre-populated from `Product.keywords` (text[] column).
- [ ] "Generate Summary" button calls the generate-summary endpoint (see Section 3) and populates the summary field with the AI result. Field remains editable after population.
- [ ] "Generate Keywords" button calls `POST /api/products/{id}/generate-keywords` and populates the keyword tag editor with the AI result. Tags remain editable after population.
- [ ] "Save" button persists summary changes via `PUT /api/products/{id}` and keywords via `POST /api/products/{id}/keywords` in a single user action (two sequential API calls).
- [ ] All AI-generated content is displayed to the admin before saving — no auto-save on generation.
- [ ] Modal can be dismissed via close button or pressing Escape. Unsaved changes prompt a confirmation dialog ("Discard unsaved changes?").
- [ ] While any API call is in flight, the triggering button shows a loading spinner and is disabled. The Save button is disabled during generation calls.
- [ ] On API error, an inline error message is shown inside the modal (not a full-page error). The admin can retry.
- [ ] No forbidden Sharia terms ("interest", "loan", "mortgage", "insurance", "conventional") may appear in AI-generated content surfaced in the modal. If detected client-side, display a warning banner: "Review required: this content may need Sharia terminology correction before saving."
- [ ] Modal is accessible: focus trap active while open, Escape closes, ARIA role="dialog" applied.

---

## 2. UI/UX Behaviour Spec

### 2.1 Trigger

- Target element: each row in the Product Listing table (existing `GET /api/products` data).
- Entire row is clickable. Row highlights on hover to indicate it is interactive.
- Action buttons on the row (e.g., delete) must NOT propagate the click to open the modal — use `stopPropagation`.

### 2.2 Modal / Drawer Layout

Component choice: MUI `Dialog` (modal) or MUI `Drawer` (right-side panel). Architect to decide based on screen real estate — both are acceptable to PO. Drawer preferred if the listing needs to remain fully visible for side-by-side reference.

Header:
- Product name (read-only, typography variant `h6`)
- Category + Islamic structure chip (read-only, MUI `Chip`)
- Close icon button (top-right)

Body — two sections, vertically stacked:

**Section A: Product Summary**
- Label: "Product Summary"
- Component: MUI `TextField` multiline, min 4 rows, max 10 rows, full width
- Bound to `product.description`
- Character limit: 1000 characters; show remaining count
- Below the field: "Generate Summary (AI)" button — MUI `Button` variant outlined, left-aligned

**Section B: Keywords**
- Label: "Keywords"
- Component: MUI `Autocomplete` in free-solo + multiple mode, rendered as chips (tag input)
- Bound to `product.keywords` (string array)
- Admin can type to add a new tag (press Enter or comma to confirm) and click the chip X to remove
- Below the field: "Generate Keywords (AI)" button — MUI `Button` variant outlined, left-aligned

Footer:
- Left: "Cancel" button (dismisses without saving, triggers discard confirmation if dirty)
- Right: "Save" button — MUI `Button` variant contained, primary colour — disabled during any in-flight call

### 2.3 State Machine

```
CLOSED
  -> [row click] -> LOADING_PRODUCT (fetch GET /api/products/{id} for latest data)
  -> OPEN_IDLE

OPEN_IDLE
  -> [Generate Summary click] -> GENERATING_SUMMARY
  -> [Generate Keywords click] -> GENERATING_KEYWORDS
  -> [Save click] -> SAVING
  -> [Cancel / Escape / overlay click] -> CONFIRM_DISCARD (if dirty) | CLOSED

GENERATING_SUMMARY
  -> [success] -> OPEN_IDLE (summary field populated, form marked dirty)
  -> [error] -> OPEN_ERROR (inline error, retry available)

GENERATING_KEYWORDS
  -> [success] -> OPEN_IDLE (keyword tags populated, form marked dirty)
  -> [error] -> OPEN_ERROR (inline error, retry available)

SAVING
  -> [success] -> CLOSED (success snackbar on listing page: "Product updated")
  -> [error] -> OPEN_ERROR (inline error, Save button re-enabled)

CONFIRM_DISCARD
  -> [confirm] -> CLOSED
  -> [cancel] -> OPEN_IDLE
```

### 2.4 Loading States

| Action | Button state | Field state |
|---|---|---|
| Fetching product on row click | Row shows spinner, row click disabled | Modal not yet open |
| Generating summary | "Generate Summary" shows CircularProgress, disabled | Summary field disabled |
| Generating keywords | "Generate Keywords" shows CircularProgress, disabled | Keyword editor disabled |
| Saving | "Save" shows CircularProgress, disabled | Both fields disabled |

### 2.5 Error States

- Network / 5xx: "Something went wrong. Please try again." with Retry button.
- 404 on product fetch: "Product not found." Modal does not open.
- 400 on save: Display validation message from API response body.
- Sharia term detected in AI output (client-side scan): Warning banner inside modal, Save is NOT blocked — admin decides whether to edit or save as-is (audit responsibility is on the admin workflow, not the UI gate).

---

## 3. Data Contract

### 3.1 Fields Read and Written

| Field | Source | Editable in modal | Saved via |
|---|---|---|---|
| `id` | `ProductDTO.id` | No | Path param |
| `productName` | `ProductDTO.productName` | No (display only) | — |
| `category` | `ProductDTO.category` | No (display only) | — |
| `islamicStructure` | `ProductDTO.islamicStructure` | No (display only) | — |
| `description` | `ProductDTO.description` | Yes — "Product Summary" | `PUT /api/products/{id}` |
| `keywords` | `ProductDTO.keywords` | Yes — keyword tags | `POST /api/products/{id}/keywords` |

All other `ProductDTO` fields are ignored by the modal. They must not be mutated
or included in the `PUT` body unless the backend performs a full-replace (architect
to confirm whether `PUT /api/products/{id}` is partial or full update — see Open
Questions).

### 3.2 Endpoints Called

**Fetch product on modal open**
```
GET /api/products/{id}
Response: ProductDTO (200) | 404
```

**Generate Summary (AI) — new endpoint required**
```
POST /api/products/{id}/generate-summary
Request body: none
Response 200:
{
  "summary": "<AI-generated text, max 1000 chars>",
  "message": "Summary generated successfully"
}
Response 404: product not found
Response 500: LLM call failed
```
This endpoint does NOT exist yet. It must be added to `ProductController` and
`ProductService`. Backend must call `LLMService` with a prompt that:
- Uses `productName`, `category`, `islamicStructure`, `description`, `keyBenefits`
  as context
- Explicitly instructs the LLM to use Sharia-compliant terminology (profit rate,
  not interest; finance, not loan; Takaful, not insurance)
- Produces a concise 2-4 sentence customer-facing summary in English
- Maps to requirement AI-5 (Sharia prompt guidelines)

**Generate Keywords (AI) — existing endpoint**
```
POST /api/products/{id}/generate-keywords
Request body: none
Response 200:
{
  "keywords": ["keyword1", "keyword2", ...],
  "message": "Keywords generated successfully"
}
```

**Save keywords**
```
POST /api/products/{id}/keywords
Request body: { "keywords": ["keyword1", "keyword2", ...] }
Response 200: ProductDTO
```

**Save summary (description)**
```
PUT /api/products/{id}
Request body: { "description": "<edited text>" }
Response 200: ProductDTO
```
Send only the `description` field in the PUT body. The existing `updateProduct`
method applies null-safe patching — fields absent from the body are not overwritten.

### 3.3 Save Sequence

On "Save" click, the frontend executes these calls sequentially (not in parallel),
stopping on first error:

1. `POST /api/products/{id}/keywords` — save keywords
2. `PUT /api/products/{id}` — save description (summary)

On success of both: close modal, show MUI `Snackbar` on listing page.

---

## 4. Out of Scope

The following are explicitly deferred and must not be built in this feature:

- Arabic (`_ar`) variants of summary or keywords — deferred to I18N-1 through I18N-3 sprint
- Sharia review flag toggle (`sharia_review_flag`) — deferred to SHARIA-2 / DATA-4
- Product lifecycle status change from inside the modal — deferred to FEAT-7
- Staging product modal (separate UI on the admin staging page) — separate story
- Diff view comparing current vs scraped version — deferred to ADMIN-1
- Match explanation field (`matchExplanation`) in the modal — this is a recommendation
  response field, not a product catalog field; separate concern
- Promotion / featured flag editing — deferred to FEAT-4
- Bulk summary or keyword generation for multiple products simultaneously

---

## 5. Open Questions for Architect

| # | Question | Why it matters | Owner |
|---|---|---|---|
| OQ-1 | Does `PUT /api/products/{id}` perform a full entity replace or a null-safe partial update? If full replace, sending only `description` will null out all other fields. Confirm the existing behaviour in `ProductController.updateProduct()` before the frontend calls it. | Determines whether frontend must fetch and re-send all fields, or can send only changed fields. | Architect / Backend |
| OQ-2 | Should the two Save calls (keywords + description) be atomic? If the keywords save succeeds but the description save fails, the product is in a partially-updated state. Consider a backend `PATCH /api/products/{id}/content` endpoint that saves both in one transaction. | Data consistency on save failure. | Architect |
| OQ-3 | Is there an existing `generateSummary` method in `LLMService`, or must it be added? Current codebase has `generateKeywordsFromMap` but no summary equivalent. Confirm before assigning backend story. | Scopes backend work for the new `POST /api/products/{id}/generate-summary` endpoint. | Architect / Backend |
| OQ-4 | What is the intended max length for `Product.description`? The column is `TEXT` (unbounded in PostgreSQL). The UI enforces 1000 characters — confirm this aligns with how the recommendation engine uses description for embedding/ranking. | Prevents overly long summaries degrading LLM prompt quality. | Architect / AI lead |
| OQ-5 | Should the Sharia term scan (client-side warning) also trigger a backend validation on `POST /api/products/{id}/generate-summary`? If AI-5 and SHARIA-6 are implemented before this feature, the backend may already reject non-compliant outputs. Coordinate with those sprints. | Avoids duplicate validation logic or conflicting error surfaces. | Architect / Compliance |
| OQ-6 | Should the product listing page fetch the full `ProductDTO` per row (including `keywords` and `description`), or should opening the modal trigger a fresh `GET /api/products/{id}` to get the latest data? The listing may cache stale data if another admin edited the product. | Determines whether a second network call on modal open is required. | Architect / Frontend |
