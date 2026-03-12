# Technical Specification: Product Modal View (ADMIN-UI-01)

**Feature ID**: ADMIN-UI-01
**Requirements source**: `docs/requirements/product-modal-view.md`
**Status**: Ready for implementation
**Date**: 2026-02-27

---

## Resolved Open Questions

These questions from the requirements document are answered here so the developer
does not need to stop and ask.

| OQ | Decision |
|----|----------|
| OQ-1 | `PUT /api/products/{id}` is a null-safe partial update. Lines 99-126 in `ProductController.java` confirm that every field is guarded by a null check before assignment. The frontend MUST send only the `description` field in the PUT body. Do not resend the full DTO. |
| OQ-2 | No backend transaction wrapping the two Save calls. The frontend calls them sequentially (keywords first, then description). On keyword-save success + description-save failure the product has updated keywords but the old description — this is the accepted behaviour for this sprint. A single-transaction `PATCH` endpoint is deferred to a future story. |
| OQ-3 | `LLMService` has no `generateSummary` method. It must be added alongside a corresponding `generateSummary(Long id)` method in `ProductService`. Scope confirmed here. |
| OQ-4 | The `description` column is `TEXT` (unbounded). The UI enforces 1000 characters. No backend length constraint is added in this sprint; the database will accept larger values if entered through other paths. |
| OQ-5 | No backend Sharia validation on the generate-summary response in this sprint. The client-side warning banner (described in the requirements) is the only guard. Backend validation is deferred until SHARIA-6 is implemented. |
| OQ-6 | On row click, the modal triggers a fresh `GET /api/products/{id}` to guarantee the admin sees the latest server state, not a potentially stale list snapshot. This adds one network call but prevents stale-read edits. |

---

## 1. Backend Changes

### 1.1 New Endpoint: Generate Summary

**File to modify**: `src/main/java/com/smartguide/poc/controller/ProductController.java`

Add the following method to the existing `ProductController` class. The existing
`generateKeywords` handler at line 183 is the direct pattern to follow.

```java
/**
 * Generate a Sharia-compliant product summary using LLM.
 * POST /api/products/{id}/generate-summary
 */
@PostMapping("/{id}/generate-summary")
public ResponseEntity<Map<String, Object>> generateSummary(@PathVariable Long id) {
    log.info("Generating summary for product id: {}", id);

    try {
        String summary = productService.generateSummary(id);

        Map<String, Object> response = new HashMap<>();
        response.put("summary", summary);
        response.put("message", "Summary generated successfully");

        return ResponseEntity.ok(response);
    } catch (RuntimeException e) {
        log.error("Product not found when generating summary for id {}: {}", id, e.getMessage());
        return ResponseEntity.notFound().build();
    } catch (Exception e) {
        log.error("Error generating summary for product {}: {}", id, e.getMessage());
        Map<String, Object> error = new HashMap<>();
        error.put("error", "Failed to generate summary: " + e.getMessage());
        return ResponseEntity.internalServerError().body(error);
    }
}
```

The RuntimeException catch maps to 404 (product not found). The Exception catch
maps to 500 (LLM failure). This matches the exact pattern used for `generateKeywords`.

**Security**: The endpoint sits under `/api/products/**` with HTTP method `POST`.
`SecurityConfig` at line 114 already requires `SCOPE_admin:*` for all POST requests
under `/api/products/**`. No security change is needed.

---

### 1.2 New Service Method: generateSummary

**File to modify**: `src/main/java/com/smartguide/poc/service/ProductService.java`

Add the following method to the existing `ProductService` class. Place it after
the existing `generateKeywords(Long id)` method at line 425.

