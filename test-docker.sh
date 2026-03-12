#!/bin/bash
# ============================================================================
# Docker Deployment Test Script
# ============================================================================
# Automated testing script for Docker deployment
# Tests all critical functionality to ensure deployment is working
#
# USAGE:
#   chmod +x test-docker.sh
#   ./test-docker.sh
# ============================================================================

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Test counters
TESTS_PASSED=0
TESTS_FAILED=0
TESTS_TOTAL=0

# Helper functions
info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

success() {
    echo -e "${GREEN}[✓]${NC} $1"
    ((TESTS_PASSED++))
}

failure() {
    echo -e "${RED}[✗]${NC} $1"
    ((TESTS_FAILED++))
}

warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

run_test() {
    ((TESTS_TOTAL++))
    echo ""
    info "Test $TESTS_TOTAL: $1"
}

# ============================================================================
# Main Test Suite
# ============================================================================

echo "================================================================"
echo "  Docker Deployment Test Suite"
echo "  Smart Guide POC - Product Recommender"
echo "================================================================"
echo ""

# ============================================================================
# Test 1: Check Prerequisites
# ============================================================================
run_test "Checking prerequisites..."

if ! command -v docker &> /dev/null; then
    failure "Docker is not installed"
    echo "Install Docker Desktop from: https://www.docker.com/products/docker-desktop"
    exit 1
fi
success "Docker is installed: $(docker --version)"

if ! command -v docker-compose &> /dev/null; then
    failure "Docker Compose is not installed"
    exit 1
fi
success "Docker Compose is installed: $(docker-compose --version)"

if ! docker info &> /dev/null; then
    failure "Docker daemon is not running"
    echo "Start Docker Desktop and try again"
    exit 1
fi
success "Docker daemon is running"

# ============================================================================
# Test 2: Check .env.docker file exists
# ============================================================================
run_test "Checking environment configuration..."

if [ ! -f .env.docker ]; then
    failure ".env.docker file not found"
    echo "Run: cp .env.docker.example .env.docker"
    echo "Then edit .env.docker and update API keys and passwords"
    exit 1
fi
success ".env.docker file exists"

# Check if default passwords are still in use
if grep -q "postgres_docker_secure_password_CHANGE_ME" .env.docker; then
    warning "Default database password detected in .env.docker"
    echo "  Please change SPRING_DATASOURCE_PASSWORD before production use"
fi

if grep -q "REPLACE_WITH_SECURE_RANDOM_KEY" .env.docker; then
    warning "Default API keys detected in .env.docker"
    echo "  Please generate secure API keys before production use"
    echo "  Run: openssl rand -base64 32 | tr -d \"=+/\" | cut -c1-40"
fi

# ============================================================================
# Test 3: Build Docker images
# ============================================================================
run_test "Building Docker images..."

info "This may take 5-10 minutes on first build..."
if docker-compose build > /tmp/docker-build.log 2>&1; then
    success "Docker images built successfully"
else
    failure "Docker build failed"
    echo "Check build logs: cat /tmp/docker-build.log"
    exit 1
fi

# Check image size
IMAGE_SIZE=$(docker images smartguide/app --format "{{.Size}}" | head -1)
info "Image size: $IMAGE_SIZE (expected: ~250-300MB)"

# ============================================================================
# Test 4: Start services
# ============================================================================
run_test "Starting Docker services..."

if docker-compose up -d > /tmp/docker-up.log 2>&1; then
    success "Services started"
else
    failure "Failed to start services"
    echo "Check logs: docker-compose logs"
    exit 1
fi

# ============================================================================
# Test 5: Wait for services to be healthy
# ============================================================================
run_test "Waiting for services to become healthy..."

info "Waiting up to 120 seconds for services..."
WAIT_TIME=0
MAX_WAIT=120

while [ $WAIT_TIME -lt $MAX_WAIT ]; do
    if docker inspect smartguide-postgres --format='{{.State.Health.Status}}' 2>/dev/null | grep -q "healthy"; then
        if docker inspect smartguide-app --format='{{.State.Health.Status}}' 2>/dev/null | grep -q "healthy"; then
            break
        fi
    fi

    sleep 5
    ((WAIT_TIME+=5))
    echo -n "."
done
echo ""

if [ $WAIT_TIME -ge $MAX_WAIT ]; then
    failure "Services did not become healthy within ${MAX_WAIT} seconds"
    echo "Check logs: docker-compose logs -f app"
    exit 1
fi

success "Services are healthy (took ${WAIT_TIME} seconds)"

# ============================================================================
# Test 6: Check container status
# ============================================================================
run_test "Checking container status..."

if docker-compose ps | grep -q "Up"; then
    success "Containers are running"
    docker-compose ps
else
    failure "Containers are not running properly"
    docker-compose ps
    exit 1
fi

# ============================================================================
# Test 7: Test health endpoint
# ============================================================================
run_test "Testing health endpoint (no auth required)..."

HEALTH_RESPONSE=$(curl -s -w "\n%{http_code}" http://localhost:8080/health)
HTTP_CODE=$(echo "$HEALTH_RESPONSE" | tail -1)
RESPONSE_BODY=$(echo "$HEALTH_RESPONSE" | head -n -1)

if [ "$HTTP_CODE" = "200" ]; then
    success "Health endpoint returned 200 OK"
    info "Response: $RESPONSE_BODY"
else
    failure "Health endpoint returned HTTP $HTTP_CODE"
    echo "Expected: 200"
    echo "Response: $RESPONSE_BODY"
fi

# ============================================================================
# Test 8: Test API without authentication (should fail)
# ============================================================================
run_test "Testing API without authentication (should return 401)..."

NO_AUTH_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8080/api/v1/recommend \
    -H "Content-Type: application/json" \
    -d '{"userInput":"travel","language":"en"}')
