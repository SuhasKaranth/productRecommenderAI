# Docker Testing Guide

Quick reference for testing your Docker deployment.

## Quick Test (Automated)

```bash
# Run automated test suite
./test-docker.sh
```

This script will:
1. ✅ Check prerequisites (Docker, Docker Compose)
2. ✅ Verify .env.docker configuration
3. ✅ Build Docker images
4. ✅ Start all services
5. ✅ Wait for health checks
6. ✅ Test health endpoint
7. ✅ Test authentication (with and without API key)
8. ✅ Test database connectivity
9. ✅ Test admin API
10. ✅ Check resource usage
11. ✅ Generate test report

**Expected output:** "✓ All tests passed!"

---

## Manual Testing Steps

### 1. Prerequisites

```bash
# Verify Docker is installed
docker --version                 # Should show 20.10+
docker-compose --version         # Should show 2.0+

# Check Docker is running
docker ps
```

### 2. Setup Environment

```bash
# Create .env.docker from template
cp .env.docker.example .env.docker

# Generate secure API keys
openssl rand -base64 32 | tr -d "=+/" | cut -c1-40
# Run twice to get two keys

# Edit .env.docker with generated keys
nano .env.docker
```

**Required changes in .env.docker:**
- `SPRING_DATASOURCE_PASSWORD` - Change from default
- `API_KEY_ADMIN_1` - Use generated key with `sk_admin_` prefix
- `API_KEY_USER_1` - Use generated key with `sk_user_` prefix

### 3. Build

```bash
# Build Docker images (5-10 minutes first time)
docker-compose build

# Verify image was created
docker images | grep smartguide
# Expected: smartguide/app latest ... 287MB
```

### 4. Start Services

```bash
# Start all services in background
docker-compose up -d

# Check status
docker-compose ps
# Expected: Both containers "Up (healthy)"
```

### 5. Verify Health

```bash
# Wait for services to be healthy (30-60 seconds)
sleep 60

# Test health endpoint
curl http://localhost:8080/health
# Expected: {"status":"UP"}
```

### 6. Test Authentication

```bash
# Get API key from .env.docker
API_KEY=$(grep "API_KEY_USER_1=" .env.docker | cut -d'=' -f2)

# Test without auth (should fail)
curl -v http://localhost:8080/api/v1/recommend
# Expected: HTTP 401 Unauthorized

# Test with auth (should succeed)
curl -X POST http://localhost:8080/api/v1/recommend \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" \
  -d '{"userInput":"travel","language":"en"}'
# Expected: HTTP 200 with recommendations
```

### 7. Test Web UI

Open in browser:
- http://localhost:8080/ui - Admin interface
- http://localhost:8080/docs - API documentation
- http://localhost:8080/health - Health check

### 8. Test Database

```bash
# Access database shell
docker-compose exec postgres psql -U postgres -d smart_guide_poc

# Inside psql:
\dt                              # List tables
SELECT COUNT(*) FROM products;   # Count products
\q                               # Quit
```

### 9. Check Logs

```bash
# View all logs
docker-compose logs -f

# View specific service
docker-compose logs -f app
docker-compose logs -f postgres

# Last 50 lines
docker-compose logs --tail=50 app
```

### 10. Monitor Resources

```bash
# Real-time stats
docker stats smartguide-app smartguide-postgres
# Press Ctrl+C to exit

# Expected usage:
# App: ~500MB-1GB RAM, <5% CPU (idle)
# Postgres: ~50-100MB RAM, <2% CPU (idle)
```

---

## Common Test Scenarios

### Scenario 1: Fresh Installation

```bash
# Full setup from scratch
cp .env.docker.example .env.docker
nano .env.docker                  # Update keys
docker-compose build
docker-compose up -d
sleep 60
curl http://localhost:8080/health
```

### Scenario 2: After Code Changes

```bash
# Rebuild and restart
docker-compose down
docker-compose build --no-cache
docker-compose up -d
```

### Scenario 3: Database Reset

```bash
# WARNING: Deletes all data!
docker-compose down -v
docker-compose up -d
```

### Scenario 4: Test with Different API Keys

```bash
# Generate new keys
openssl rand -base64 32 | tr -d "=+/" | cut -c1-40

# Update .env.docker
nano .env.docker

# Restart services
docker-compose restart app
sleep 30

# Test with new key
API_KEY=$(grep "API_KEY_USER_1=" .env.docker | cut -d'=' -f2)
curl -X POST http://localhost:8080/api/v1/recommend \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" \
  -d '{"userInput":"travel","language":"en"}'
```

---

## Troubleshooting Tests

### Test Failed: Health Check

**Problem:** `curl http://localhost:8080/health` returns connection refused

**Solutions:**
```bash
# 1. Check if containers are running
docker-compose ps

# 2. Check if port is in use by another app
lsof -i :8080

# 3. Check application logs
docker-compose logs -f app

# 4. Wait longer (services can take 60+ seconds to start)
sleep 30
curl http://localhost:8080/health

# 5. Check container health
docker inspect smartguide-app --format='{{.State.Health.Status}}'
```

### Test Failed: Authentication

**Problem:** API returns 401 even with valid key

**Solutions:**
```bash
# 1. Verify API key was extracted correctly
echo $API_KEY

# 2. Check .env.docker has correct format
cat .env.docker | grep API_KEY_USER_1

# 3. Verify auth is enabled
docker-compose exec app env | grep API_KEY_AUTH_ENABLED

# 4. Check application logs for auth errors
docker-compose logs -f app | grep -i "auth\|api.*key"

# 5. Restart app to reload .env.docker
docker-compose restart app
sleep 30
```