```java
/**
 * Generate a Sharia-compliant customer-facing summary for a product using LLM.
 *
 * @param id product ID
 * @return AI-generated summary string, max 1000 characters
 * @throws RuntimeException if the product is not found
 */
public String generateSummary(Long id) {
    Product product = productRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Product not found: " + id));

    log.info("Generating summary for product: {}", product.getProductName());

    Map<String, Object> productData = new HashMap<>();
    productData.put("productName", product.getProductName());
    productData.put("category", product.getCategory());
    productData.put("islamicStructure", product.getIslamicStructure());
    productData.put("description", product.getDescription());
    productData.put("keyBenefits", product.getKeyBenefits());

    String summary = llmService.generateSummaryFromMap(productData);
    log.info("Generated summary ({} chars) for product: {}",
            summary != null ? summary.length() : 0, product.getProductName());

    return summary;
}
```

---

### 1.3 New LLMService Method: generateSummaryFromMap

**File to modify**: `src/main/java/com/smartguide/poc/service/LLMService.java`

Add the following two methods to `LLMService`. The pattern follows
`generateKeywordsFromMap` (line 573) exactly — same provider dispatch,
same Azure/Ollama private methods, different prompt and parsing.

Place these methods after `generateKeywordsFromMap` (before `getFallbackKeywords`).

```java
/**
 * System prompt for summary generation. Declared as a constant rather than a
 * @Value field because there is no need for operator override at this stage.
 * Sharia-compliant terminology is mandated explicitly in the prompt.
 */
private static final String SUMMARY_SYSTEM_PROMPT = """
        You are an expert in Islamic finance. Write a concise, customer-facing product
        summary for a Sharia-compliant banking product.

        REQUIREMENTS:
        - Length: 2-4 sentences, no more than 1000 characters total.
        - Audience: Retail bank customers in the UAE.
        - Tone: Clear, professional, welcoming.
        - Language: English only.

        SHARIA TERMINOLOGY RULES (mandatory):
        - Use "profit rate" — never "interest" or "interest rate".
        - Use "finance" — never "loan".
        - Use "Takaful" — never "insurance".
        - Use "home finance" — never "mortgage".
        - Do not use the word "conventional".

        Return ONLY the summary text. No JSON wrapper, no preamble, no title.
        """;

/**
 * Generate a product summary from a product data map (for approved products).
 *
 * @param productData map containing productName, category, islamicStructure,
 *                    description, keyBenefits
 * @return summary string, never null — falls back to a template string on LLM failure
 */
public String generateSummaryFromMap(Map<String, Object> productData) {
    try {
        String userPrompt = buildSummaryPrompt(productData);

        String summary;
        if ("azure".equalsIgnoreCase(llmConfig.getProvider())) {
            summary = generateSummaryAzure(userPrompt);
        } else if ("ollama".equalsIgnoreCase(llmConfig.getProvider())) {
            summary = generateSummaryOllama(userPrompt);
        } else {
            throw new IllegalArgumentException("Unknown LLM provider: " + llmConfig.getProvider());
        }

        if (summary == null || summary.isBlank()) {
            log.warn("LLM returned blank summary, using fallback");
            return getFallbackSummary(productData);
        }

        // Truncate to 1000 chars to match UI constraint
        if (summary.length() > 1000) {
            summary = summary.substring(0, 997) + "...";
        }

        return summary;
    } catch (Exception e) {
        log.error("Failed to generate summary: {}", e.getMessage(), e);
        return getFallbackSummary(productData);
    }
}

private String buildSummaryPrompt(Map<String, Object> productData) {
    StringBuilder prompt = new StringBuilder();
    prompt.append("Write a customer-facing product summary.\n\n");
    prompt.append("Product details:\n");
    prompt.append("- Name: ").append(productData.get("productName")).append("\n");
    if (productData.get("category") != null) {
        prompt.append("- Category: ").append(productData.get("category")).append("\n");
    }
    if (productData.get("islamicStructure") != null) {
        prompt.append("- Islamic Structure: ").append(productData.get("islamicStructure")).append("\n");
    }
    if (productData.get("description") != null) {
        prompt.append("- Existing description: ").append(productData.get("description")).append("\n");
    }
    if (productData.get("keyBenefits") != null) {
        prompt.append("- Key Benefits: ").append(productData.get("keyBenefits")).append("\n");
    }
    return prompt.toString();
}

private String generateSummaryAzure(String userPrompt) throws Exception {
    String url = String.format("%s/openai/deployments/%s/chat/completions?api-version=%s",
            llmConfig.getAzure().getEndpoint(),
            llmConfig.getAzure().getDeploymentName(),
            llmConfig.getAzure().getApiVersion());

    Map<String, Object> requestBody = new HashMap<>();
    requestBody.put("messages", Arrays.asList(
            Map.of("role", "system", "content", SUMMARY_SYSTEM_PROMPT),
            Map.of("role", "user", "content", userPrompt)
    ));
    requestBody.put("temperature", 0.5);
    requestBody.put("max_tokens", 300);

    String response = webClient.post()
            .uri(url)
            .header("api-key", llmConfig.getAzure().getApiKey())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .block();

    JsonNode root = objectMapper.readTree(response);
    return root.path("choices").get(0).path("message").path("content").asText().trim();
}

private String generateSummaryOllama(String userPrompt) throws Exception {
    String url = llmConfig.getOllama().getHost() + "/api/generate";
    String fullPrompt = SUMMARY_SYSTEM_PROMPT + "\n\n" + userPrompt;

    Map<String, Object> requestBody = new HashMap<>();
    requestBody.put("model", llmConfig.getOllama().getModel());
    requestBody.put("prompt", fullPrompt);
    requestBody.put("stream", false);
    requestBody.put("options", Map.of(
            "temperature", 0.5,
            "num_predict", 300
    ));

    String response = webClient.post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .block();

    JsonNode root = objectMapper.readTree(response);
    return root.path("response").asText().trim();
}

private String getFallbackSummary(Map<String, Object> productData) {
    String name = productData.get("productName") != null
            ? productData.get("productName").toString() : "This product";
    String structure = productData.get("islamicStructure") != null
            ? productData.get("islamicStructure").toString() : "Sharia-compliant";
    return String.format(
            "%s is a %s banking product designed for customers seeking ethical financial solutions. "
            + "It offers transparent, Sharia-compliant terms with no hidden charges.",
            name, structure);
}
```

