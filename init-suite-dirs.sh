#!/bin/bash
# Initialize directory structure for Robin Full Suite
# Run this script before starting docker-compose.suite.yaml

set -e

echo "Creating log directories..."
mkdir -p log/{postgres,clamav,rspamd,robin,dovecot,roundcube}

echo "Creating store directories..."
mkdir -p store/{postgres,clamav,rspamd,robin,dovecot}

echo "Directory structure created successfully:"
tree -L 2 log store 2>/dev/null || (ls -la log/ && ls -la store/)

echo ""
echo "You can now run: docker-compose -f docker-compose.suite.yaml up -d"
