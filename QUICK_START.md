# Quick Start Guide - Run Scraping & Approval

## Prerequisites Checklist
- ✅ PostgreSQL running (Docker container: priceless_pascal)
- ✅ Database `smart_guide_poc` created
- ⏳ Build completed (`mvn clean install`)

---

## Step 1: Start Main Application

Open **Terminal 1**:
```bash
cd /Users/suhaskaranth/dev/productRecommenderAI/productRecommenderAI-springboot

# Run main app
mvn spring-boot:run
```

**Wait for this message:**
```
Started SmartGuidePocApplication in X seconds
```

**Verify:** Open http://localhost:8080/docs - Should see Swagger UI

---

## Step 2: Start Scraper Service

Open **Terminal 2**:
```bash
cd /Users/suhaskaranth/dev/productRecommenderAI/productRecommenderAI-springboot/product-scraper-service

# Build scraper (first time only)
mvn clean install -DskipTests

# Install Playwright browsers (first time only)
mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"

# Run scraper service
mvn spring-boot:run
```

**Wait for this message:**
```
Started ProductScraperApplication in X seconds
Loaded 2 scraper configurations
```

**Verify:** Open http://localhost:8081/docs - Should see Swagger UI

---

## Step 3: Open Admin UI

**URL:** http://localhost:8080/ui

You should see:
- Dashboard with statistics (all 0 initially)
- Three tabs: Dashboard | Start Scrape | Review Products

---

## Step 4: Run Your First Scrape

### Via Web UI (Recommended):

1. **Click "Start Scrape" tab**

2. **Select a website:**
   - Click the dropdown menu
   - Choose either:
     - "Example Islamic Bank"
     - "Maybank Islamic"

3. **Click "Start Scraping" button**
   - You'll see "Scraping in progress..."
   - A Job ID will appear (e.g., "abc-123-xyz")
   - Wait 30 seconds - 2 minutes

4. **Check the console/logs:**
   ```
   Scraping job abc-123-xyz started
   Scraped 10 products
   Saving products to staging table
   Job completed successfully
   ```

### Via API (Alternative):

```bash
# Trigger scraping
curl -X POST http://localhost:8081/api/scraper/trigger/example_bank

# Response:
{
  "jobId": "abc-123-xyz",
  "status": "STARTED"
}

# Check job status
curl http://localhost:8081/api/scraper/status/abc-123-xyz
```

---

## Step 5: Review Scraped Products

1. **Click "Review Products" tab** in the UI

2. **You should see a table with products:**
   - Product Name
   - Category
   - AI Suggested Category (with confidence %)
   - Quality Score (0-100%)
   - Source Website
   - Action buttons

**Example row:**
```
Product Name: Islamic Credit Card - Travel Rewards
Category: CREDIT_CARD
AI Suggestion: CREDIT_CARD (95%)
Quality Score: 85%
Source: example_bank
Actions: [✓ Approve] [✏️ Edit] [✗ Reject] [🗑️ Delete]
```

---

## Step 6: Approve Products

### Option A: Approve Individual Product

1. **Review the product details**
   - Check name, category, description
   - Review AI suggestion
   - Check quality score

2. **Click green checkmark (✓) button**
   - Product status changes to APPROVED
   - Moved to production `products` table
   - Removed from pending list

### Option B: Edit Before Approval

1. **Click edit icon (✏️)**
2. **Modal opens with form fields:**
   - Product Name
   - Category
   - Description
   - Islamic Structure
   - Annual Rate
   - Etc.

3. **Make changes**
4. **Click "Save Changes"**
5. **Then click approve (✓)**

### Option C: Bulk Approve

1. **Check boxes next to multiple products**
2. **Click "Approve Selected (X)" button** at top
3. **All selected products approved at once**

### Option D: Reject Product

1. **Click orange X button**
2. **Product marked as REJECTED**
3. **Won't appear in production**

---