**Note on `timeoutSeconds`**: `LLMService` already declares `@Value("${app.keywords.generation.timeout-seconds:30}") private int timeoutSeconds` at line 82. The summary methods reuse this field. No new configuration key is required.

---

### 1.4 Configuration Changes

No new `application.yml` keys are required. The summary generation reuses:
- `app.llm.provider`
- `app.llm.azure.*`
- `app.llm.ollama.*`
- `app.keywords.generation.timeout-seconds`

---

### 1.5 Flyway Migration

No schema migration is required. All fields used by the modal (`description`,
`keywords`, `product_name`, `category`, `islamic_structure`) already exist in the
`products` table. The last migration is `V10__Add_keywords_to_products.sql`.
The next migration, if needed by a future story, must be `V11__*.sql`.

---

## 2. Frontend Changes

### 2.1 New File: ProductEditDrawer Component

**Create**: `frontend/src/components/ProductEditDrawer.jsx`

This is a standalone component. `AllProducts.jsx` mounts it and passes the selected
product ID via props. The component owns all modal-internal state.

**Component signature**:
```jsx
// Props:
// - productId: number | null  — null means closed
// - onClose: () => void       — called when the drawer should close (after save or cancel)
// - onSaveSuccess: () => void — called after a successful save; triggers list refresh in parent
const ProductEditDrawer = ({ productId, onClose, onSaveSuccess }) => { ... }
```

**State shape** (all local to the component via `useState`):

