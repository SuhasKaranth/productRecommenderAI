# API Key Authentication - Testing Guide

## Setup Instructions

### 1. Generate Secure API Keys

Generate secure random API keys using one of these methods:

```bash
# Method 1: Using openssl
openssl rand -base64 32 | tr -d "=+/" | cut -c1-32

# Method 2: Using openssl hex
openssl rand -hex 16

# Method 3: Online generator
# Visit: https://www.uuidgenerator.net/
```

### 2. Create Your .env File

Copy the example file and add your generated keys:

```bash
cp .env.example .env
```

Edit `.env` and replace the placeholder keys with your generated keys:

```properties
# Example .env configuration
API_KEY_AUTH_ENABLED=true

# ADMIN keys (full access)
API_KEY_ADMIN_1=sk_admin_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6
API_KEY_ADMIN_2=sk_admin_z9y8x7w6v5u4t3s2r1q0p9o8n7m6l5k4

# USER keys (limited access)
API_KEY_USER_1=sk_user_q1w2e3r4t5y6u7i8o9p0a1s2d3f4g5h6
API_KEY_USER_2=sk_user_j7k8l9m0n1b2v3c4x5z6a7s8d9f0g1h2
```

### 3. Start the Application

```bash
# Build the project
mvn clean install

# Run the application
mvn spring-boot:run
```

Check the logs for API key initialization:

```
INFO  - Initializing API key authentication...
INFO  - Loaded ADMIN API key: sk_admin_a***
INFO  - Loaded ADMIN API key: sk_admin_z***
INFO  - Loaded USER API key: sk_user_q1***
INFO  - Loaded USER API key: sk_user_j7***
INFO  - API key authentication initialized with 4 keys
```

---

## Test Scenarios

### Test 1: Public Endpoint (No API Key Required)

**Endpoint:** `GET /health`

**Expected:** Should work without API key

```bash
curl http://localhost:8080/health
```

**Expected Response:**
```json
{
  "status": "healthy",
  "timestamp": 1703001234567,
  "service": "smart-guide-poc"
}
```

---

### Test 2: Protected Endpoint Without API Key

**Endpoint:** `POST /api/v1/recommend`

**Expected:** 401 Unauthorized

```bash
curl -X POST http://localhost:8080/api/v1/recommend \
  -H "Content-Type: application/json" \
  -d '{"userInput": "I want to travel", "language": "en"}'
```

**Expected Response:**
```json
{
  "status": "error",
  "errorCode": "MISSING_API_KEY",
  "message": "API key is required. Please provide X-API-Key header.",
  "details": null
}
```

---

### Test 3: Invalid API Key

**Endpoint:** `POST /api/v1/recommend`

**Expected:** 401 Unauthorized

```bash
curl -X POST http://localhost:8080/api/v1/recommend \
  -H "Content-Type: application/json" \
  -H "X-API-Key: invalid_key_12345" \
  -d '{"userInput": "I want to travel", "language": "en"}'
```

**Expected Response:**
```json
{
  "status": "error",
  "errorCode": "INVALID_API_KEY",
  "message": "Invalid API key provided.",
  "details": null
}
```

---

### Test 4: Valid USER Key - Recommendation API

**Endpoint:** `POST /api/v1/recommend`

**Expected:** Success (200 OK)

```bash
curl -X POST http://localhost:8080/api/v1/recommend \
  -H "Content-Type: application/json" \
  -H "X-API-Key: sk_user_q1w2e3r4t5y6u7i8o9p0a1s2d3f4g5h6" \
  -d '{
    "userInput": "I want to save money for my child education",
    "language": "en"
  }'
```

**Expected Response:**
```json
{
  "status": "success",
  "intent": {
    "detectedIntent": "EDUCATION",
    "confidence": 0.92
  },
  "recommendations": [
    {
      "rank": 1,
      "productId": 5,
      "productName": "Education Savings Account",
      "category": "SAVINGS",
      "relevanceScore": 0.95
    }
  ],
  "processingTimeMs": 1234
}
```

---

### Test 5: USER Key Accessing Admin Endpoint

**Endpoint:** `GET /api/admin/staging/pending`

**Expected:** 403 Forbidden

```bash
curl -X GET http://localhost:8080/api/admin/staging/pending \
  -H "X-API-Key: sk_user_q1w2e3r4t5y6u7i8o9p0a1s2d3f4g5h6"
```

**Expected Response:**
```json
{
  "status": "error",
  "errorCode": "ACCESS_DENIED",
  "message": "You don't have permission to access this resource. This endpoint requires ADMIN level access.",
  "details": {
    "error": "Access Denied"
  }
}
```

---

### Test 6: Valid ADMIN Key - Admin Endpoint

**Endpoint:** `GET /api/admin/staging/pending`

**Expected:** Success (200 OK)

```bash
curl -X GET http://localhost:8080/api/admin/staging/pending \
  -H "X-API-Key: sk_admin_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6"
```

**Expected Response:**
```json
{
  "status": "success",
  "data": [
    {
      "id": 1,
      "productName": "Scraped Product Name",
      "approvalStatus": "PENDING",
      "dataQualityScore": 85.5
    }
  ]
}
```

