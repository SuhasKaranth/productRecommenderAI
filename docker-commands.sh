#!/bin/bash
# ============================================================================
# Docker Helper Commands for Smart Guide POC
# ============================================================================
# Collection of useful Docker commands for development and deployment
#
# USAGE:
#   chmod +x docker-commands.sh
#   ./docker-commands.sh <command>
#
# COMMANDS:
#   setup       - First-time setup (create .env.docker)
#   build       - Build Docker images
#   up          - Start all services
#   down        - Stop all services
#   restart     - Restart services
#   logs        - View logs
#   clean       - Remove containers and volumes
#   test        - Test the application
#   shell       - Access container shell
#   db-shell    - Access PostgreSQL shell
#   backup      - Backup database
#   restore     - Restore database
#   stats       - Show resource usage
# ============================================================================

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
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

# ============================================================================
# Command: setup - First-time setup
# ============================================================================
cmd_setup() {
    info "Setting up Docker environment..."

    if [ ! -f .env.docker ]; then
        info "Creating .env.docker from template..."
        cp .env.docker.example .env.docker
        warning "IMPORTANT: Edit .env.docker and update API keys and passwords!"
        warning "Run: nano .env.docker OR code .env.docker"
    else
        warning ".env.docker already exists. Skipping..."
    fi

    success "Setup complete! Next steps:"
    echo "  1. Edit .env.docker and update secrets"
    echo "  2. Run: ./docker-commands.sh build"
    echo "  3. Run: ./docker-commands.sh up"
}

# ============================================================================
# Command: build - Build Docker images
# ============================================================================
cmd_build() {
    info "Building Docker images..."
    docker-compose build
    success "Build complete!"

    info "Image sizes:"
    docker images | grep smartguide
}

# ============================================================================
# Command: up - Start all services
# ============================================================================
cmd_up() {
    info "Starting services..."
    docker-compose up -d

    success "Services started!"
    echo ""
    info "Access points:"
    echo "  - Main App:    http://localhost:8080"
    echo "  - Admin UI:    http://localhost:8080/ui"
    echo "  - API Docs:    http://localhost:8080/docs"
    echo "  - Health:      http://localhost:8080/health"
    echo ""
    info "View logs: ./docker-commands.sh logs"
}

# ============================================================================
# Command: down - Stop all services
# ============================================================================
cmd_down() {
    info "Stopping services..."
    docker-compose down
    success "Services stopped!"
}

# ============================================================================
# Command: restart - Restart services
# ============================================================================
cmd_restart() {
    info "Restarting services..."
    docker-compose restart
    success "Services restarted!"
}

# ============================================================================
# Command: logs - View logs
# ============================================================================
cmd_logs() {
    info "Showing logs (Ctrl+C to exit)..."
    docker-compose logs -f "${2:-app}"
}

# ============================================================================
# Command: clean - Remove containers and volumes
# ============================================================================
cmd_clean() {
    warning "This will remove all containers and volumes (including database data)!"
    read -p "Are you sure? (yes/no): " -r
    if [[ $REPLY =~ ^[Yy][Ee][Ss]$ ]]; then
        info "Cleaning up..."
        docker-compose down -v
        success "Cleanup complete!"
    else
        info "Cancelled."
    fi
}

# ============================================================================
# Command: test - Test the application
# ============================================================================
cmd_test() {
    info "Testing application..."

    # Wait for services to be ready
    info "Waiting for services to start..."
    sleep 10

    # Test health endpoint
    info "Testing health endpoint..."
    curl -f http://localhost:8080/health || error "Health check failed!"

    # Test with API key (if available)
    if [ -f .env.docker ]; then
        API_KEY=$(grep "API_KEY_USER_1=" .env.docker | cut -d'=' -f2)
        if [ -n "$API_KEY" ] && [ "$API_KEY" != "sk_user_REPLACE_WITH_SECURE_RANDOM_KEY_40_CHARS_MIN" ]; then
            info "Testing recommendation API..."
            curl -X POST http://localhost:8080/api/v1/recommend \
                -H "Content-Type: application/json" \
                -H "X-API-Key: $API_KEY" \
                -d '{"userInput":"travel","language":"en"}' \
                | jq '.' || warning "API test failed (check if jq is installed)"
        else
            warning "API key not configured in .env.docker. Skipping API test."
        fi
    fi

    success "Testing complete!"
}

