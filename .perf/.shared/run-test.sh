#!/bin/bash
# Unified performance test runner for all Robin/Postfix/Stalwart test suites.
# Auto-detects backend (Dovecot/Stalwart) and adjusts behavior accordingly.

set -e

COMPOSE_FILE=${COMPOSE_FILE:-docker-compose.robin.yaml}
JMETER_TEST="../.shared/performance-test.jmx"
RESULTS_DIR="./results"
BENCHMARK_USERS_CSV="../.shared/benchmark-users.csv"
BENCHMARK_WORKLOAD_CSV="../.shared/benchmark-workload.csv"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
THREADS=${THREADS:-200}
LOOPS=${LOOPS:-1}
QUEUE_DRAIN_TIMEOUT_SECONDS=${QUEUE_DRAIN_TIMEOUT_SECONDS:-300}

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

resolve_compose_container_id() {
    docker-compose -f "$COMPOSE_FILE" ps -q "$1" 2>/dev/null | head -1
}

csv_rows() {
    tail -n +2 "$1" | grep -v '^[[:space:]]*$'
}

sum_dovecot_mailboxes() {
    local container_id="$1"
    local total=0
    local status count

    while IFS=, read -r email _; do
        status="$(docker exec "$container_id" doveadm mailbox status -u "$email" messages INBOX 2>/dev/null || true)"
        count="$(printf '%s\n' "$status" | sed -n 's/.*messages=\([0-9][0-9]*\).*/\1/p' | tail -1)"
        if [[ -z "$count" ]]; then
            count=0
        fi
        total=$((total + count))
    done < <(csv_rows "$BENCHMARK_USERS_CSV")

    echo "$total"
}

sum_imap_mailboxes() {
    local total=0
    local output count

    while IFS=, read -r email password; do
        output="$(python3 ../.scripts/imap-tool.py --host 127.0.0.1 --port "${IMAP_PORT}" --user "$email" --pass "$password" --folder INBOX 2>/dev/null || true)"
        count="$(printf '%s\n' "$output" | sed -n 's/^Message count: \([0-9][0-9]*\)$/\1/p' | tail -1)"
        if [[ -z "$count" ]]; then
            count=0
        fi
        total=$((total + count))
    done < <(csv_rows "$BENCHMARK_USERS_CSV")

    echo "$total"
}

sum_mailboxes() {
    if [[ "$BACKEND" == "stalwart" ]]; then
        sum_imap_mailboxes
        return
    fi

    DOVECOT_CONTAINER_ID="$(resolve_compose_container_id "${DOVECOT_SERVICE}")"
    if [[ -z "$DOVECOT_CONTAINER_ID" ]]; then
        echo_warning "Could not resolve Dovecot container for mailbox counting"
        echo 0
        return
    fi

    sum_dovecot_mailboxes "$DOVECOT_CONTAINER_ID"
}

uses_queued_robin_dovecot() {
    [[ "$CURRENT_DIR" == "robin-dovecot" && "$COMPOSE_FILE" == *"docker-compose.robin.yaml" ]]
}

reset_queued_state() {
    if ! uses_queued_robin_dovecot; then
        return
    fi

    POSTGRES_CONTAINER_ID="$(resolve_compose_container_id "postgres")"
    if [[ -n "$POSTGRES_CONTAINER_ID" ]]; then
        docker exec "$POSTGRES_CONTAINER_ID" psql -U robin -d robin -c "truncate table relay_queue;" >/dev/null 2>&1 || true
        echo_info "Reset queued relay state"
    else
        echo_warning "Could not resolve PostgreSQL container for queue reset"
    fi
}

wait_for_queue_drain() {
    if ! uses_queued_robin_dovecot; then
        return 0
    fi

    echo_info "Waiting for queued LMTP drain..."
    for _ in $(seq 1 "${QUEUE_DRAIN_TIMEOUT_SECONDS}"); do
        QUEUE_SIZE=$(python3 - <<'PY'
import json, urllib.request
try:
    data=json.load(urllib.request.urlopen('http://127.0.0.1:28080/health', timeout=2))
    print(data['queue']['size'])
except Exception:
    print(-1)
PY
)
        if [[ "$QUEUE_SIZE" == "0" ]]; then
            echo_success "Queued relay drained to zero"
            return 0
        fi
        sleep 1
    done

    echo_warning "Queued relay did not drain within ${QUEUE_DRAIN_TIMEOUT_SECONDS}s"
    return 1
}

# Auto-detect backend and test name from directory and compose file.
CURRENT_DIR="$(basename "$(pwd)")"
DOVECOT_SERVICE=""
STALWART_SERVICE=""

# Check directory name first for more accurate detection.
if [[ "$CURRENT_DIR" == "robin-dovecot-lda" ]]; then
    BACKEND="dovecot"
    USE_IMAP=false
    if [[ "$COMPOSE_FILE" == *"postfix"* ]]; then
        TEST_NAME="postfix"
        DOVECOT_SERVICE="postfix-dovecot-lda"
    elif [[ "$COMPOSE_FILE" == *"haraka"* ]]; then
        TEST_NAME="haraka"
        DOVECOT_SERVICE="haraka-dovecot-lda"
    else
        TEST_NAME="robin"
        DOVECOT_SERVICE="robin-lda"
    fi
elif [[ "$CURRENT_DIR" == "robin-stalwart" ]]; then
    BACKEND="stalwart"
    IMAP_PORT=2143
    USE_IMAP=true
    STALWART_SERVICE="stalwart"
    if [[ "$COMPOSE_FILE" == *"postfix"* ]]; then
        TEST_NAME="postfix"
    else
        TEST_NAME="robin"
    fi
