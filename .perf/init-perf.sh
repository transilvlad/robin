#!/bin/bash
# Initialize directory structure and file permissions for performance testing infrastructure.
# Run this script after cloning the repository: sudo ./.perf/init-perf.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Initializing .perf infrastructure..."
echo ""

# Create log directories
echo "Creating log directories..."
mkdir -p "$SCRIPT_DIR/../log/"{postgres,dovecot,postfix,robin,stalwart}

# Create store directories
echo "Creating store directories..."
mkdir -p "$SCRIPT_DIR/../store/"{postgres,dovecot,robin,stalwart}

echo ""
echo "Setting file permissions..."

# Postfix requires dynamicmaps.cf to be owned by root
if [ -f "$SCRIPT_DIR/.shared/postfix/etc/postfix/dynamicmaps.cf" ]; then
    echo "  - Setting dynamicmaps.cf to root:root 644"
    chown root:root "$SCRIPT_DIR/.shared/postfix/etc/postfix/dynamicmaps.cf"
    chmod 644 "$SCRIPT_DIR/.shared/postfix/etc/postfix/dynamicmaps.cf"
fi

echo ""
echo "✓ Initialization complete"
echo ""
echo "Directory structure created:"
tree -L 1 "$SCRIPT_DIR/../log" "$SCRIPT_DIR/../store" 2>/dev/null || (ls -la "$SCRIPT_DIR/../log/" && ls -la "$SCRIPT_DIR/../store/")

echo ""
echo "You can now run performance tests:"
echo "  cd .perf/robin-dovecot && ../.shared/run-test.sh -t 20 -l 50"
echo "  cd .perf/robin-dovecot-lda && docker-compose up -d"
echo "  cd .perf/robin-stalwart && ../.shared/run-test.sh -t 20 -l 50"
echo "  cd .perf/stalwart-bare && ../.shared/run-test.sh -t 20 -l 50"
