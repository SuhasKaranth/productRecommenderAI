# ============================================================================
# Multi-Stage Dockerfile for Smart Guide POC
# ============================================================================
# Stage 1: Build the application (Maven + Frontend)
# Stage 2: Runtime with minimal JRE
#
# Benefits:
#   - Smaller final image (~250-300MB vs ~800MB)
#   - No source code in production image
#   - Faster deployments
#   - Better security (minimal attack surface)
# ============================================================================

# ============================================================================
# STAGE 1: BUILD
# ============================================================================
FROM maven:3.9-eclipse-temurin-17 AS builder

# Set working directory
WORKDIR /build

# ============================================================================
# Step 1: Download dependencies (cached layer)
# ============================================================================
# Copy only pom.xml first to leverage Docker cache
# Dependencies are only re-downloaded if pom.xml changes
COPY pom.xml .

# Download dependencies in offline mode for caching
RUN mvn dependency:go-offline -B

# ============================================================================
# Step 2: Copy source code
# ============================================================================
COPY src ./src
COPY frontend ./frontend

# ============================================================================
# Step 3: Build the application
# ============================================================================
# This will:
#   1. Install Node.js and npm (via frontend-maven-plugin)
#   2. Run npm install in frontend/
#   3. Build React app (npm run build)
#   4. Copy React build to src/main/resources/static
#   5. Compile Java code
#   6. Package into JAR
RUN mvn clean package -DskipTests -B

# Verify JAR was created
RUN ls -lh target/*.jar

# ============================================================================
# STAGE 2: RUNTIME
# ============================================================================
FROM eclipse-temurin:17-jre-alpine

# Metadata labels
LABEL maintainer="Smart Guide Team"
LABEL description="AI-powered Islamic Banking Product Recommendation System"
LABEL version="1.0.0"

# ============================================================================
# Install runtime dependencies
# ============================================================================
# Install Playwright browser dependencies for scraper support
# Note: Only needed if running scraper within same container
RUN apk add --no-cache \
    ca-certificates \
    wget \
    curl \
    # Uncomment below if scraper is enabled
    # chromium \
    # nss \
    # freetype \
    # harfbuzz \
    # ttf-freefont \
    && rm -rf /var/cache/apk/*

# ============================================================================
# Create application user (security best practice)
# ============================================================================
# Run as non-root user
RUN addgroup -g 1001 -S appuser && \
    adduser -u 1001 -S appuser -G appuser

# ============================================================================
# Set up application directory
# ============================================================================
WORKDIR /app

# Copy JAR from builder stage
COPY --from=builder /build/target/product-recommender-poc-1.0.0.jar app.jar

# Create logs directory
RUN mkdir -p /app/logs && \
    chown -R appuser:appuser /app

# ============================================================================
# Switch to non-root user
# ============================================================================
USER appuser

# ============================================================================
# Environment variables
# ============================================================================
# JVM Options for container environment
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

# Expose application port
EXPOSE 8080

# ============================================================================
# Health check
# ============================================================================
# Docker will mark container as unhealthy if this fails
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

# ============================================================================
# Startup command
# ============================================================================
# Use shell form to allow environment variable expansion
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

# ============================================================================
# USAGE EXAMPLES:
# ============================================================================
# Build:
#   docker build -t smartguide/app:latest .
#
# Run standalone:
#   docker run -p 8080:8080 \
#     -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/smart_guide_poc \
#     -e API_KEY_ADMIN_1=sk_admin_key \
#     smartguide/app:latest
#
# Run with docker-compose:
#   docker-compose up -d
#
# Check logs:
#   docker logs -f <container_id>
#
# Access shell:
#   docker exec -it <container_id> sh
#
# Check health:
#   docker inspect --format='{{.State.Health.Status}}' <container_id>
# ============================================================================

# ============================================================================
# NOTES:
# ============================================================================
# Image size optimization:
#   - Stage 1 (builder): ~800MB (includes Maven, Node.js, source code)
#   - Stage 2 (runtime): ~250-300MB (only JRE + JAR)
#   - Using alpine base reduces size by ~100MB vs standard images
#
# Security:
#   - Runs as non-root user (appuser)
#   - Minimal base image (fewer vulnerabilities)
#   - No source code in final image
#   - Read-only root filesystem possible (add: --read-only)
#
# Performance:
#   - Layer caching optimized (dependencies downloaded only if pom.xml changes)
#   - Multi-core builds supported
#   - Container-aware JVM settings
#
# Troubleshooting:
#   - If build fails, check Maven logs in builder stage
#   - If frontend fails, check Node.js version compatibility
#   - If runtime fails, check environment variables
#   - View build cache: docker buildx du
#   - Clear build cache: docker builder prune
# ============================================================================