elif [[ "$CURRENT_DIR" == "stalwart-bare" ]]; then
    BACKEND="stalwart"
    TEST_NAME="stalwart-bare"
    IMAP_PORT=2143
    USE_IMAP=true
    STALWART_SERVICE="stalwart"
elif [[ "$COMPOSE_FILE" == *"stalwart"* ]]; then
    BACKEND="stalwart"
    TEST_NAME="stalwart"
    IMAP_PORT=2143
    USE_IMAP=true
    STALWART_SERVICE="stalwart"
elif [[ "$COMPOSE_FILE" == *"haraka"* ]]; then
    BACKEND="dovecot"
    TEST_NAME="haraka"
    DOVECOT_SERVICE="dovecot"
    USE_IMAP=false
elif [[ "$COMPOSE_FILE" == *"postfix"* ]]; then
    BACKEND="dovecot"
    TEST_NAME="postfix"
    DOVECOT_SERVICE="dovecot"
    USE_IMAP=false
else
    BACKEND="dovecot"
    TEST_NAME="robin"
    DOVECOT_SERVICE="dovecot"
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

if [[ ! -f "$BENCHMARK_USERS_CSV" || ! -f "$BENCHMARK_WORKLOAD_CSV" ]]; then
    echo_error "Benchmark workload files are missing"
    exit 1
fi

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
        STALWART_CONTAINER_ID="$(resolve_compose_container_id "${STALWART_SERVICE}")"
        echo_info "Disabling Stalwart rate limiting via CLI..."
        if [[ -n "$STALWART_CONTAINER_ID" ]]; then
            docker exec "$STALWART_CONTAINER_ID" stalwart-cli -u http://localhost:8080 -c admin:admin123 \
                server add-config queue.limiter.inbound.ip.enable false 2>/dev/null || true
            docker exec "$STALWART_CONTAINER_ID" stalwart-cli -u http://localhost:8080 -c admin:admin123 \
                server add-config queue.limiter.inbound.sender.enable false 2>/dev/null || true
            docker exec "$STALWART_CONTAINER_ID" stalwart-cli -u http://localhost:8080 -c admin:admin123 \
                server reload-config 2>/dev/null || true
            echo_info "Rate limiting disabled"
        else
            echo_warning "Could not resolve Stalwart container for rate limit configuration"
        fi
    fi
fi

# Verify containers are healthy.
echo_info "Checking container health..."
docker-compose -f "$COMPOSE_FILE" ps

# Keep mailbox state warm across the run series. Only queue state resets between queued Robin runs.
echo_info "Preparing sustained benchmark state for ${BACKEND}..."
reset_queued_state
BASELINE_EMAIL_COUNT=$(sum_mailboxes)
EXPECTED_EMAIL_COUNT=$((BASELINE_EMAIL_COUNT + TOTAL_EMAILS))
echo_info "Benchmark recipient pool baseline: ${BASELINE_EMAIL_COUNT} messages across $(csv_rows "$BENCHMARK_USERS_CSV" | wc -l) mailboxes"

# Run JMeter test.
echo_info "Starting JMeter performance test..."
echo_info "  Threads: ${THREADS}"
echo_info "  Emails per thread: ${LOOPS}"
echo_info "  Total emails: ${TOTAL_EMAILS}"
echo_info "  Workload CSV: ${BENCHMARK_WORKLOAD_CSV}"
echo_info "  Results: ${RESULTS_DIR}/${TEST_NAME}-${TIMESTAMP}"
echo_info "  JMeter log: ${RESULTS_DIR}/jmeter-${TIMESTAMP}.log"
echo

RESULTS_FILE="${RESULTS_DIR}/${TEST_NAME}-${TIMESTAMP}.jtl"
REPORT_DIR="${RESULTS_DIR}/${TEST_NAME}-${TIMESTAMP}-report"
JMETER_LOG="${RESULTS_DIR}/jmeter-${TIMESTAMP}.log"

jmeter -n -t "$JMETER_TEST" \
  -Jthreads="${THREADS}" \
  -Jloops="${LOOPS}" \
  -JworkloadCsv="${BENCHMARK_WORKLOAD_CSV}" \
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
wait_for_queue_drain || true

echo_info "Verifying email delivery..."
EMAIL_COUNT=$(sum_mailboxes)
DELIVERED_DELTA=$((EMAIL_COUNT - BASELINE_EMAIL_COUNT))
SUCCESS_COUNT=$(grep ",true," "$RESULTS_FILE" 2>/dev/null | wc -l)

echo_info "Messages in benchmark recipient pool: ${EMAIL_COUNT}"
echo_info "Messages added this run: ${DELIVERED_DELTA}/${TOTAL_EMAILS}"
echo_info "JMeter successful deliveries: ${SUCCESS_COUNT}/${TOTAL_EMAILS}"

if [ "$EMAIL_COUNT" -eq "${EXPECTED_EMAIL_COUNT}" ] 2>/dev/null; then
    echo_success "All ${TOTAL_EMAILS} emails were delivered to the benchmark recipient pool"
else
    echo_warning "Mailbox count mismatch: expected cumulative ${EXPECTED_EMAIL_COUNT}, got ${EMAIL_COUNT}"
fi

echo
echo_info "📊 Test Results Summary"
echo_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo_info "  Backend:         ${BACKEND}"
echo_info "  Compose File:    ${COMPOSE_FILE}"
echo_info "  Total Emails:    ${TOTAL_EMAILS}"
echo_info "  Pool Baseline:   ${BASELINE_EMAIL_COUNT}"
echo_info "  Pool Delivered:  ${EMAIL_COUNT}"
echo_info "  Delta Delivered: ${DELIVERED_DELTA}"
echo_info "  MTA Accepted:    ${SUCCESS_COUNT}"
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
