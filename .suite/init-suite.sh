#!/bin/bash
# Initialize directory structure and file permissions for Robin full suite.
# Run after cloning: ./.suite/init-suite.sh --demo|--production

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODE="${1:-}"
if [ "$#" -ne 1 ] || { [ "$MODE" != "--demo" ] && [ "$MODE" != "--production" ]; }; then
    echo "Usage: $0 --demo|--production" >&2
    echo "Existing deployments must use the explicit migration/preflight procedure, not initialization." >&2
    exit 2
fi
if [ "$MODE" = "--production" ]; then
    postgres="$SCRIPT_DIR/../store/postgres"
    if [ -e "$postgres" ] || [ -L "$postgres" ]; then
        if [ ! -d "$postgres" ]; then
            echo "ERROR: Existing PostgreSQL path is not a directory." >&2
            exit 1
        fi
        first_entry="$(find -H "$postgres" -mindepth 1 -maxdepth 1 -print -quit)" || {
            echo "ERROR: Cannot inspect existing PostgreSQL state." >&2
            exit 1
        }
        if [ -n "$first_entry" ]; then
            echo "ERROR: PostgreSQL state already exists. Initialization is not a production migration." >&2
            exit 1
        fi
    fi
fi

echo "Initializing .suite infrastructure..."
echo ""

# Create log directories
echo "Creating log directories..."
mkdir -p "$SCRIPT_DIR/../log/"{postgres,clamav,rspamd,robin,dovecot,cyrus,imap-proxy,roundcube}

# Create store directories
echo "Creating store directories..."
mkdir -p "$SCRIPT_DIR/../store/"{postgres,clamav,robin,dovecot}

# Generate a self-signed IMAP TLS certificate for the demo baseline, used by
# Dovecot or by the Cyrus backend's IMAP proxy.
# Production should mount operator-issued material via docker-compose.production.yaml.
CERT_DIR="$SCRIPT_DIR/etc/dovecot/certs"
if [ "$MODE" = "--demo" ] && { [ ! -f "$CERT_DIR/imap.crt" ] || [ ! -f "$CERT_DIR/imap.key" ]; }; then
    echo "Generating self-signed IMAP certificate..."
    mkdir -p "$CERT_DIR"
    openssl req -x509 -newkey rsa:2048 -days 3650 -nodes \
      -subj "/CN=imap-backend" \
      -keyout "$CERT_DIR/imap.key" -out "$CERT_DIR/imap.crt" 2>/dev/null
    chmod 600 "$CERT_DIR/imap.key"
fi

echo ""
echo "Setting file permissions..."
chown 5000:5000 "$SCRIPT_DIR/../store/dovecot"
chmod 750 "$SCRIPT_DIR/../store/dovecot"
echo "  - Dovecot mailbox root owned by 5000:5000"

echo ""
echo "✓ Initialization complete"
echo ""
echo "Directory structure created:"
tree -L 1 "$SCRIPT_DIR/../log" "$SCRIPT_DIR/../store" 2>/dev/null || (ls -la "$SCRIPT_DIR/../log/" && ls -la "$SCRIPT_DIR/../store/")

echo ""
echo "You can now run the full suite:"
echo "  Configure .suite/.env and run init-secrets.sh first."
echo "  docker compose --project-directory .suite up -d --build"
echo "  For production, also set MAIL_HOSTNAME and MAIL_TLS_DIR and include docker-compose.production.yaml."
echo "  Demo listeners default to loopback; explicitly choose MAIL_BIND only after reviewing exposure."
echo ""
echo "Or run the integration tests:"
echo "  docker compose --project-directory .integration --profile test run --rm integration-tests"