```js
const [modalState, setModalState] = useState('CLOSED');
// Values: 'CLOSED' | 'LOADING_PRODUCT' | 'OPEN_IDLE' | 'GENERATING_SUMMARY'
//         | 'GENERATING_KEYWORDS' | 'SAVING' | 'OPEN_ERROR' | 'CONFIRM_DISCARD'

const [product, setProduct] = useState(null);           // ProductDTO from GET /api/products/{id}
const [summary, setSummary] = useState('');             // bound to description TextField
const [keywords, setKeywords] = useState([]);           // bound to Autocomplete tags
const [isDirty, setIsDirty] = useState(false);
const [errorMessage, setErrorMessage] = useState(null); // inline error string
const [retryAction, setRetryAction] = useState(null);   // function to call on Retry click
```

**State machine transitions**:

```
productId changes to non-null  ->  LOADING_PRODUCT
  fetch GET /api/products/{id}
  on success  ->  OPEN_IDLE (populate product, summary, keywords from response)
  on 404      ->  CLOSED (do not open, optionally show parent-level snackbar)
  on error    ->  CLOSED

"Generate Summary" click (OPEN_IDLE)  ->  GENERATING_SUMMARY
  POST /api/products/{id}/generate-summary
  on success  ->  OPEN_IDLE, setSummary(result.summary), setIsDirty(true)
  on error    ->  OPEN_ERROR, setRetryAction(() => handleGenerateSummary)

"Generate Keywords" click (OPEN_IDLE)  ->  GENERATING_KEYWORDS
  POST /api/products/{id}/generate-keywords
  on success  ->  OPEN_IDLE, setKeywords(result.keywords), setIsDirty(true)
  on error    ->  OPEN_ERROR, setRetryAction(() => handleGenerateKeywords)

"Save" click (OPEN_IDLE)  ->  SAVING
  sequential: POST /api/products/{id}/keywords  then  PUT /api/products/{id}
  on both success  ->  CLOSED, onSaveSuccess()
  on any error     ->  OPEN_ERROR, setRetryAction(null) [no auto-retry for save]

"Cancel" click or Escape or overlay click:
  if isDirty  ->  CONFIRM_DISCARD
  else        ->  CLOSED, onClose()

CONFIRM_DISCARD "Confirm"  ->  CLOSED, onClose()
CONFIRM_DISCARD "Cancel"   ->  OPEN_IDLE
```

**Sharia term scan** (client-side only):

```js
const SHARIA_FORBIDDEN_TERMS = ['interest', 'loan', 'mortgage', 'insurance', 'conventional'];

const containsForbiddenTerms = (text) =>
  SHARIA_FORBIDDEN_TERMS.some(term => text.toLowerCase().includes(term));
```

Run this check after any AI generation populates the `summary` field. If true,
show an MUI `Alert severity="warning"` inside the drawer body:
`"Review required: this content may need Sharia terminology correction before saving."`
Save is NOT blocked. The warning disappears as soon as the summary no longer contains
the flagged term.

**Drawer layout** (use MUI `Drawer` with `anchor="right"`, `PaperProps={{ sx: { width: 480 } }}`):