NO_AUTH_CODE=$(echo "$NO_AUTH_RESPONSE" | tail -1)

if [ "$NO_AUTH_CODE" = "401" ]; then
    success "API correctly rejected request without authentication"
else
    failure "API did not reject unauthenticated request (got HTTP $NO_AUTH_CODE)"
    echo "Expected: 401 Unauthorized"
fi

# ============================================================================
# Test 9: Test API with authentication (should succeed)
# ============================================================================
run_test "Testing API with authentication..."

# Extract API key from .env.docker
API_KEY=$(grep "API_KEY_USER_1=" .env.docker | cut -d'=' -f2)

if [ -z "$API_KEY" ]; then
    failure "Could not extract API_KEY_USER_1 from .env.docker"
    exit 1
fi

AUTH_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8080/api/v1/recommend \
    -H "Content-Type: application/json" \
    -H "X-API-Key: $API_KEY" \
    -d '{"userInput":"travel","language":"en"}')
AUTH_CODE=$(echo "$AUTH_RESPONSE" | tail -1)
AUTH_BODY=$(echo "$AUTH_RESPONSE" | head -n -1)

if [ "$AUTH_CODE" = "200" ]; then
    success "API returned 200 OK with authentication"

    # Check if response contains expected fields
    if echo "$AUTH_BODY" | grep -q "recommendations"; then
        success "Response contains recommendations"
    else
        warning "Response doesn't contain recommendations field"
    fi

    if echo "$AUTH_BODY" | grep -q "intent"; then
        success "Response contains intent"
    else
        warning "Response doesn't contain intent field"
    fi
else
    failure "API returned HTTP $AUTH_CODE (expected 200)"
    echo "Response: $AUTH_BODY"
fi

# ============================================================================
# Test 10: Test database connectivity
# ============================================================================
run_test "Testing database connectivity..."

DB_RESULT=$(docker-compose exec -T postgres psql -U postgres -d smart_guide_poc -c "SELECT COUNT(*) FROM products;" 2>&1)

if echo "$DB_RESULT" | grep -q "ERROR"; then
    failure "Database query failed"
    echo "$DB_RESULT"
else
    success "Database is accessible and tables exist"
    PRODUCT_COUNT=$(echo "$DB_RESULT" | grep -o '[0-9]*' | head -1)
    info "Products in database: $PRODUCT_COUNT"
fi

# ============================================================================
# Test 11: Test admin API with admin key
# ============================================================================
run_test "Testing admin API..."

ADMIN_KEY=$(grep "API_KEY_ADMIN_1=" .env.docker | cut -d'=' -f2)

if [ -z "$ADMIN_KEY" ]; then
    warning "Could not extract API_KEY_ADMIN_1 from .env.docker"
else
    ADMIN_RESPONSE=$(curl -s -w "\n%{http_code}" http://localhost:8080/api/admin/staging/pending \
        -H "X-API-Key: $ADMIN_KEY")
    ADMIN_CODE=$(echo "$ADMIN_RESPONSE" | tail -1)

    if [ "$ADMIN_CODE" = "200" ]; then
        success "Admin API is accessible with admin key"
    else
        failure "Admin API returned HTTP $ADMIN_CODE (expected 200)"
    fi
fi

# ============================================================================
# Test 12: Test resource usage
# ============================================================================
run_test "Checking resource usage..."

docker stats --no-stream smartguide-app smartguide-postgres > /tmp/docker-stats.txt
success "Resource usage captured"
cat /tmp/docker-stats.txt

# ============================================================================
# Test Results Summary
# ============================================================================
echo ""
echo "================================================================"
echo "  Test Results Summary"
echo "================================================================"
echo ""
echo "Total Tests: $TESTS_TOTAL"
echo -e "Passed: ${GREEN}$TESTS_PASSED${NC}"
echo -e "Failed: ${RED}$TESTS_FAILED${NC}"
echo ""

if [ $TESTS_FAILED -eq 0 ]; then
    echo -e "${GREEN}✓ All tests passed!${NC}"
    echo ""
    echo "Your Docker deployment is working correctly!"
    echo ""
    echo "Access the application at:"
    echo "  - Main App:    http://localhost:8080"
    echo "  - Admin UI:    http://localhost:8080/ui"
    echo "  - API Docs:    http://localhost:8080/docs"
    echo "  - Health:      http://localhost:8080/health"
    echo ""
    echo "Useful commands:"
    echo "  - View logs:       docker-compose logs -f app"
    echo "  - Stop services:   docker-compose down"
    echo "  - Restart:         docker-compose restart"
    echo "  - Database shell:  docker-compose exec postgres psql -U postgres -d smart_guide_poc"
    echo ""
    exit 0
else
    echo -e "${RED}✗ Some tests failed${NC}"
    echo ""
    echo "Troubleshooting:"
    echo "  1. Check logs: docker-compose logs -f app"
    echo "  2. Check container status: docker-compose ps"
    echo "  3. Verify .env.docker configuration"
    echo "  4. See DOCKER_QUICK_START.md for detailed troubleshooting"
    echo ""
    exit 1
fi
