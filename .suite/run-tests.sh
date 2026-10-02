#!/bin/bash
# Run Robin integration tests inside the suite network with a pinned Maven/JDK.
# Entrypoint of the suite-tests container (see .suite/tests.Dockerfile).
# Usage: docker compose --profile test run --rm suite-tests [extra Maven args]

set -euo pipefail

SRC=/src
WORK=/work
REPORTS=/src-log/suite-tests
WAIT_SECONDS=60

# Tests expect the suite on the host's published ports; recreate them locally.
# Format: local_port:target_host:target_port
FORWARDS=(
    "2525:mta-backend:25"           # SMTP
    "28090:mta-backend:8090"        # Robin API
    "2143:imap-backend:143"         # IMAP
    "5434:postgres-backend:5432"    # PostgreSQL
)

echo "Copying sources to $WORK..."
mkdir -p "$WORK"
tar -C "$SRC" --exclude=./target --exclude=./log --exclude=./store --exclude=./.git -cf - . | tar -C "$WORK" -xf -

for forward in "${FORWARDS[@]}"; do
    IFS=: read -r local_port host port <<< "$forward"

    waited=0
    until nc -z "$host" "$port" 2>/dev/null; do
        if [ "$waited" -ge "$WAIT_SECONDS" ]; then
            echo "ERROR: suite service $host:$port not reachable after ${WAIT_SECONDS}s" >&2
            exit 1
        fi
        sleep 1
        waited=$((waited + 1))
    done

    socat "TCP-LISTEN:$local_port,fork,reuseaddr" "TCP:$host:$port" &
    echo "Forwarding 127.0.0.1:$local_port -> $host:$port"
done

cd "$WORK"
status=0
mvn -B test \
    -Dgroups=integration \
    -Dtest.excludedGroups= \
    -Dsurefire.failIfNoSpecifiedTests=false \
    "$@" || status=$?

if [ -d target/surefire-reports ]; then
    rm -rf "$REPORTS"
    mkdir -p "$REPORTS"
    cp -r target/surefire-reports/. "$REPORTS/"
    echo "Test reports copied to log/suite-tests/"
fi

exit "$status"
