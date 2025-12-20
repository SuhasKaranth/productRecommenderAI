# Complete Setup & Usage Guide

## Step-by-Step Setup from Scratch

### Prerequisites

**Required Software:**
- ✅ Java 17+ (You have: OpenJDK 17.0.12)
- ✅ Maven 3.6+ (You have: Apache Maven 3.9.9)
- ❌ PostgreSQL 14+ (Needs installation)
- Node.js 16+ (Will be auto-installed by Maven)

---

## Part 1: Install & Configure PostgreSQL

### 1. Install PostgreSQL

```bash
# Install PostgreSQL via Homebrew
brew install postgresql@14

# Start PostgreSQL service
brew services start postgresql@14

# Add PostgreSQL to PATH (add to ~/.zshrc or ~/.bash_profile)
echo 'export PATH="/opt/homebrew/opt/postgresql@14/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc

# Verify installation
psql --version
```

### 2. Create Database

```bash
# Connect to PostgreSQL
psql postgres

# In psql prompt, run:
CREATE DATABASE smart_guide_poc;

# Create user (optional - can use default postgres user)
CREATE USER smartguide WITH PASSWORD 'smartguide123';
GRANT ALL PRIVILEGES ON DATABASE smart_guide_poc TO smartguide;

# Exit psql
\q
```

### 3. Verify Database Connection

```bash
# Test connection
psql -d smart_guide_poc -U postgres

# You should see: smart_guide_poc=#
# Exit with: \q
```

---

## Part 2: Configure Environment Variables

### 1. Update .env File

```bash
# Navigate to project root
cd /Users/suhaskaranth/dev/productRecommenderAI/productRecommenderAI-springboot

# Edit .env file (already exists)
# Update these values:
```

**.env file content:**
```bash
# Database Configuration
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/smart_guide_poc
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=postgres

# LLM Provider (ollama or azure)
LLM_PROVIDER=ollama

# Ollama Configuration (if using Ollama)
OLLAMA_HOST=http://localhost:11434
OLLAMA_MODEL=llama3.2

# Server Configuration
SERVER_PORT=8080

# Scraper Service Configuration
SCRAPER_SERVER_PORT=8081
MAIN_SERVICE_URL=http://localhost:8080
```

### 2. Install Ollama (For AI Categorization)

```bash
# Install Ollama
brew install ollama

# Start Ollama service
ollama serve &

# Pull the model (this may take a few minutes)
ollama pull llama3.2

# Verify
curl http://localhost:11434/api/tags
```

---

## Part 3: Build the Project

### 1. Build Main Application (includes React UI)

```bash
cd /Users/suhaskaranth/dev/productRecommenderAI/productRecommenderAI-springboot

# Clean and build (this will take 3-5 minutes first time)
mvn clean install

# This will:
# - Install Node.js and npm
# - Install React dependencies
# - Build React app
# - Run database migrations
# - Build Spring Boot app
```

**Expected output:**
```
[INFO] Installing node version v18.18.0
[INFO] Installed node locally.
[INFO] npm install
[INFO] npm run build
[INFO] BUILD SUCCESS
```

### 2. Build Scraper Service

```bash
cd product-scraper-service

# Build scraper service
mvn clean install

# Install Playwright browsers (one-time setup)
mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
```

---

## Part 4: Run the Services

### Terminal 1: Main Application

```bash
cd /Users/suhaskaranth/dev/productRecommenderAI/productRecommenderAI-springboot

# Run main app
mvn spring-boot:run
```

**Expected output:**
```
Started SmartGuidePocApplication in 8.5 seconds
Tomcat started on port(s): 8080 (http)
```

**Verify it's running:**
- Open browser: http://localhost:8080/docs
- You should see Swagger UI

### Terminal 2: Scraper Service

```bash
cd /Users/suhaskaranth/dev/productRecommenderAI/productRecommenderAI-springboot/product-scraper-service

# Run scraper service
mvn spring-boot:run
```

**Expected output:**
```
Started ProductScraperApplication in 6.2 seconds
Tomcat started on port(s): 8081 (http)
Loaded 2 scraper configurations
```

**Verify it's running:**
- Open browser: http://localhost:8081/docs
- You should see Swagger UI for scraper

---

## Part 5: Verify Database Setup

```bash
# Connect to database
psql -d smart_guide_poc -U postgres

# Check tables were created
\dt

# You should see these tables:
# - products
# - staging_products
# - scrape_sources
# - scrape_logs
# - intent_category_mapping
# - flyway_schema_history

# Check scrape sources
SELECT website_id, website_name FROM scrape_sources;

# Expected output:
# example_bank | Example Islamic Bank
# maybank_islamic | Maybank Islamic

# Exit
\q
```

---

## Part 6: Access the Admin UI

### 1. Open the Admin UI

**URL:** http://localhost:8080/ui

You should see:
- Dashboard with 3 statistic cards (all showing 0 initially)
- Tabs: Dashboard, Start Scrape, Review Products

### 2. Verify API Endpoints

**Main App API:**
- http://localhost:8080/docs

**Scraper API:**
- http://localhost:8081/docs

---

## Part 7: Run Your First Scrape

### Method 1: Via Web UI (Recommended)

1. **Open UI:** http://localhost:8080/ui

2. **Go to "Start Scrape" Tab**

3. **Select Website:**
   - Click dropdown "Select Website"
   - Choose "Example Islamic Bank" or "Maybank Islamic"

4. **Click "Start Scraping"**
   - You'll see: "Scraping in progress..."
   - Job ID will be displayed
   - Wait for completion (30 seconds - 2 minutes)

5. **Go to "Review Products" Tab**
   - You should see scraped products in the table
   - Each product shows:
     - Product name
     - Category
     - AI suggested category (with confidence %)
     - Quality score
     - Source website