### Test Failed: Database Connection

**Problem:** Application can't connect to database

**Solutions:**
```bash
# 1. Check postgres is healthy
docker-compose ps postgres

# 2. Check postgres logs
docker-compose logs postgres

# 3. Verify database exists
docker-compose exec postgres psql -U postgres -l

# 4. Check network connectivity
docker-compose exec app ping postgres

# 5. Verify connection string
docker-compose exec app env | grep SPRING_DATASOURCE_URL
# Should be: jdbc:postgresql://postgres:5432/smart_guide_poc
```

### Test Failed: Build

**Problem:** `docker-compose build` fails

**Solutions:**
```bash
# 1. Clean build with no cache
docker-compose build --no-cache

# 2. Check Docker disk space
docker system df

# 3. Prune unused data
docker system prune

# 4. Check build logs
docker-compose build 2>&1 | tee build.log
cat build.log

# 5. Verify Dockerfile syntax
cat Dockerfile
```

### Test Failed: Out of Memory

**Problem:** Containers keep restarting or OOM killed

**Solutions:**
```bash
# 1. Check Docker memory allocation
docker info | grep "Total Memory"

# 2. Increase memory in Docker Desktop
# Docker Desktop > Settings > Resources > Memory: 4GB+

# 3. Check container memory usage
docker stats --no-stream

# 4. Reduce JVM memory in .env.docker
# JAVA_OPTS=-Xmx512m -Xms256m

# 5. Restart Docker Desktop
```

---

## Performance Testing

### Test 1: Response Time

```bash
API_KEY=$(grep "API_KEY_USER_1=" .env.docker | cut -d'=' -f2)

# Test response time with curl
time curl -X POST http://localhost:8080/api/v1/recommend \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" \
  -d '{"userInput":"travel","language":"en"}'

# Expected: <2 seconds for first request, <1 second for subsequent
```

### Test 2: Concurrent Requests

```bash
# Install Apache Bench (if not installed)
# macOS: brew install httpd
# Ubuntu: sudo apt-get install apache2-utils

# Run 100 requests, 10 concurrent
ab -n 100 -c 10 -p request.json -T application/json \
  -H "X-API-Key: $API_KEY" \
  http://localhost:8080/api/v1/recommend

# Create request.json:
echo '{"userInput":"travel","language":"en"}' > request.json
```

### Test 3: Database Performance

```bash
# Test query performance
docker-compose exec postgres psql -U postgres -d smart_guide_poc -c \
  "EXPLAIN ANALYZE SELECT * FROM products WHERE category = 'CREDIT_CARD' LIMIT 10;"
```

---

## Test Checklist

Use this checklist to verify all functionality:

### Basic Functionality
- [ ] Docker and Docker Compose installed
- [ ] .env.docker created and configured
- [ ] Docker images build successfully
- [ ] Containers start and become healthy
- [ ] Health endpoint returns 200 OK
- [ ] Database accessible and tables exist

### Authentication & Security
- [ ] Unauthenticated requests return 401
- [ ] Valid user key allows API access
- [ ] Valid admin key allows admin API access
- [ ] Invalid keys are rejected
- [ ] Different scopes enforced correctly

### API Functionality
- [ ] Recommendation API returns results
- [ ] Results contain intent and recommendations
- [ ] Different intents processed correctly
- [ ] Response time acceptable (<2s)
- [ ] Error handling works properly

### Web UI
- [ ] Admin UI loads at /ui
- [ ] API documentation loads at /docs
- [ ] All UI pages accessible
- [ ] Forms submit correctly
- [ ] Navigation works

### Database
- [ ] Migrations run automatically
- [ ] Seed data loaded
- [ ] Tables created correctly
- [ ] Queries perform well
- [ ] Data persists after restart

### Monitoring & Logs
- [ ] Logs accessible via docker-compose logs
- [ ] Resource usage acceptable
- [ ] No critical errors in logs
- [ ] Health checks passing
- [ ] Container stats reasonable

### Operations
- [ ] Services can be stopped cleanly
- [ ] Services can be restarted
- [ ] Database backup works
- [ ] Database restore works
- [ ] Cleanup commands work

---

## Next Steps After Testing

Once all tests pass:

1. **Customize Configuration**
   - Update LLM provider settings
   - Configure scraper settings if needed
   - Adjust resource limits in docker-compose.yml

2. **Add Your Data**
   - Use admin UI to scrape products
   - Review and approve staging products
   - Test recommendations with real data

3. **Production Preparation**
   - Change all default passwords
   - Generate new API keys
   - Set up HTTPS/TLS (nginx reverse proxy)
   - Configure secrets management
   - Set up monitoring and alerts
   - Implement backup schedule

4. **Read Documentation**
   - [DOCKER_QUICK_START.md](DOCKER_QUICK_START.md) - Detailed Docker guide
   - [API_KEY_TESTING_GUIDE.md](API_KEY_TESTING_GUIDE.md) - Authentication testing
   - [README.md](README.md) - Complete application documentation

---

## Support

If tests continue to fail:

1. Check [DOCKER_QUICK_START.md](DOCKER_QUICK_START.md) troubleshooting section
2. Review application logs: `docker-compose logs -f app`
3. Check Docker resources: `docker system df`
4. Verify prerequisites are met
5. Try clean rebuild: `docker-compose down -v && docker-compose build --no-cache && docker-compose up -d`

---

**Last Updated:** 2024-12-20