```
<Drawer anchor="right" open={modalState !== 'CLOSED'} onClose={handleCloseRequest}>
  <Box role="dialog" aria-modal="true" aria-label="Edit Product">

    {/* Header */}
    <Box sx={{ p: 2, borderBottom: 1, borderColor: 'divider' }}>
      <Typography variant="h6">{product?.productName}</Typography>
      <Box display="flex" gap={1} mt={0.5}>
        <Chip label={product?.category} size="small" />
        {product?.islamicStructure && (
          <Chip label={product.islamicStructure} size="small" variant="outlined" />
        )}
      </Box>
      <IconButton
        aria-label="Close"
        onClick={handleCloseRequest}
        sx={{ position: 'absolute', top: 8, right: 8 }}
      >
        <CloseIcon />
      </IconButton>
    </Box>

    {/* Loading state */}
    {modalState === 'LOADING_PRODUCT' && <CircularProgress />}

    {/* Body — visible in OPEN_IDLE, GENERATING_*, SAVING, OPEN_ERROR */}
    {product && modalState !== 'LOADING_PRODUCT' && (
      <Box sx={{ p: 2, overflowY: 'auto', flexGrow: 1 }}>

        {/* Inline error */}
        {modalState === 'OPEN_ERROR' && (
          <Alert severity="error" action={
            retryAction && <Button onClick={retryAction}>Retry</Button>
          }>
            {errorMessage}
          </Alert>
        )}

        {/* Sharia warning */}
        {containsForbiddenTerms(summary) && (
          <Alert severity="warning" sx={{ mb: 2 }}>
            Review required: this content may need Sharia terminology correction
            before saving.
          </Alert>
        )}

        {/* Section A: Product Summary */}
        <Typography variant="subtitle2" gutterBottom>Product Summary</Typography>
        <TextField
          multiline
          minRows={4}
          maxRows={10}
          fullWidth
          value={summary}
          onChange={e => { setSummary(e.target.value); setIsDirty(true); }}
          inputProps={{ maxLength: 1000 }}
          helperText={`${summary.length}/1000 characters`}
          disabled={modalState === 'GENERATING_SUMMARY' || modalState === 'SAVING'}
        />
        <Button
          variant="outlined"
          size="small"
          sx={{ mt: 1 }}
          onClick={handleGenerateSummary}
          disabled={modalState !== 'OPEN_IDLE' && modalState !== 'OPEN_ERROR'}
          startIcon={modalState === 'GENERATING_SUMMARY' ? <CircularProgress size={16} /> : null}
        >
          {modalState === 'GENERATING_SUMMARY' ? 'Generating...' : 'Generate Summary (AI)'}
        </Button>

        {/* Section B: Keywords */}
        <Typography variant="subtitle2" gutterBottom sx={{ mt: 3 }}>Keywords</Typography>
        <Autocomplete
          multiple
          freeSolo
          options={[]}
          value={keywords}
          onChange={(_, newValue) => { setKeywords(newValue); setIsDirty(true); }}
          disabled={modalState === 'GENERATING_KEYWORDS' || modalState === 'SAVING'}
          renderTags={(value, getTagProps) =>
            value.map((option, index) => (
              <Chip label={option} size="small" {...getTagProps({ index })} />
            ))
          }
          renderInput={params => (
            <TextField {...params} placeholder="Type and press Enter to add" fullWidth />
          )}
        />
        <Button
          variant="outlined"
          size="small"
          sx={{ mt: 1 }}
          onClick={handleGenerateKeywords}
          disabled={modalState !== 'OPEN_IDLE' && modalState !== 'OPEN_ERROR'}
          startIcon={modalState === 'GENERATING_KEYWORDS' ? <CircularProgress size={16} /> : null}
        >
          {modalState === 'GENERATING_KEYWORDS' ? 'Generating...' : 'Generate Keywords (AI)'}
        </Button>
      </Box>
    )}

    {/* Footer */}
    <Box sx={{ p: 2, borderTop: 1, borderColor: 'divider', display: 'flex', justifyContent: 'space-between' }}>
      <Button onClick={handleCloseRequest} disabled={modalState === 'SAVING'}>Cancel</Button>
      <Button
        variant="contained"
        onClick={handleSave}
        disabled={modalState !== 'OPEN_IDLE' && modalState !== 'OPEN_ERROR'}
      >
        {modalState === 'SAVING' ? <CircularProgress size={20} /> : 'Save'}
      </Button>
    </Box>

  </Box>
</Drawer>

{/* Discard confirmation */}
<Dialog open={modalState === 'CONFIRM_DISCARD'}>
  <DialogTitle>Discard unsaved changes?</DialogTitle>
  <DialogActions>
    <Button onClick={() => setModalState('OPEN_IDLE')}>Keep Editing</Button>
    <Button variant="contained" color="error" onClick={() => { setModalState('CLOSED'); onClose(); }}>
      Discard
    </Button>
  </DialogActions>
</Dialog>
```