---

### Test 7: ADMIN Key - Approve Staging Product

**Endpoint:** `POST /api/admin/staging/{id}/approve`

**Expected:** Success (200 OK)

```bash
curl -X POST http://localhost:8080/api/admin/staging/1/approve \
  -H "X-API-Key: sk_admin_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6"
```

**Expected Response:**
```json
{
  "status": "success",
  "message": "Product approved and promoted to production",
  "productId": 123
}
```

---

### Test 8: Access Swagger Documentation

**Endpoint:** `GET /docs`

**Expected:** Success with USER or ADMIN key

```bash
# Open in browser with API key in URL (not recommended for production)
# Or use browser extension to add custom header

# Command line test
curl -X GET http://localhost:8080/docs \
  -H "X-API-Key: sk_user_q1w2e3r4t5y6u7i8o9p0a1s2d3f4g5h6" \
  -L
```

---

### Test 9: Disable Authentication (Development Mode)

Set in `.env`:
```properties
API_KEY_AUTH_ENABLED=false
```

Restart application and test:

```bash
# Should work without API key
curl -X POST http://localhost:8080/api/v1/recommend \
  -H "Content-Type: application/json" \
  -d '{"userInput": "I want to travel", "language": "en"}'
```

---

## API Key Scopes Reference

### ADMIN Scope
Full access to all endpoints:
- `admin:*` - All admin operations
- `products:read` - Read products
- `products:write` - Create/modify products
- `products:delete` - Delete products
- `recommend:execute` - Get recommendations
- `staging:read` - View staging products
- `staging:approve` - Approve/reject staging products
- `staging:edit` - Edit staging products
- `scraper:execute` - Run scraping jobs
- `scraper:read` - View scraper logs

### USER Scope
Limited access:
- `user:*` - Standard user access
- `products:read` - Read products
- `recommend:execute` - Get recommendations
- `staging:read` - View staging products (read-only)
- `scraper:read` - View scraper logs (read-only)

---

## Endpoint Protection Matrix

| Endpoint | Method | PUBLIC | USER | ADMIN |
|----------|--------|--------|------|-------|
| `/health` | GET | ✅ | ✅ | ✅ |
| `/` | GET | ✅ | ✅ | ✅ |
| `/docs` | GET | ❌ | ✅ | ✅ |
| `/api/v1/recommend` | POST | ❌ | ✅ | ✅ |
| `/api/products/**` | GET | ❌ | ✅ | ✅ |
| `/api/products/**` | POST/PUT/DELETE | ❌ | ❌ | ✅ |
| `/api/admin/**` | ALL | ❌ | ❌ | ✅ |
| `/ui/**` | ALL | ❌ | ❌ | ✅ |
| `/api/scraper/**` | ALL | ❌ | ❌ | ✅ |

---

## Troubleshooting

### Issue: "API key authentication is enabled but NO VALID KEYS are configured!"

**Solution:** Check your `.env` file:
1. Ensure API keys follow format: `sk_admin_<random>` or `sk_user_<random>`
2. Keys must be at least 20 characters long
3. Keys must have at least 2 underscores

### Issue: API key not being recognized

**Solution:**
1. Check the header name is correct: `X-API-Key` (case-sensitive)
2. Verify key value matches exactly what's in `.env`
3. Restart application after changing `.env`
4. Check logs for "API key validation failed"

### Issue: 403 Forbidden even with valid key

**Solution:**
1. Check if your key has the required scope
2. USER keys cannot access ADMIN endpoints
3. Verify endpoint protection rules in `SecurityConfig.java`

### Issue: Can't access Swagger UI

**Solution:**
1. Swagger requires authentication now
2. Use browser extension to add `X-API-Key` header
3. Or temporarily disable auth: `API_KEY_AUTH_ENABLED=false`

---

## Security Best Practices

1. **Never commit API keys to git**
   - `.env` is in `.gitignore`
   - Always use `.env.example` with placeholder values

2. **Use strong random keys**
   - Minimum 32 characters
   - Generated from cryptographically secure random source

3. **Different keys per environment**
   - Development keys
   - Staging keys
   - Production keys

4. **Rotate keys regularly**
   - Quarterly rotation recommended
   - Keep old keys active during transition period

5. **Monitor key usage**
   - Check logs for failed authentication attempts
   - Track which keys are being used

6. **Scope principle of least privilege**
   - Use USER keys for applications that only need read access
   - Use ADMIN keys only where necessary

---

## Next Steps

After API key authentication is working:

1. **Add key management UI** (future)
   - Admin panel to create/revoke keys
   - View key usage statistics

2. **Database-backed keys** (future)
   - Store keys in database instead of `.env`
   - Enable runtime key management

3. **Key expiration** (future)
   - Set expiration dates on keys
   - Automatic key rotation

4. **Rate limiting per key** (future)
   - Different rate limits for different keys
   - Prevent API abuse

5. **Audit logging** (future)
   - Log all API key usage
   - Track which key accessed which endpoint