# ============================================================================
# Command: shell - Access container shell
# ============================================================================
cmd_shell() {
    info "Accessing application container shell..."
    docker-compose exec app sh
}

# ============================================================================
# Command: db-shell - Access PostgreSQL shell
# ============================================================================
cmd_db_shell() {
    info "Accessing PostgreSQL shell..."
    docker-compose exec postgres psql -U postgres -d smart_guide_poc
}

# ============================================================================
# Command: backup - Backup database
# ============================================================================
cmd_backup() {
    BACKUP_FILE="backup-$(date +%Y%m%d-%H%M%S).sql"
    info "Backing up database to $BACKUP_FILE..."
    docker-compose exec -T postgres pg_dump -U postgres smart_guide_poc > "$BACKUP_FILE"
    success "Database backed up to $BACKUP_FILE"
}

# ============================================================================
# Command: restore - Restore database
# ============================================================================
cmd_restore() {
    if [ -z "$2" ]; then
        error "Usage: ./docker-commands.sh restore <backup-file.sql>"
        exit 1
    fi

    BACKUP_FILE="$2"
    if [ ! -f "$BACKUP_FILE" ]; then
        error "Backup file not found: $BACKUP_FILE"
        exit 1
    fi

    warning "This will overwrite the current database!"
    read -p "Are you sure? (yes/no): " -r
    if [[ $REPLY =~ ^[Yy][Ee][Ss]$ ]]; then
        info "Restoring database from $BACKUP_FILE..."
        docker-compose exec -T postgres psql -U postgres smart_guide_poc < "$BACKUP_FILE"
        success "Database restored!"
    else
        info "Cancelled."
    fi
}

# ============================================================================
# Command: stats - Show resource usage
# ============================================================================
cmd_stats() {
    info "Resource usage:"
    docker stats --no-stream smartguide-app smartguide-postgres
}

# ============================================================================
# Command: help - Show help
# ============================================================================
cmd_help() {
    cat << EOF
Smart Guide POC - Docker Helper Commands

USAGE:
  ./docker-commands.sh <command> [args]

COMMANDS:
  setup           First-time setup (create .env.docker)
  build           Build Docker images
  up              Start all services
  down            Stop all services
  restart         Restart services
  logs [service]  View logs (default: app)
  clean           Remove containers and volumes
  test            Test the application
  shell           Access application container shell
  db-shell        Access PostgreSQL shell
  backup          Backup database
  restore <file>  Restore database from backup
  stats           Show resource usage
  help            Show this help

EXAMPLES:
  ./docker-commands.sh setup
  ./docker-commands.sh build
  ./docker-commands.sh up
  ./docker-commands.sh logs app
  ./docker-commands.sh backup
  ./docker-commands.sh restore backup-20231220-120000.sql

For more information, see README.md
EOF
}

# ============================================================================
# Main command dispatcher
# ============================================================================
case "${1:-help}" in
    setup)
        cmd_setup "$@"
        ;;
    build)
        cmd_build "$@"
        ;;
    up|start)
        cmd_up "$@"
        ;;
    down|stop)
        cmd_down "$@"
        ;;
    restart)
        cmd_restart "$@"
        ;;
    logs)
        cmd_logs "$@"
        ;;
    clean)
        cmd_clean "$@"
        ;;
    test)
        cmd_test "$@"
        ;;
    shell|sh)
        cmd_shell "$@"
        ;;
    db-shell|psql)
        cmd_db_shell "$@"
        ;;
    backup)
        cmd_backup "$@"
        ;;
    restore)
        cmd_restore "$@"
        ;;
    stats)
        cmd_stats "$@"
        ;;
    help|--help|-h)
        cmd_help
        ;;
    *)
        error "Unknown command: $1"
        echo ""
        cmd_help
        exit 1
        ;;
esac
