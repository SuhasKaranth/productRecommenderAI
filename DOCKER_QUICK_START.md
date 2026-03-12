# 🐳 Docker Quick Start Guide

## Prerequisites

- Docker Desktop 20.10+ ([Download](https://www.docker.com/products/docker-desktop))
- Docker Compose 2.0+ (included with Docker Desktop)
- 4GB+ RAM allocated to Docker
- 10GB+ free disk space

## 🚀 Quick Start (5 Minutes)

### Step 1: Setup Environment

```bash
# Copy environment template
cp .env.docker.example .env.docker

# Edit and update API keys
nano .env.docker
# OR
code .env.docker
```

**IMPORTANT:** Update these values in `.env.docker`:
- `SPRING_DATASOURCE_PASSWORD` - Change from default
- `API_KEY_ADMIN_1` - Generate secure key
- `API_KEY_USER_1` - Generate secure key

Generate keys:
```bash
# Generate a secure API key
openssl rand -base64 32 | tr -d "=+/" | cut -c1-40
```

### Step 2: Build and Start

```bash
# Build Docker images (first time only)
docker-compose build

# Start all services
docker-compose up -d
```

### Step 3: Verify

```bash
# Check services are running
docker-compose ps

# View logs
docker-compose logs -f app

# Test health check
curl http://localhost:8080/health
```

### Step 4: Test API

```bash
# Get your API key from .env.docker
API_KEY=$(grep "API_KEY_USER_1=" .env.docker | cut -d'=' -f2)

# Test recommendation API
curl -X POST http://localhost:8080/api/v1/recommend \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" \
  -d '{"userInput":"travel","language":"en"}'
```

---

## 📦 What Gets Started

When you run `docker-compose up`, these services start:

| Service | Port | Description |
|---------|------|-------------|
| **postgres** | 5432 | PostgreSQL 15 database |
| **app** | 8080 | Main Spring Boot application |

### Access Points

- **Main API**: http://localhost:8080
- **Admin UI**: http://localhost:8080/ui
- **API Docs**: http://localhost:8080/docs
- **Health Check**: http://localhost:8080/health

---

## 🛠 Common Commands

### Using Helper Script (Recommended)

```bash
# First time setup
./docker-commands.sh setup

# Build images
./docker-commands.sh build

# Start services
./docker-commands.sh up

# View logs
./docker-commands.sh logs

# Stop services
./docker-commands.sh down

# See all commands
./docker-commands.sh help
```

### Using Docker Compose Directly

```bash
# Start services
docker-compose up -d

# Stop services
docker-compose down

# View logs
docker-compose logs -f app
docker-compose logs -f postgres

# Restart a service
docker-compose restart app

# Rebuild and restart
docker-compose up -d --build

# Remove everything including volumes
docker-compose down -v
```

---

## 📊 Monitoring

### Check Service Health

```bash
# All services status
docker-compose ps

# Resource usage
docker stats smartguide-app smartguide-postgres

# Container health
docker inspect --format='{{.State.Health.Status}}' smartguide-app
```

### View Logs

```bash
# Follow all logs
docker-compose logs -f

# Follow specific service
docker-compose logs -f app

# Last 100 lines
docker-compose logs --tail=100 app

# Since timestamp
docker-compose logs --since="2024-12-20T10:00:00"
```

---

## 🔧 Troubleshooting

### Problem: Services won't start

```bash
# Check if ports are in use
lsof -i :8080
lsof -i :5432

# Check Docker daemon
docker ps

# Check logs for errors
docker-compose logs app
docker-compose logs postgres
```

### Problem: Database connection failed

```bash
# Check postgres is running
docker-compose ps postgres

# Check postgres logs
docker-compose logs postgres

# Access postgres shell
docker-compose exec postgres psql -U postgres -d smart_guide_poc
```

### Problem: API returns 500 errors

```bash
# Check application logs
docker-compose logs -f app

# Access container shell
docker-compose exec app sh

# Check environment variables
docker-compose exec app env | grep SPRING
```

### Problem: Out of disk space

```bash
# Remove unused images
docker image prune -a

# Remove unused volumes
docker volume prune

# Clean build cache
docker builder prune
```

### Problem: Build fails

```bash
# Clean build (no cache)
docker-compose build --no-cache

# Check build context size
docker-compose build 2>&1 | grep "Sending build context"

# Build with verbose output
docker-compose build --progress=plain
```

---

## 💾 Database Operations

### Backup Database

```bash
# Using helper script
./docker-commands.sh backup

# Manual backup
docker-compose exec -T postgres pg_dump -U postgres smart_guide_poc > backup.sql
```

### Restore Database

```bash
# Using helper script
./docker-commands.sh restore backup.sql

# Manual restore
docker-compose exec -T postgres psql -U postgres smart_guide_poc < backup.sql
```

### Access Database

```bash
# PostgreSQL shell
./docker-commands.sh db-shell

# OR
docker-compose exec postgres psql -U postgres -d smart_guide_poc

# Common SQL commands:
\dt              # List tables
\d products      # Describe products table
\l               # List databases
\q               # Quit
```

---

## 🔄 Update & Rebuild

### After Code Changes

```bash
# Rebuild and restart
docker-compose up -d --build

# OR step by step
docker-compose down
docker-compose build
docker-compose up -d
```

### Pull Latest Images

```bash
# Pull latest base images
docker-compose pull

# Rebuild with new base images
docker-compose build --pull
```

---

## 🧹 Cleanup

### Remove Containers (Keep Data)

```bash
docker-compose down
```

### Remove Everything (Including Database)

```bash
# WARNING: This deletes all data!
docker-compose down -v
```

### Clean Docker System

```bash
# Remove unused containers, networks, images
docker system prune

# Remove everything (be careful!)
docker system prune -a --volumes
```

---

## 📝 Environment Variables

### Configuration File Hierarchy

1. `.env.docker` - Your local configuration (highest priority)
2. `docker-compose.yml` - Default values
3. `application.yml` - Application defaults

### Important Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `SPRING_DATASOURCE_URL` | Yes | Database connection URL |
| `SPRING_DATASOURCE_PASSWORD` | Yes | Database password |
| `API_KEY_ADMIN_1` | Yes* | Admin API key |
| `API_KEY_USER_1` | Yes* | User API key |
| `LLM_PROVIDER` | No | `ollama` or `azure` |
| `LLM_RANKING_ENABLED` | No | Enable LLM ranking |

*Required if `API_KEY_AUTH_ENABLED=true`

---

## 🐛 Debug Mode

### Access Container Shell

```bash
# Application container
docker-compose exec app sh

# Inside container:
ls -la                    # List files
cat /app/app.jar          # Check JAR exists
java -version             # Check Java version
env | grep SPRING         # Check environment
wget http://localhost:8080/health  # Test from inside
```

### Check Network

```bash
# List networks
docker network ls

# Inspect network
docker network inspect smartguide-network

# Test connectivity
docker-compose exec app ping postgres
```

---

## 📈 Performance Tuning

### Adjust Resource Limits

Edit `docker-compose.yml`:

```yaml
services:
  app:
    deploy:
      resources:
        limits:
          cpus: '4.0'        # Increase CPU
          memory: 4G         # Increase memory
```

### Adjust JVM Settings

In `.env.docker`:

```bash
JAVA_OPTS=-Xmx2g -Xms1g -XX:+UseG1GC
```

---

## 🔒 Security Checklist

Before deploying to production:

- [ ] Changed default database password
- [ ] Generated new API keys (not from example)
- [ ] Set `API_KEY_AUTH_ENABLED=true`
- [ ] Different keys for each environment
- [ ] `.env.docker` not committed to git
- [ ] File permissions: `chmod 600 .env.docker`
- [ ] Using HTTPS/TLS (reverse proxy)
- [ ] Regular security updates: `docker-compose pull`

---

## 🎯 Production Deployment

For production use, create `docker-compose.prod.yml`:

```yaml
version: '3.8'

services:
  app:
    image: ghcr.io/your-org/smartguide:latest
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: ${PROD_DATABASE_URL}
    deploy:
      replicas: 2
      restart_policy:
        condition: on-failure
```

Run with:
```bash
docker-compose -f docker-compose.prod.yml up -d
```

---

## 📚 Additional Resources

- [Docker Documentation](https://docs.docker.com/)
- [Docker Compose Reference](https://docs.docker.com/compose/compose-file/)
- [Main README](README.md)
- [Setup Guide](SETUP_GUIDE.md)
- [API Testing Guide](API_KEY_TESTING_GUIDE.md)

---

## ❓ Need Help?

1. Check logs: `docker-compose logs -f app`
2. Review this guide
3. Check GitHub Issues
4. Contact dev team

---

**Last Updated**: 2024-12-20
