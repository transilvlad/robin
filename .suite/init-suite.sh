#!/bin/bash
# Initialize directory structure and file permissions for Robin full suite.
# Run this script after cloning the repository: ./.suite/init-suite.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Initializing .suite infrastructure..."
echo ""

# Create log directories
echo "Creating log directories..."
mkdir -p "$SCRIPT_DIR/../log/"{postgres,clamav,rspamd,robin,dovecot,roundcube}

# Create store directories
echo "Creating store directories..."
mkdir -p "$SCRIPT_DIR/../store/"{postgres,clamav,robin,dovecot}

# Generate self-signed Dovecot TLS certificate (git-ignored, required by 10-ssl.conf)
CERT_DIR="$SCRIPT_DIR/etc/dovecot/certs"
if [ ! -f "$CERT_DIR/imap.crt" ] || [ ! -f "$CERT_DIR/imap.key" ]; then
    echo "Generating self-signed Dovecot certificate..."
    mkdir -p "$CERT_DIR"
    openssl req -x509 -newkey rsa:2048 -days 3650 -nodes \
      -subj "/CN=imap-backend" \
      -keyout "$CERT_DIR/imap.key" -out "$CERT_DIR/imap.crt" 2>/dev/null
    chmod 600 "$CERT_DIR/imap.key"
fi

echo ""
echo "Setting file permissions..."
echo "  - No special permissions required for suite"

echo ""
echo "✓ Initialization complete"
echo ""
echo "Directory structure created:"
tree -L 1 "$SCRIPT_DIR/../log" "$SCRIPT_DIR/../store" 2>/dev/null || (ls -la "$SCRIPT_DIR/../log/" && ls -la "$SCRIPT_DIR/../store/")

echo ""
echo "You can now run the full suite:"
echo "  cd .suite && docker-compose up -d"
echo ""
echo "Or run individual tests:"
echo "  mvn test -Dtest=SuiteIntegrationTest"