**Accessibility requirements**:
- `Drawer` root `Box` must carry `role="dialog"`, `aria-modal="true"`, and `aria-label="Edit Product"`.
- Focus must move to the first interactive element (the close button or the summary field) when the drawer opens. Use `autoFocus` on the close IconButton or use a `useEffect` + `ref.focus()`.
- Escape key is handled natively by MUI `Drawer` when `onClose` is wired up. The `handleCloseRequest` function must check `isDirty` before calling `onClose()`.

**MUI imports needed** (all already in `package.json` `@mui/material ^5.14.0`):
`Drawer`, `Autocomplete`, `Alert`, `Snackbar`, `CircularProgress`, `Chip`,
`Dialog`, `DialogTitle`, `DialogActions`, `IconButton`, `TextField`, `Button`,
`Box`, `Typography`.

New icon needed: `CloseIcon` from `@mui/icons-material`. Already installed.

---

### 2.2 Modify: AllProducts.jsx

**File to modify**: `frontend/src/pages/AllProducts.jsx`

**Changes required** (all additive, no removal of existing behaviour):

1. Import `ProductEditDrawer`:
   ```js
   import ProductEditDrawer from '../components/ProductEditDrawer';
   ```

2. Add two new state variables:
   ```js
   const [drawerProductId, setDrawerProductId] = useState(null);
   const [successSnackbarOpen, setSuccessSnackbarOpen] = useState(false);
   ```

3. Add two new handlers:
   ```js
   const handleRowClick = (productId) => {
     setDrawerProductId(productId);
   };

   const handleDrawerClose = () => {
     setDrawerProductId(null);
   };

   const handleDrawerSaveSuccess = () => {
     setDrawerProductId(null);
     setSuccessSnackbarOpen(true);
     fetchProducts(); // refresh the listing
   };
   ```

4. On the `<TableRow>` element (currently line 348), add:
   ```jsx
   onClick={() => handleRowClick(product.id)}
   sx={{ cursor: 'pointer' }}
   ```

5. On the two action `IconButton` elements (the `PsychologyIcon` at line 442 and the
   `EditIcon` at line 449), add `onClick` wrappers that call `e.stopPropagation()`
   before the existing handler:
   ```jsx
   onClick={(e) => { e.stopPropagation(); handleGenerateKeywords(product); }}
   onClick={(e) => { e.stopPropagation(); handleEdit(product); }}
   ```
   Also wrap the `Checkbox` `onChange` at line 350 to stop propagation:
   ```jsx
   onChange={(e) => { e.stopPropagation(); handleSelectProduct(product.id); }}
   onClick={(e) => e.stopPropagation()}
   ```

6. Render the drawer and snackbar after the closing `</Dialog>` at the end of the
   component return:
   ```jsx
   <ProductEditDrawer
     productId={drawerProductId}
     onClose={handleDrawerClose}
     onSaveSuccess={handleDrawerSaveSuccess}
   />
   <Snackbar
     open={successSnackbarOpen}
     autoHideDuration={4000}
     onClose={() => setSuccessSnackbarOpen(false)}
     message="Product updated"
     anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
   />
   ```

---

### 2.3 Modify: api.js

**File to modify**: `frontend/src/services/api.js`

Add one new method to the existing `productApi` object (after `saveKeywords` at
line 140):

```js
// Generate product summary via LLM
generateSummary: (id) =>
  api.post(`/api/products/${id}/generate-summary`),
```

No other changes to `api.js` are needed. `getProduct`, `updateProduct`, and
`saveKeywords` already exist and are used as-is.

---

## 3. API Contracts

