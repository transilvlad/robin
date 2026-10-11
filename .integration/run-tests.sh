#!/bin/bash
# Run tests only against the isolated integration network.
# Usage: docker compose --project-directory .integration --profile test run --rm integration-tests

set -euo pipefail

SRC=/src
WORK=/work
REPORTS="/reports/$(date -u +%Y%m%dT%H%M%SZ)-$$"
WAIT_SECONDS=60

# Cases expect localhost endpoints; recreate them inside the test container.
# Format: local_port:target_host:target_port
FORWARDS=(
    "2525:mta-backend:25"           # SMTP
    "28090:mta-backend:8090"        # Robin API
    "2143:imap-backend:143"         # IMAP
)

echo "Copying sources to $WORK..."
mkdir -p "$WORK"
tar -C "$SRC" --exclude=./target --exclude=./log --exclude=./store --exclude=./.git \
    --exclude=./.suite --exclude=./.perf --exclude=./.integration/reports \
    -cf - . | tar -C "$WORK" -xf -

echo "Integration client configuration: $WORK/.integration/client-cfg"
echo "Remote MTA configuration: .integration/cfg (mounted at /integration-server-cfg)"
test -f /integration-server-cfg/server.json5
test -f "$WORK/.integration/client-cfg/client.json5"

for forward in "${FORWARDS[@]}"; do
    IFS=: read -r local_port host port <<< "$forward"

    waited=0
    until nc -z "$host" "$port" 2>/dev/null; do
        if [ "$waited" -ge "$WAIT_SECONDS" ]; then
            echo "ERROR: integration service $host:$port not reachable after ${WAIT_SECONDS}s" >&2
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
    -Dgroups=suite \
    -Dtest.excludedGroups= \
    -DfailIfNoTests=true \
    -Drobin.integration.clientConfig=.integration/client-cfg \
    -Drobin.integration.jdbcUrl=jdbc:postgresql://postgres-backend:5432/robin \
    "$@" || status=$?

if [ -d target/surefire-reports ]; then
    mkdir -p "$REPORTS"
    cp -r target/surefire-reports/. "$REPORTS/"
    echo "Test reports copied to .integration${REPORTS}/"
fi

exit "$status"
