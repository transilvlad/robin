#!/bin/bash
# Unified performance test runner for all Robin/Postfix/Stalwart test suites.
# Auto-detects backend (Dovecot/Stalwart) and adjusts behavior accordingly.

set -e

COMPOSE_FILE=${COMPOSE_FILE:-docker-compose.robin.yaml}
JMETER_TEST="../.shared/performance-test.jmx"
RESULTS_DIR="./results"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
THREADS=${THREADS:-200}
LOOPS=${LOOPS:-1}

show_usage() {
    cat <<EOF
Usage: $0 [-t threads] [-l loops] [--full]
  -t threads   Number of concurrent threads (default: ${THREADS})
  -l loops     Emails per thread (default: ${LOOPS})
  --full       Shortcut for 200 threads x 50 loops (10,000 emails)

Environment overrides: THREADS, LOOPS, COMPOSE_FILE
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -t|--threads) THREADS="$2"; shift 2 ;;
        -l|--loops) LOOPS="$2"; shift 2 ;;
        --full) THREADS=200; LOOPS=50; shift ;;
        -h|--help) show_usage; exit 0 ;;
        *) echo "Unknown option: $1"; show_usage; exit 1 ;;
    esac
done

TOTAL_EMAILS=$((THREADS * LOOPS))

# Colors for output.
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color.

echo_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

echo_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

echo_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

echo_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

# Auto-detect backend and test name from directory and compose file.
CURRENT_DIR="$(basename $(pwd))"

# Check directory name first for more accurate detection.
if [[ "$CURRENT_DIR" == "robin-stalwart" ]]; then
    BACKEND="stalwart"
    TEST_NAME="robin"
    CONTAINER_NAME="stalwart"
    IMAP_PORT=2143
    USE_IMAP=true
elif [[ "$CURRENT_DIR" == "stalwart-bare" ]]; then
    BACKEND="stalwart"
    TEST_NAME="stalwart-bare"
    CONTAINER_NAME="perf-stalwart"
    IMAP_PORT=2143
    USE_IMAP=true
elif [[ "$COMPOSE_FILE" == *"stalwart"* ]]; then
    BACKEND="stalwart"
    TEST_NAME="stalwart"
    CONTAINER_NAME="perf-stalwart"
    IMAP_PORT=2143
    USE_IMAP=true
elif [[ "$COMPOSE_FILE" == *"postfix"* ]]; then
    BACKEND="dovecot"
    TEST_NAME="postfix"
    CONTAINER_NAME="perf-dovecot"
    USE_IMAP=false
else
    BACKEND="dovecot"
    TEST_NAME="robin"
    CONTAINER_NAME="perf-dovecot"
    USE_IMAP=false
fi

# Check if JMeter is installed.
if ! command -v jmeter &> /dev/null; then
    echo_error "JMeter is not installed. Please install it first:"
    echo "  macOS:  brew install jmeter"
    echo "  Linux:  sudo apt-get install jmeter"
    echo "  Or download from: https://jmeter.apache.org/download_jmeter.cgi"
    exit 1
fi

echo_info "JMeter version: $(jmeter --version 2>&1 | head -1)"
echo_info "Compose file: ${COMPOSE_FILE}"
echo_info "Backend: ${BACKEND}"
echo

# Create results directory.
mkdir -p "$RESULTS_DIR"

# Check if containers are already running.
if docker-compose -f "$COMPOSE_FILE" ps | grep -q "Up"; then
    echo_warning "Containers are already running. Using existing setup."
else
    echo_info "Starting Docker containers..."
    docker-compose -f "$COMPOSE_FILE" up -d

    echo_info "Waiting for containers to be healthy (30 seconds)..."
    sleep 30

    # Special rate limit handling for stalwart-bare.
    if [[ "$TEST_NAME" == "stalwart-bare" ]]; then
        echo_info "Disabling Stalwart rate limiting via CLI..."
        docker exec perf-stalwart stalwart-cli -u http://localhost:8080 -c admin:admin123 \
            server add-config queue.limiter.inbound.ip.enable false 2>/dev/null || true
        docker exec perf-stalwart stalwart-cli -u http://localhost:8080 -c admin:admin123 \
            server add-config queue.limiter.inbound.sender.enable false 2>/dev/null || true
        docker exec perf-stalwart stalwart-cli -u http://localhost:8080 -c admin:admin123 \
            server reload-config 2>/dev/null || true
        echo_info "Rate limiting disabled"
    fi
fi

# Verify containers are healthy.
echo_info "Checking container health..."
docker-compose -f "$COMPOSE_FILE" ps