### 3.1 Existing endpoints used without modification

| Method | Path | Used for |
|--------|------|----------|
| `GET` | `/api/products/{id}` | Fetch product on drawer open. Returns `ProductDTO` (200) or 404. |
| `POST` | `/api/products/{id}/generate-keywords` | Generate keywords. Returns `{ keywords: string[], message: string }` (200). |
| `POST` | `/api/products/{id}/keywords` | Save keywords. Request: `{ keywords: string[] }`. Returns `ProductDTO` (200). |
| `PUT` | `/api/products/{id}` | Save description. Request body: `{ description: string }` only. Returns `ProductDTO` (200). |

### 3.2 New endpoint

```
POST /api/products/{id}/generate-summary

Path param:
  id  — Long, product ID

Request body: none (empty body)

Response 200:
{
  "summary": "string (2-4 sentences, max 1000 chars)",
  "message": "Summary generated successfully"
}

Response 404:
  Product not found (empty body — matches existing pattern in ProductController)

Response 500:
{
  "error": "Failed to generate summary: <cause message>"
}
```

### 3.3 Save sequence (frontend-enforced, not atomic)

```
Step 1: POST /api/products/{id}/keywords  { "keywords": [...] }
  on error -> OPEN_ERROR, stop sequence

Step 2: PUT /api/products/{id}  { "description": "..." }
  on error -> OPEN_ERROR

on both 200 -> CLOSED + success snackbar on listing page
```

The frontend must not send the full `ProductDTO` in the `PUT` body. Sending only
`description` is safe because `ProductController.updateProduct()` applies null-safe
patching (confirmed from source lines 99-126).

---

## 4. Flyway Migration

None required. All needed columns exist:
- `products.description` (TEXT) — created in `V1__Create_tables.sql`
- `products.keywords` (text[]) — added in `V10__Add_keywords_to_products.sql`
- `products.product_name`, `products.category`, `products.islamic_structure` — all in V1

The next Flyway migration for any future story must be `V11__*.sql`.

---

## 5. Assumptions

1. **Drawer width 480px is sufficient.** The existing `AllProducts` table is wide
   enough that a right-side drawer of 480px does not obscure the product name column
   for typical laptop screen widths (1280px+). If screen real estate is insufficient
   in QA, increase to 560px — this is a CSS-only change in `ProductEditDrawer.jsx`.

2. **`productId` prop drives open/closed state.** `null` means closed; any non-null
   Long means open and triggers the product fetch. This avoids threading a separate
   `open` boolean prop alongside the ID.

3. **The existing `handleGenerateKeywords` flow in `AllProducts.jsx`** (the standalone
   keyword dialog) is kept unchanged. The new drawer is an additional, richer flow
   triggered by row click. The `PsychologyIcon` button in the Actions column still
   opens the old keyword-only dialog to avoid breaking existing workflow muscle memory.
   If the PO decides to retire it, that is a separate story.

4. **Focus trap**: MUI `Drawer` does not provide a native focus trap. A focus trap
   library (`focus-trap-react`) is not currently in `package.json`. To satisfy the
   accessibility requirement with no new dependency, use the `disableEnforceFocus={false}`
   default on `Drawer` combined with setting `autoFocus` on the close `IconButton`.
   Full ARIA focus-trap implementation (WAI-ARIA dialog pattern) can be added in the
   I18N / accessibility sprint.

5. **`CONFIRM_DISCARD` dialog is rendered inside `AllProducts.jsx`'s component tree**,
   not inside the `Drawer`, because MUI renders `Drawer` in a Portal and stacking a
   second Portal Dialog inside it can cause z-index issues. The component uses a single
   `Dialog` for discard confirmation, always rendered at the page level.

6. **No `@Transactional` annotation** is added to `ProductService.generateSummary()`.
   The method is read-only (only a `findById` call) and does not require a transaction.

