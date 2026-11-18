#!/bin/sh
set -eu

chown postgres:postgres /var/log
chmod 0750 /var/log
if [ -L /var/log/postgresql.log ] || { [ -e /var/log/postgresql.log ] && [ ! -f /var/log/postgresql.log ]; }; then
    echo "ERROR: PostgreSQL active log must be a regular file, not a link or special file" >&2
    exit 1
fi
if [ -f /var/log/postgresql.log ]; then
    chown postgres:postgres /var/log/postgresql.log
fi

exec /usr/local/bin/docker-entrypoint.sh "$@"