### Method 2: Via API (Alternative)

```bash
# Trigger scraping via API
curl -X POST http://localhost:8081/api/scraper/trigger/example_bank

# Response:
{
  "jobId": "abc-123-xyz",
  "websiteId": "example_bank",
  "status": "STARTED",
  "message": "Scraping job started successfully"
}

# Check job status
curl http://localhost:8081/api/scraper/status/abc-123-xyz

# Check staging products
curl http://localhost:8080/api/admin/staging?pendingOnly=true
```

---

## Part 8: Review & Approve Products

### Via Web UI

1. **Go to "Review Products" Tab**

2. **Review Product Details:**
   - Check product name, category, description
   - Review AI suggested category
   - Check quality score (higher is better)

3. **Edit Product (if needed):**
   - Click edit icon (pencil) on any product
   - Modal opens with editable fields
   - Modify product name, category, description, etc.
   - Click "Save Changes"

4. **Approve Individual Product:**
   - Click green checkmark icon
   - Product is approved and moved to production
   - Status changes from PENDING → APPROVED

5. **Bulk Approve:**
   - Check boxes next to multiple products
   - Click "Approve Selected (X)" button at top
   - All selected products approved at once

6. **Reject Product:**
   - Click orange X icon
   - Product marked as rejected
   - Won't appear in production

7. **Delete Product:**
   - Click red delete icon
   - Confirm deletion
   - Product removed from staging

### Via API (Alternative)

```bash
# Get all pending products
curl http://localhost:8080/api/admin/staging?pendingOnly=true

# Approve single product (replace {id} with actual ID)
curl -X POST http://localhost:8080/api/admin/staging/1/approve \
  -H "Content-Type: application/json" \
  -d '{"reviewedBy": "admin", "reviewNotes": "Looks good"}'

# Bulk approve multiple products
curl -X POST http://localhost:8080/api/admin/staging/bulk-approve \
  -H "Content-Type: application/json" \
  -d '{
    "productIds": [1, 2, 3],
    "reviewedBy": "admin",
    "reviewNotes": "Batch approval"
  }'

# Update product before approval
curl -X PUT http://localhost:8080/api/admin/staging/1 \
  -H "Content-Type: application/json" \
  -d '{
    "productName": "Updated Product Name",
    "category": "CREDIT_CARD",
    "description": "Updated description"
  }'
```

---

## Part 9: Verify Products in Production

```bash
# Check products table (production)
psql -d smart_guide_poc -U postgres -c "SELECT product_code, product_name, category, source_website_id FROM products;"

# Or via API
curl http://localhost:8080/api/recommendations \
  -H "Content-Type: application/json" \
  -d '{
    "userQuery": "I need a credit card",
    "userContext": {
      "income": 50000,
      "creditScore": 700
    }
  }'
```

---

## Troubleshooting

### Issue: PostgreSQL not starting

```bash
# Check if it's running
brew services list | grep postgresql

# Restart it
brew services restart postgresql@14

# Check logs
tail -f /opt/homebrew/var/log/postgresql@14.log
```

### Issue: Port already in use

```bash
# Find what's using port 8080
lsof -i :8080

# Kill the process
kill -9 <PID>
```

### Issue: Database connection failed

```bash
# Test connection
psql -d smart_guide_poc -U postgres

# If fails, check .env file has correct credentials
# Check PostgreSQL is running:
brew services list | grep postgresql
```

### Issue: Ollama not responding

```bash
# Start Ollama
ollama serve

# In another terminal, verify
curl http://localhost:11434/api/tags
```

### Issue: React UI not loading

```bash
# Rebuild frontend
cd /Users/suhaskaranth/dev/productRecommenderAI/productRecommenderAI-springboot
mvn clean install

# Check static files exist
ls -la target/classes/static/

# Should see: index.html, static/ folder
```

### Issue: Playwright browser not installed

```bash
cd product-scraper-service
mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
```

---

## Quick Reference Commands

**Start Everything:**
```bash
# Terminal 1: Main App
cd /Users/suhaskaranth/dev/productRecommenderAI/productRecommenderAI-springboot
mvn spring-boot:run

# Terminal 2: Scraper Service
cd product-scraper-service
mvn spring-boot:run

# Terminal 3: Ollama (if not running)
ollama serve
```

**Access Points:**
- Admin UI: http://localhost:8080/ui
- Main API Docs: http://localhost:8080/docs
- Scraper API Docs: http://localhost:8081/docs

**Useful Database Commands:**
```bash
# Connect to database
psql -d smart_guide_poc -U postgres

# View staging products
SELECT id, product_name, approval_status, ai_suggested_category FROM staging_products;

# View production products
SELECT product_code, product_name, category FROM products;

# View scrape logs
SELECT job_id, status, products_found, products_saved FROM scrape_logs ORDER BY started_at DESC;
```

---

## Workflow Summary

1. ✅ **Setup**: Install PostgreSQL, configure .env
2. ✅ **Build**: `mvn clean install` (both projects)
3. ✅ **Run**: Start main app & scraper service
4. ✅ **Scrape**: Use UI to trigger scraping
5. ✅ **Review**: Check staging products with AI suggestions
6. ✅ **Edit**: Modify products if needed
7. ✅ **Approve**: Individual or bulk approve
8. ✅ **Production**: Approved products available for recommendations

---

## Next Steps

After approval, products are available for the recommendation engine:

```bash
# Test recommendation API
curl -X POST http://localhost:8080/api/recommendations \
  -H "Content-Type: application/json" \
  -d '{
    "userQuery": "I want to buy a house",
    "userContext": {
      "income": 80000,
      "creditScore": 750
    }
  }'
```

The approved products will now appear in recommendation results!