7. **`timeoutSeconds` reuse**: `LLMService.generateSummaryAzure` and
   `generateSummaryOllama` reuse the existing `timeoutSeconds` field (sourced from
   `app.keywords.generation.timeout-seconds`, default 30). This is acceptable because
   summary generation has similar latency characteristics to keyword generation.

8. **Ollama `format` field omitted** in `generateSummaryOllama`. The keyword methods
   set `"format": "json"` because the expected output is a JSON array. Summary
   generation expects plain text, so the `format` field must be omitted; including it
   would cause Ollama to attempt JSON formatting of a free-text response.

---

## 6. Out of Scope

The following items from the requirements document are explicitly not built in this
spec. Do not implement them in this sprint.

- Arabic (`_ar`) summary and keyword variants — deferred to I18N-1 through I18N-3.
- Sharia review flag toggle (`sharia_review_flag`) — deferred to SHARIA-2 / DATA-4.
- Product lifecycle status change from inside the modal — deferred to FEAT-7.
- Staging product modal (separate UI on the Staging Review page) — separate story.
- Diff view comparing current vs scraped version — deferred to ADMIN-1.
- `matchExplanation` field in the modal — recommendation field, not product catalog.
- Promotion / featured flag editing — deferred to FEAT-4.
- Bulk summary or keyword generation — out of scope per requirements doc section 4.
- Backend Sharia validation on the generate-summary response — deferred to SHARIA-6.
- Single-transaction `PATCH /api/products/{id}/content` endpoint — deferred to OQ-2 resolution.
- Full WAI-ARIA focus trap — deferred to accessibility sprint.

---

## 7. Verification Checklist

Before marking the story done, the developer must confirm all of the following.

**Backend**
- [ ] `POST /api/products/{id}/generate-summary` returns 200 with `{ summary, message }` for a known product ID.
- [ ] `POST /api/products/{id}/generate-summary` returns 404 for an unknown product ID.
- [ ] `POST /api/products/{id}/generate-summary` returns 500 (with `error` field) when LLM is unavailable.
- [ ] The generated summary contains no occurrences of: `interest`, `loan`, `mortgage`, `insurance`, `conventional` (verify against LLM output via the Swagger UI at `/docs`).
- [ ] `PUT /api/products/{id}` with body `{ "description": "new text" }` does not null-out `productName`, `category`, or any other field.
- [ ] All existing endpoints listed in `CLAUDE.md` still return the same response shape.

**Frontend**
- [ ] Clicking a product row opens the drawer; clicking a row with a checkbox selection, the keyword icon, or the edit icon does NOT open the drawer.
- [ ] Drawer header shows `productName`, `category` chip, and `islamicStructure` chip (if present).
- [ ] Summary field is pre-populated from `product.description`; keywords field is pre-populated from `product.keywords`.
- [ ] "Generate Summary (AI)" button calls the new endpoint and populates the summary field without saving.
- [ ] "Generate Keywords (AI)" button calls the existing endpoint and populates the keyword tags without saving.
- [ ] While generating, the triggering button shows a spinner and is disabled; the Save button is also disabled.
- [ ] "Save" calls keywords endpoint first, then description PUT, then closes the drawer and shows the snackbar.
- [ ] A keyword absent from the AI result can be manually typed and confirmed with Enter.
- [ ] A generated keyword chip can be removed by clicking its X.
- [ ] Closing the drawer with unsaved changes shows the discard confirmation dialog.
- [ ] Closing the drawer with no unsaved changes closes immediately with no dialog.
- [ ] Pressing Escape triggers the same close logic as the Cancel button.
- [ ] API error inside the drawer shows an inline `Alert` with a Retry button (for generation errors).
- [ ] If AI-generated summary contains a forbidden Sharia term, the warning banner appears. Save is not blocked.
- [ ] The listing refreshes after a successful save.
- [ ] `Snackbar` "Product updated" appears at the bottom of the listing page after save.
