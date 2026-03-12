#!/bin/bash
# ============================================================================
# Smart Guide POC - Unified Development Startup Script
# ============================================================================
# Starts all three services for local development:
#   1. Smart POC Service (Spring Boot backend) - Port 8080
#   2. Frontend (React development server) - Port 3000
#   3. Product Scraper Service (Spring Boot) - Port 8081
#
# USAGE:
#   chmod +x start-all.sh
#   ./start-all.sh [command]
#
# COMMANDS:
#   start       - Start all services (default)
#   stop        - Stop all services
#   restart     - Restart all services
#   status      - Check status of all services
#   logs        - Show logs directory
#   clean       - Stop services and clean log files
# ============================================================================

set -e

# Project root directory
PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
FRONTEND_DIR="$PROJECT_ROOT/frontend"
SCRAPER_DIR="$PROJECT_ROOT/product-scraper-service"
LOGS_DIR="$PROJECT_ROOT/logs"
PID_DIR="$PROJECT_ROOT/.pids"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Helper functions
info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

header() {
    echo -e "${CYAN}============================================${NC}"
    echo -e "${CYAN} $1${NC}"
    echo -e "${CYAN}============================================${NC}"
}

# Create necessary directories
setup_dirs() {
    mkdir -p "$LOGS_DIR"
    mkdir -p "$PID_DIR"
}

# ============================================================================
# Prerequisite Checks
# ============================================================================
check_prerequisites() {
    header "Checking Prerequisites"

    local missing=0

    # Check Java
    if command -v java &> /dev/null; then
        JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2)
        success "Java: $JAVA_VERSION"
    else
        error "Java is not installed"
        missing=1
    fi

    # Check Maven
    if command -v mvn &> /dev/null; then
        MVN_VERSION=$(mvn -version 2>&1 | head -n 1 | cut -d' ' -f3)
        success "Maven: $MVN_VERSION"
    else
        error "Maven is not installed"
        missing=1
    fi

    # Check Node.js
    if command -v node &> /dev/null; then
        NODE_VERSION=$(node -v)
        success "Node.js: $NODE_VERSION"
    else
        error "Node.js is not installed"
        missing=1
    fi

    # Check npm
    if command -v npm &> /dev/null; then
        NPM_VERSION=$(npm -v)
        success "npm: $NPM_VERSION"
    else
        error "npm is not installed"
        missing=1
    fi

    # Check PostgreSQL connectivity
    if command -v psql &> /dev/null; then
        success "PostgreSQL client: installed"
    else
        warning "PostgreSQL client not found (optional)"
    fi

    # Check .env file
    if [ -f "$PROJECT_ROOT/.env" ]; then
        success ".env file: found"
    else
        warning ".env file not found. Copy from .env.example:"
        echo "        cp .env.example .env"
    fi

    echo ""
    if [ $missing -eq 1 ]; then
        error "Missing prerequisites. Please install them and try again."
        exit 1
    fi
}

# ============================================================================
# Check if port is in use
# ============================================================================
check_port() {
    local port=$1
    if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1; then
        return 0  # Port is in use
    else
        return 1  # Port is free
    fi
}

# ============================================================================
# Wait for service to be ready
# ============================================================================
wait_for_service() {
    local name=$1
    local url=$2
    local max_attempts=${3:-60}
    local attempt=1

    info "Waiting for $name to be ready..."
    while [ $attempt -le $max_attempts ]; do
        if curl -s "$url" > /dev/null 2>&1; then
            success "$name is ready!"
            return 0
        fi
        sleep 2
        attempt=$((attempt + 1))
        printf "."
    done
    echo ""
    error "$name failed to start within timeout"
    return 1
}

# ============================================================================
# Start Smart POC Service (Main Backend)
# ============================================================================
start_backend() {
    header "Starting Smart POC Service (Port 8080)"

    if check_port 8080; then
        warning "Port 8080 is already in use. Backend may already be running."
        return 0
    fi

    cd "$PROJECT_ROOT"

    info "Starting Spring Boot application..."
    nohup mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx1g" \
        > "$LOGS_DIR/backend.log" 2>&1 &

    echo $! > "$PID_DIR/backend.pid"
    success "Backend starting... (PID: $!)"
    info "Logs: $LOGS_DIR/backend.log"

    # Wait for backend to be ready before starting other services
    wait_for_service "Backend" "http://localhost:8080/health" 90
}