# Clear any existing emails from previous tests.
echo_info "Cleaning previous test data from ${BACKEND}..."
if [[ "$USE_IMAP" == true ]]; then
    python3 ../.scripts/imap-tool.py \
        --host 127.0.0.1 \
        --port ${IMAP_PORT} \
        --user pepper@example.com \
        --pass potts \
        --folder INBOX \
        --delete-all 2>/dev/null || true
else
    docker exec ${CONTAINER_NAME} doveadm expunge -u pepper@example.com mailbox INBOX all 2>/dev/null || true
fi

# Run JMeter test.
echo_info "Starting JMeter performance test..."
echo_info "  Threads: ${THREADS}"
echo_info "  Emails per thread: ${LOOPS}"
echo_info "  Total emails: ${TOTAL_EMAILS}"
echo_info "  Results: ${RESULTS_DIR}/${TEST_NAME}-${TIMESTAMP}"
echo_info "  JMeter log: ${RESULTS_DIR}/jmeter-${TIMESTAMP}.log"
echo

RESULTS_FILE="${RESULTS_DIR}/${TEST_NAME}-${TIMESTAMP}.jtl"
REPORT_DIR="${RESULTS_DIR}/${TEST_NAME}-${TIMESTAMP}-report"
JMETER_LOG="${RESULTS_DIR}/jmeter-${TIMESTAMP}.log"

jmeter -n -t "$JMETER_TEST" \
  -Jthreads="${THREADS}" \
  -Jloops="${LOOPS}" \
  -l "$RESULTS_FILE" \
  -j "$JMETER_LOG" \
  -e -o "$REPORT_DIR"
JMETER_EXIT_CODE=$?

echo
echo

if [ $JMETER_EXIT_CODE -eq 0 ]; then
    echo_success "JMeter test completed successfully"
else
    echo_error "JMeter test failed with exit code: $JMETER_EXIT_CODE"
    exit $JMETER_EXIT_CODE
fi

# Count delivered emails.
echo_info "Verifying email delivery..."
if [[ "$USE_IMAP" == true ]]; then
    # Use IMAP for Stalwart
    EMAIL_COUNT=$(python3 ../.scripts/imap-tool.py \
        --host 127.0.0.1 \
        --port ${IMAP_PORT} \
        --user pepper@example.com \
        --pass potts \
        --folder INBOX 2>/dev/null | grep "Message count:" | awk '{print $3}')

    echo_info "Emails delivered to ${BACKEND}: ${EMAIL_COUNT}"

    if [ "$EMAIL_COUNT" -eq "${TOTAL_EMAILS}" ] 2>/dev/null; then
        echo_success "All ${TOTAL_EMAILS} emails delivered successfully!"
    else
        echo_warning "Email count mismatch: expected ${TOTAL_EMAILS}, got ${EMAIL_COUNT}"
    fi
else
    # For Dovecot: verify from JMeter results (Dovecot container too minimal for file counting)
    SUCCESS_COUNT=$(grep ",true," "$RESULTS_FILE" 2>/dev/null | wc -l)
    echo_info "JMeter successful deliveries: ${SUCCESS_COUNT}/${TOTAL_EMAILS}"

    if [ "$SUCCESS_COUNT" -eq "${TOTAL_EMAILS}" ] 2>/dev/null; then
        echo_success "All ${TOTAL_EMAILS} emails accepted by MTA!"
    else
        echo_warning "Delivery count mismatch: expected ${TOTAL_EMAILS}, got ${SUCCESS_COUNT}"
    fi
fi

echo
echo_info "📊 Test Results Summary"
echo_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo_info "  Backend:         ${BACKEND}"
echo_info "  Compose File:    ${COMPOSE_FILE}"
echo_info "  Total Emails:    ${TOTAL_EMAILS}"
if [[ "$USE_IMAP" == true ]]; then
    echo_info "  Emails Delivered: ${EMAIL_COUNT}"
else
    echo_info "  MTA Accepted:    ${SUCCESS_COUNT}"
fi
echo_info "  HTML Report:     ${REPORT_DIR}/index.html"
echo_info "  JMeter Log:      ${JMETER_LOG}"
echo_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo

# Ask if user wants to stop containers.
read -p "Stop containers? [y/N] " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo_info "Stopping containers..."
    docker-compose -f "$COMPOSE_FILE" down
    echo_success "Containers stopped"
else
    echo_info "Containers still running. Use 'docker-compose -f ${COMPOSE_FILE} down' to stop them."
fi
