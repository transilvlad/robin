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
mkdir -p "$SCRIPT_DIR/../store/"{postgres,clamav,rspamd,robin,dovecot}

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