## Step 7: Verify Approved Products

### Check Database:

```bash
# Connect to PostgreSQL
docker exec -it priceless_pascal psql -U postgres -d smart_guide_poc

# View staging products
SELECT id, product_name, approval_status, ai_suggested_category
FROM staging_products
ORDER BY created_at DESC;

# View production products
SELECT product_code, product_name, category, source_website_id
FROM products
ORDER BY created_at DESC;

# Exit
\q
```

### Via API:

```bash
# Get all products
curl http://localhost:8080/api/admin/staging

# Get statistics
curl http://localhost:8080/api/admin/staging/stats

# Response:
{
  "pending": 0,
  "approved": 10,
  "rejected": 0
}
```

---

## Step 8: Test Recommendations

Now that products are approved, test the recommendation engine:

```bash
curl -X POST http://localhost:8080/api/recommendations \
  -H "Content-Type: application/json" \
  -d '{
    "userQuery": "I want a credit card for travel",
    "userContext": {
      "income": 80000,
      "creditScore": 750
    }
  }'
```

**Expected Response:**
```json
{
  "recommendations": [
    {
      "product": {
        "productName": "Islamic Travel Credit Card",
        "category": "CREDIT_CARD",
        "annualRate": 15.5,
        ...
      },
      "score": 0.95,
      "reason": "Perfect for travelers with Murabaha structure"
    }
  ],
  "userIntent": "TRAVEL",
  "confidence": 0.92
}
```

---

## Common Tasks

### Refresh Product List:
- Click "Refresh" button in Review tab

### View Scrape History:
```bash
docker exec -it priceless_pascal psql -U postgres -d smart_guide_poc -c "
SELECT job_id, status, products_found, products_saved, started_at
FROM scrape_logs
ORDER BY started_at DESC
LIMIT 10;"
```

### Clear All Staging Products:
```bash
docker exec -it priceless_pascal psql -U postgres -d smart_guide_poc -c "
DELETE FROM staging_products WHERE approval_status = 'PENDING';"
```

### Rescrape a Website:
1. Go to "Start Scrape" tab
2. Select same website
3. Click "Start Scraping" again
4. New products added to staging

---

## Troubleshooting

### No products appear after scraping:
```bash
# Check scrape logs
curl http://localhost:8081/api/scraper/status/YOUR_JOB_ID

# Check database
docker exec -it priceless_pascal psql -U postgres -d smart_guide_poc -c "
SELECT COUNT(*) FROM staging_products WHERE approval_status = 'PENDING';"
```

### Scraping fails:
- Check Playwright browsers installed:
  ```bash
  cd product-scraper-service
  mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
  ```
- Check scraper service logs in Terminal 2

### UI not loading:
- Verify main app is running (Terminal 1)
- Check http://localhost:8080/ui
- Clear browser cache and refresh

### Products not approving:
- Check main app logs in Terminal 1
- Verify database connection:
  ```bash
  docker exec -it priceless_pascal psql -U postgres -d smart_guide_poc -c "SELECT 1;"
  ```

---

## Quick Reference

**Services:**
- Main App: http://localhost:8080
- Scraper: http://localhost:8081
- Admin UI: http://localhost:8080/ui

**API Docs:**
- Main: http://localhost:8080/docs
- Scraper: http://localhost:8081/docs

**Database:**
```bash
# Connect
docker exec -it priceless_pascal psql -U postgres -d smart_guide_poc

# Key tables
\dt

# Exit
\q
```

**Stop Services:**
- Press `Ctrl+C` in Terminal 1 and Terminal 2

---

## Success Checklist

- [ ] Main app running on port 8080
- [ ] Scraper service running on port 8081
- [ ] Admin UI accessible at /ui
- [ ] Scraped at least one website
- [ ] Reviewed products in staging
- [ ] Approved products to production
- [ ] Tested recommendation API
- [ ] Products appear in recommendations

**You're all set! 🎉**
