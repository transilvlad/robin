#!/bin/bash
# Initialize directory structure and file permissions for Robin full suite.
# Run this script after cloning the repository: ./.suite/init-suite.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

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
if [ ! -f "$CERT_DIR/imap.crt" ] || [ ! -f "$CERT_DIR/imap.key" ]; then
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
echo ""
echo "Or run the integration tests:"
echo "  docker compose --project-directory .integration --profile test run --rm integration-tests"
