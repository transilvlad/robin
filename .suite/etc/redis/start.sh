#!/bin/sh
set -eu

for file in /run/secrets/redis_auth.conf /run/secrets/redis_auth.acl /etc/redis/suite-production.conf; do
    if [ ! -s "$file" ]; then
        echo "ERROR: Required Redis configuration is missing or empty: $file" >&2
        exit 1
    fi
done

# Starting AOF mode over an RDB-only dataset can silently start an empty database.
if [ ! -s /data/appendonlydir/appendonly.aof.manifest ]; then
    existing="$(find /data -mindepth 1 -maxdepth 1 -print -quit)"
    if [ -n "$existing" ]; then
        echo "ERROR: Existing Redis state requires verified AOF conversion before production startup; preserve the data and follow suite-operations.md" >&2
        exit 1
    fi
fi

if [ ! -f /data/redis.conf ]; then
    printf 'loglevel warning\nprotected-mode yes\n' > /data/redis.conf
fi
exec redis-server /data/redis.conf \
    --include /run/secrets/redis_auth.conf \
    --include /etc/redis/suite-production.conf