# ============================================================================
# Start Frontend (React Dev Server)
# ============================================================================
start_frontend() {
    header "Starting Frontend (Port 3000)"

    if check_port 3000; then
        warning "Port 3000 is already in use. Frontend may already be running."
        return 0
    fi

    cd "$FRONTEND_DIR"

    # Install dependencies if node_modules doesn't exist
    if [ ! -d "node_modules" ]; then
        info "Installing frontend dependencies..."
        npm install
    fi

    info "Starting React development server..."
    nohup npm start > "$LOGS_DIR/frontend.log" 2>&1 &

    echo $! > "$PID_DIR/frontend.pid"
    success "Frontend starting... (PID: $!)"
    info "Logs: $LOGS_DIR/frontend.log"

    wait_for_service "Frontend" "http://localhost:3000" 60
}

# ============================================================================
# Start Scraper Service
# ============================================================================
start_scraper() {
    header "Starting Scraper Service (Port 8081)"

    if check_port 8081; then
        warning "Port 8081 is already in use. Scraper may already be running."
        return 0
    fi

    cd "$SCRAPER_DIR"

    info "Starting Scraper Service..."
    nohup mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx512m" \
        > "$LOGS_DIR/scraper.log" 2>&1 &

    echo $! > "$PID_DIR/scraper.pid"
    success "Scraper starting... (PID: $!)"
    info "Logs: $LOGS_DIR/scraper.log"

    wait_for_service "Scraper" "http://localhost:8081/health" 90
}

# ============================================================================
# Stop a service by PID file
# ============================================================================
stop_service() {
    local name=$1
    local pid_file=$2
    local port=$3

    if [ -f "$pid_file" ]; then
        local pid=$(cat "$pid_file")
        if ps -p $pid > /dev/null 2>&1; then
            info "Stopping $name (PID: $pid)..."
            kill $pid 2>/dev/null || true
            sleep 2
            # Force kill if still running
            if ps -p $pid > /dev/null 2>&1; then
                kill -9 $pid 2>/dev/null || true
            fi
            success "$name stopped"
        fi
        rm -f "$pid_file"
    fi

    # Also kill by port if still running
    if [ -n "$port" ] && check_port $port; then
        info "Killing process on port $port..."
        lsof -ti:$port | xargs kill -9 2>/dev/null || true
    fi
}

# ============================================================================
# Stop all services
# ============================================================================
stop_all() {
    header "Stopping All Services"

    stop_service "Frontend" "$PID_DIR/frontend.pid" 3000
    stop_service "Scraper Service" "$PID_DIR/scraper.pid" 8081
    stop_service "Backend" "$PID_DIR/backend.pid" 8080

    # Kill any remaining Maven processes for this project
    pkill -f "spring-boot:run.*product-recommender" 2>/dev/null || true
    pkill -f "spring-boot:run.*product-scraper" 2>/dev/null || true

    success "All services stopped"
}

# ============================================================================
# Check status of all services
# ============================================================================
check_status() {
    header "Service Status"

    echo ""
    echo "Service              Port    Status"
    echo "-------------------------------------------"

    # Backend
    if check_port 8080; then
        echo -e "Smart POC Backend    8080    ${GREEN}RUNNING${NC}"
    else
        echo -e "Smart POC Backend    8080    ${RED}STOPPED${NC}"
    fi

    # Frontend
    if check_port 3000; then
        echo -e "Frontend (React)     3000    ${GREEN}RUNNING${NC}"
    else
        echo -e "Frontend (React)     3000    ${RED}STOPPED${NC}"
    fi

    # Scraper
    if check_port 8081; then
        echo -e "Scraper Service      8081    ${GREEN}RUNNING${NC}"
    else
        echo -e "Scraper Service      8081    ${RED}STOPPED${NC}"
    fi

    echo ""
    echo "Access URLs:"
    echo "  - Frontend:      http://localhost:3000"
    echo "  - Backend API:   http://localhost:8080"
    echo "  - API Docs:      http://localhost:8080/docs"
    echo "  - Admin UI:      http://localhost:8080/ui"
    echo "  - Scraper API:   http://localhost:8081"
    echo "  - Scraper Docs:  http://localhost:8081/docs"
    echo ""
}

