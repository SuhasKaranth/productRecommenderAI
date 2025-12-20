# Smart Guide POC - Islamic Banking Product Recommendation System

AI-powered Islamic banking product recommendation system with automated web scraping, staging workflow, and intelligent product recommendations based on natural language input.

## Overview

This is a comprehensive Java Spring Boot application designed for enterprise deployment, featuring:
- **AI-Powered Recommendations**: Natural language processing for product recommendations
- **Automated Web Scraping**: Extract product data from banking websites using Playwright
- **Admin Workflow**: Review, edit, and approve scraped products before publishing
- **Multi-Service Architecture**: Main API service + separate scraper microservice
- **Modern Web UI**: React-based admin interface for product management

## Key Features

### Core Recommendation Engine
- Natural language intent extraction using LLM (Azure OpenAI or Ollama)
- Rule-based product category mapping with 11 banking intents
- Intelligent LLM-based product ranking with formula fallback
- Support for English and Arabic inputs
- Fast response times (<1.5 seconds)
- 100% Sharia-compliant product recommendations
- Keyword-based search and filtering

### Web Scraping & Data Collection
- Automated product scraping using Playwright browser automation
- AI-powered product data extraction and validation
- Configurable scraping rules per website (YAML-based)
- Data quality scoring and categorization
- Job tracking and monitoring
- Scrape history and audit logs

### Admin & Staging Workflow
- Web-based admin UI for product review
- Three-state approval workflow: PENDING → APPROVED → REJECTED
- Edit scraped data before publishing
- Bulk approve/reject operations
- Statistics dashboard
- Staging-to-production promotion

### Technical Features
- OpenAPI/Swagger documentation
- Flyway database migrations
- Comprehensive error handling with fallback mechanisms
- PostgreSQL with JSONB support
- Multi-environment configuration

## Technology Stack

### Backend
- **Java**: 17
- **Framework**: Spring Boot 3.2.0
- **Build Tool**: Maven 3.8+
- **Database**: PostgreSQL 15+ with JSONB support
- **ORM**: Spring Data JPA / Hibernate
- **Migration**: Flyway
- **Documentation**: SpringDoc OpenAPI 3
- **HTTP Client**: WebClient (Spring WebFlux)
- **JSON Processing**: Jackson

### Frontend
- **React**: 18.x
- **Build Tool**: Vite
- **Styling**: CSS3

### Web Scraping
- **Browser Automation**: Playwright (Chromium)
- **AI Extraction**: Azure OpenAI / Ollama
- **Configuration**: YAML-based scraper configs

## Prerequisites

- Java 17 or higher
- Maven 3.8+
- PostgreSQL 15+
- Node.js 18+ and npm (for frontend build)
- Azure OpenAI API key (or Ollama for local development)
- Playwright browsers (for web scraping): `mvn exec:java -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"`

## Documentation

- **[SETUP_GUIDE.md](SETUP_GUIDE.md)** - Complete installation and configuration guide
- **[QUICK_START.md](QUICK_START.md)** - Quick start guide for web scraping workflow
- **[product-scraper-service/README.md](product-scraper-service/README.md)** - Scraper service architecture and configuration

## Quick Start

### 1. Clone the Repository

```bash
cd productRecommenderAI-springboot
```

### 2. Configure Database

Create PostgreSQL database:

```bash
createdb smart_guide_poc
```

### 3. Configure Environment

Copy `.env.example` to `.env` and configure:

```bash
cp .env.example .env
```

Edit `.env` with your configuration:

```properties
# Database
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/smart_guide_poc
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=your_password

# LLM Provider
LLM_PROVIDER=ollama  # or 'azure'

# Azure OpenAI (if using Azure)
AZURE_OPENAI_ENDPOINT=https://your-resource.openai.azure.com/
AZURE_OPENAI_API_KEY=your-api-key
AZURE_OPENAI_DEPLOYMENT=your-deployment-name

# Ollama (if using Ollama)
OLLAMA_HOST=http://localhost:11434
OLLAMA_MODEL=llama3.2
```

### 4. Build the Project

```bash
mvn clean install
```

### 5. Run the Application

```bash
mvn spring-boot:run
```

Or run the JAR file:

```bash
java -jar target/product-recommender-poc-1.0.0.jar
```

The application will start on:
- **Main API**: `http://localhost:8080`
- **Admin UI**: `http://localhost:8080/ui`
- **API Docs**: `http://localhost:8080/docs`

