#!/bin/bash
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$SCRIPT_DIR/secrets"
for name in postgres_password mta_db_password mailbox_db_password roundcube_db_password roundcube_des_key; do
    file="$SCRIPT_DIR/secrets/$name"
    if [ ! -e "$file" ]; then
        if [ "$name" = roundcube_des_key ]; then
            openssl rand -hex 12 > "$file"
        else
            openssl rand -hex 32 > "$file"
        fi
    elif [ ! -s "$file" ]; then
        echo "ERROR: existing secret file is empty: $file" >&2
        exit 1
    fi
    chmod 600 "$file"
done
echo "Database and webmail encryption secret files are ready; existing values were not rotated."
echo "Set DB_PASSWORD and MONITOR_DB_PASSWORD independently in .suite/.env."