# ============================================================================
# Start all services
# ============================================================================
start_all() {
    check_prerequisites
    setup_dirs

    header "Starting All Services"
    echo ""
    info "This will start:"
    echo "  1. Smart POC Backend (Port 8080)"
    echo "  2. React Frontend (Port 3000)"
    echo "  3. Scraper Service (Port 8081)"
    echo ""

    # Start services in order (backend first, then others)
    start_backend
    echo ""
    start_frontend
    echo ""
    start_scraper
    echo ""

    header "All Services Started"
    check_status

    success "All services are running!"
    echo ""
    info "To view logs:"
    echo "  tail -f $LOGS_DIR/backend.log"
    echo "  tail -f $LOGS_DIR/frontend.log"
    echo "  tail -f $LOGS_DIR/scraper.log"
    echo ""
    info "To stop all services:"
    echo "  ./start-all.sh stop"
}

# ============================================================================
# Show logs info
# ============================================================================
show_logs() {
    header "Log Files"

    echo ""
    echo "Log Directory: $LOGS_DIR"
    echo ""

    if [ -d "$LOGS_DIR" ]; then
        echo "Available logs:"
        ls -la "$LOGS_DIR"/*.log 2>/dev/null || echo "  No log files yet"
    fi

    echo ""
    echo "View logs in real-time:"
    echo "  tail -f $LOGS_DIR/backend.log   # Backend logs"
    echo "  tail -f $LOGS_DIR/frontend.log  # Frontend logs"
    echo "  tail -f $LOGS_DIR/scraper.log   # Scraper logs"
    echo ""
    echo "View all logs together:"
    echo "  tail -f $LOGS_DIR/*.log"
}

# ============================================================================
# Clean up
# ============================================================================
clean_all() {
    stop_all

    header "Cleaning Up"

    if [ -d "$LOGS_DIR" ]; then
        info "Removing log files..."
        rm -rf "$LOGS_DIR"
    fi

    if [ -d "$PID_DIR" ]; then
        info "Removing PID files..."
        rm -rf "$PID_DIR"
    fi

    success "Cleanup complete"
}

# ============================================================================
# Show help
# ============================================================================
show_help() {
    cat << EOF
Smart Guide POC - Unified Development Startup Script

USAGE:
  ./start-all.sh [command]

COMMANDS:
  start       Start all services (default)
  stop        Stop all services
  restart     Restart all services
  status      Check status of all services
  logs        Show logs directory and commands
  clean       Stop services and clean log files
  help        Show this help

SERVICES:
  1. Smart POC Backend    - Port 8080 (Spring Boot)
  2. Frontend (React)     - Port 3000 (Development Server)
  3. Scraper Service      - Port 8081 (Spring Boot)

EXAMPLES:
  ./start-all.sh              # Start all services
  ./start-all.sh start        # Start all services
  ./start-all.sh stop         # Stop all services
  ./start-all.sh status       # Check service status
  ./start-all.sh restart      # Restart all services

PREREQUISITES:
  - Java 17+
  - Maven 3.9+
  - Node.js 18+
  - npm 9+
  - PostgreSQL running on localhost:5432

LOGS:
  Logs are stored in: ./logs/
  - backend.log   : Smart POC Backend logs
  - frontend.log  : React development server logs
  - scraper.log   : Scraper Service logs

EOF
}

# ============================================================================
# Main command dispatcher
# ============================================================================
case "${1:-start}" in
    start)
        start_all
        ;;
    stop)
        stop_all
        ;;
    restart)
        stop_all
        echo ""
        sleep 3
        start_all
        ;;
    status)
        check_status
        ;;
    logs)
        show_logs
        ;;
    clean)
        clean_all
        ;;
    help|--help|-h)
        show_help
        ;;
    *)
        error "Unknown command: $1"
        echo ""
        show_help
        exit 1
        ;;
esac