### 6. (Optional) Start Product Scraper Service

If you want to use the web scraping functionality:

```bash
cd product-scraper-service
mvn spring-boot:run
```

Scraper service will start on `http://localhost:8081`

### 7. Database Migration

Flyway will automatically run migrations on startup. The database schema and seed data will be created automatically.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                         Users / API Clients                  │
└────────────────┬────────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────────┐
│              Main Spring Boot Application (8080)             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Web UI (React)          │  REST API                  │  │
│  │  - Staging Review        │  - /api/v1/recommend       │  │
│  │  - Product Management    │  - /api/admin/*            │  │
│  │  - Scrape Job Monitor    │  - /health, /docs          │  │
│  └──────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              Business Logic Layer                     │  │
│  │  - LLMService (Intent + Ranking)                      │  │
│  │  - ProductService (Search + Filter)                   │  │
│  │  - RulesEngine (Intent → Category)                    │  │
│  │  - StagingProductService (Approve/Reject)             │  │
│  └──────────────────────────────────────────────────────┘  │
└────────────────┬────────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────────┐
│          Product Scraper Service (8081) - Optional          │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  - ScraperOrchestrationService                        │  │
│  │  - PlaywrightScraperEngine (Browser Control)          │  │
│  │  - AIProductExtractor (LLM-based extraction)          │  │
│  │  - LLMDataEnricher (Quality scoring)                  │  │
│  └──────────────────────────────────────────────────────┘  │
└────────────────┬────────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────────┐
│                  PostgreSQL Database                         │
│  - products (approved products)                              │
│  - staging_products (pending review)                         │
│  - scrape_logs (job tracking)                                │
│  - scrape_sources (website configs)                          │
│  - intent_category_mapping (rules)                           │
└─────────────────────────────────────────────────────────────┘
                 │
                 ▼
        ┌────────────────────┐
        │  Azure OpenAI /    │
        │  Ollama (LLM)      │
        └────────────────────┘
```

## Using Ollama (Local LLM)

### 1. Install Ollama

Visit [https://ollama.ai](https://ollama.ai) for installation instructions.

### 2. Pull Model

```bash
ollama pull llama3.2
```

### 3. Start Ollama Server

```bash
ollama serve
```

### 4. Configure Application

Set in `application.yml` or `.env`:

```yaml
app:
  llm:
    provider: ollama
    ollama:
      host: http://localhost:11434
      model: llama3.2
```

## Usage

### 1. Access Web Admin UI

Open your browser and navigate to:

```
http://localhost:8080/ui
```

Features:
- **Scrape Form**: Configure and start new scraping jobs
- **Staging Review**: Review, edit, approve/reject scraped products
- **All Products**: View all approved products in the system
- **Test Recommendations**: Test the recommendation API

### 2. API Usage

#### Health Check

```bash
curl http://localhost:8080/health
```

#### Get Product Recommendations

```bash
curl -X POST http://localhost:8080/api/v1/recommend \
  -H "Content-Type: application/json" \
  -d '{
    "userInput": "I want to travel to Brazil",
    "language": "en"
  }'
```

### Example Response

```json
{
  "status": "success",
  "intent": {
    "detectedIntent": "TRAVEL",
    "confidence": 0.96,
    "entities": {
      "destination": "Brazil"
    }
  },
  "recommendations": [
    {
      "rank": 1,
      "productId": 1,
      "productCode": "CC_TRAVEL_01",
      "productName": "Voyager Travel Credit Card",
      "category": "CREDIT_CARD",
      "islamicStructure": "Murabaha",
      "relevanceScore": 0.92,
      "reason": "Perfect for travelers with Murabaha structure and travel benefits",
      "keyBenefits": [
        "5% cashback on international travel",
        "No foreign transaction fees",
        "Airport lounge access worldwide"
      ],
      "annualFee": 150.0,
      "minIncome": 50000.0
    }
  ],
  "processingTimeMs": 1240
}
```

#### Start a Scraping Job

```bash
curl -X POST http://localhost:8081/api/scraper/scrape \
  -H "Content-Type: application/json" \
  -d '{
    "configFile": "example-bank.yml",
    "scrapeUrl": "https://example-bank.com/products",
    "options": {
      "maxProducts": 10,
      "useAIExtraction": true
    }
  }'
```

#### Review Staging Products

```bash
curl http://localhost:8080/api/admin/staging/pending
```

#### Approve a Staging Product

```bash
curl -X POST http://localhost:8080/api/admin/staging/{id}/approve
```

## API Documentation

Once the application is running, access the interactive API documentation:

- **Swagger UI**: http://localhost:8080/docs
- **OpenAPI JSON**: http://localhost:8080/api-docs

## Web Scraping Configuration

Scraper configurations are stored in YAML files under `scraper-configs/`:

```yaml
# scraper-configs/example-bank.yml
website: example-bank
base_url: https://example-bank.com
selectors:
  product_list: ".product-card"
  product_name: "h3.product-title"
  product_description: ".description"
  # ... more selectors
options:
  wait_for_load: true
  pagination: true
  max_pages: 5
```

See [product-scraper-service/README.md](product-scraper-service/README.md) for detailed configuration options.

## Project Structure

```
productRecommenderAI-springboot/
├── src/main/
│   ├── java/com/smartguide/poc/
│   │   ├── SmartGuidePocApplication.java           # Main application
│   │   ├── admin/                                  # Admin module
│   │   │   ├── controller/AdminStagingController.java
│   │   │   ├── service/StagingProductService.java
│   │   │   └── dto/StagingProductDTO.java
│   │   ├── config/
│   │   │   ├── CorsConfig.java                     # CORS configuration
│   │   │   └── LLMConfig.java                      # LLM provider config
│   │   ├── controller/
│   │   │   ├── HealthController.java               # Health checks
│   │   │   ├── RecommendationController.java       # Main API
│   │   │   ├── ProductController.java              # Product CRUD
│   │   │   └── UIController.java                   # Frontend routes
│   │   ├── dto/                                    # Data transfer objects
│   │   ├── entity/
│   │   │   ├── Product.java                        # Main product entity
│   │   │   ├── StagingProduct.java                 # Staging entity
│   │   │   └── IntentCategoryMapping.java
│   │   ├── exception/GlobalExceptionHandler.java
│   │   ├── repository/                             # JPA repositories
│   │   └── service/
│   │       ├── LLMService.java                     # LLM integration
│   │       ├── ProductService.java                 # Product logic
│   │       └── RulesEngine.java                    # Business rules
│   └── resources/
│       ├── application.yml                         # Main config
│       ├── db/migration/                           # Flyway migrations
│       │   ├── V1__Create_tables.sql
│       │   ├── V2__Seed_data.sql
│       │   ├── V3__Add_scraping_metadata.sql
│       │   ├── V5__Create_staging_products.sql
│       │   └── V10__Add_keywords_to_products.sql
│       └── static/                                 # React build output
│
├── product-scraper-service/                        # Scraper microservice
│   ├── src/main/java/com/smartguide/scraper/
│   │   ├── ProductScraperApplication.java
│   │   ├── controller/ScraperController.java
│   │   ├── service/
│   │   │   ├── ScraperOrchestrationService.java   # Main orchestrator
│   │   │   ├── PlaywrightScraperEngine.java       # Browser automation
│   │   │   ├── AIProductExtractor.java            # AI extraction
│   │   │   ├── LLMDataEnricher.java               # Quality scoring
│   │   │   └── StagingProductService.java         # DB persistence
│   │   ├── dto/                                   # Scraper DTOs
│   │   └── config/                                # Scraper configs
│   └── README.md
│
├── frontend/                                       # React admin UI
│   ├── src/
│   │   ├── App.jsx                                # Main app component
│   │   ├── pages/
│   │   │   ├── ScrapeForm.jsx                     # Scraping interface
│   │   │   ├── StagingReview.jsx                  # Approval workflow
│   │   │   ├── AllProducts.jsx                    # Product listing
│   │   │   └── TestRecommendations.jsx            # API testing
│   │   └── services/api.js                        # API client
│   ├── package.json
│   └── vite.config.js
│
├── scraper-configs/                                # Website configs
│   ├── example-bank.yml
│   └── maybank-islamic.yml
│
├── pom.xml                                        # Maven build
├── .env.example                                   # Environment template
├── .gitignore
├── README.md                                      # This file
├── SETUP_GUIDE.md                                 # Detailed setup
└── QUICK_START.md                                 # Quick start guide
```

## Configuration

### Application Properties

Key configuration in `src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/smart_guide_poc
    username: postgres
    password: postgres

server:
  port: 8080

app:
  llm:
    provider: ollama  # or 'azure'
    ollama:
      host: http://localhost:11434
      model: llama3.2
```

## Building for Production

### Build Complete Application (Backend + Frontend)

The Maven build automatically compiles the React frontend and includes it in the JAR:

```bash
mvn clean package -DskipTests
```

This will:
1. Install Node.js and npm (via frontend-maven-plugin)
2. Run `npm install` in the frontend directory
3. Build the React app (`npm run build`)
4. Copy the build to `src/main/resources/static`
5. Package everything into a single JAR

The JAR file will be created in `target/product-recommender-poc-1.0.0.jar`

### Run Production Build

```bash
java -jar target/product-recommender-poc-1.0.0.jar
```

Access the application:
- Main API: http://localhost:8080
- Admin UI: http://localhost:8080/ui
- API Docs: http://localhost:8080/docs

### Build Scraper Service Separately

```bash
cd product-scraper-service
mvn clean package
java -jar target/product-scraper-service-1.0.0.jar
```

### Docker Support (Future Enhancement)

Production deployment should include:
- Multi-stage Dockerfile for optimized image size
- Docker Compose for local development with PostgreSQL
- Kubernetes manifests for orchestration
- Environment-specific configurations

## Testing

### Run Tests

```bash
mvn test
```

### Run with Coverage

```bash
mvn test jacoco:report
```

## Differences from Python Version

| Aspect | Python (FastAPI) | Java (Spring Boot) |
|--------|------------------|-------------------|
| Language | Python 3.10+ | Java 17 |
| Framework | FastAPI | Spring Boot 3.2 |
| ORM | SQLAlchemy | JPA/Hibernate |
| Async | Native async/await | Synchronous (can add WebFlux) |
| Type System | Dynamic + Pydantic | Static + Bean Validation |
| Migration | Manual SQL | Flyway |
| DI Container | FastAPI Depends | Spring IoC |
| Enterprise Support | Limited | Extensive |

## Performance Metrics

- Backend latency: <1.5 seconds (excluding LLM call)
- Supports 100+ concurrent requests
- Database queries optimized with indexes
- Intent extraction accuracy: Target 85%+

## Troubleshooting

### Database Connection Issues

Ensure PostgreSQL is running and credentials are correct in `application.yml` or `.env`.

### LLM Service Timeout

Increase timeout in `application.yml`:

```yaml
app:
  llm:
    ollama:
      timeout: 60000  # 60 seconds
```

### Port Already in Use

Change server port in `application.yml`:

```yaml
server:
  port: 8081
```

## Workflow: From Scraping to Recommendation

1. **Configure Scraper**: Create YAML config for target website
2. **Start Scraping Job**: Use UI or API to initiate scraping
3. **AI Extraction**: Playwright + LLM extract product data
4. **Data Review**: Products land in staging with quality scores
5. **Admin Approval**: Review and edit products in admin UI
6. **Promote to Production**: Approved products become available for recommendations
7. **Recommendations**: End users get AI-powered product suggestions

## Database Schema

### Core Tables
- **products**: Approved products available for recommendations (80+ columns)
- **staging_products**: Pending review products from scraping
- **scrape_logs**: Audit trail of all scraping jobs
- **scrape_sources**: Website configuration metadata
- **intent_category_mapping**: Intent → Category mapping rules

### Key Features
- JSONB columns for flexible data (eligibility_criteria, key_benefits)
- Array columns for keywords
- Full-text search ready
- Referential integrity with cascades

## Production Readiness Checklist

This is a POC/MVP application. For production deployment, you should implement:

### Critical (Security & Reliability)
- [ ] Spring Security with JWT authentication
- [ ] API rate limiting and throttling
- [ ] HTTPS/TLS configuration
- [ ] Secrets management (Vault/AWS Secrets Manager)
- [ ] Comprehensive unit and integration tests (current coverage: 0%)
- [ ] Database backup and disaster recovery procedures

### Important (Scalability & Monitoring)
- [ ] Redis caching layer
- [ ] Circuit breakers (Resilience4j)
- [ ] Metrics and monitoring (Prometheus/Grafana)
- [ ] Centralized logging (ELK Stack)
- [ ] Database connection pool tuning
- [ ] Async processing for long-running scraper jobs

### Nice to Have
- [ ] Docker and Kubernetes deployment
- [ ] CI/CD pipeline (GitHub Actions)
- [ ] Feature flags
- [ ] API versioning strategy
- [ ] Performance optimization and load testing
- [ ] Comprehensive documentation

See the production readiness analysis for detailed requirements.

## License

This is a POC project for demonstration purposes.

## Support

For issues or questions, please refer to the project documentation or contact the development team.
