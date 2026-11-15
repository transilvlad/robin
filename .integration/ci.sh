#!/usr/bin/env bash
# Runs the integration suite the way CI does: fresh stack, reports and service logs kept, stack removed.
#
# Usage: .integration/ci.sh
#   ROBIN_IMAGE=<image>  Test this prebuilt Robin image instead of building one from the working tree.
#
# Surefire reports land in .integration/reports/<timestamp>/ and service logs in .integration/reports/service-logs/.

set -uo pipefail
cd "$(dirname "$0")/.."

compose=(docker compose --project-directory .integration --profile test)
logs=.integration/reports/service-logs

build=(--build)
image_id=""
if [ -n "${ROBIN_IMAGE:-}" ]; then
    image_id=$(docker image inspect --format '{{.Id}}' "$ROBIN_IMAGE") || { echo "ERROR: image $ROBIN_IMAGE not found locally" >&2; exit 1; }
    echo "Testing prebuilt Robin image: $ROBIN_IMAGE ($image_id)"
    compose=(docker compose --project-directory .integration
             -f .integration/docker-compose.yaml -f .integration/docker-compose.prebuilt.yaml --profile test)
    "${compose[@]}" build integration-tests || exit 1
    build=()
fi

# Start from nothing so state from an earlier run cannot leak in.
"${compose[@]}" down -v --remove-orphans > /dev/null 2>&1

status=0
"${compose[@]}" run "${build[@]}" --rm integration-tests || status=$?

mkdir -p "$logs"
for service in $("${compose[@]}" ps -a --services); do
    "${compose[@]}" logs --no-color "$service" > "$logs/$service.log" 2>&1
done
"${compose[@]}" exec -T integration-robin sh -c 'cat /var/log/robin/*.log' > "$logs/robin-files.log" 2>&1 || true
"${compose[@]}" exec -T integration-sink sh -c 'cat /var/log/robin/*.log' > "$logs/sink-files.log" 2>&1 || true

"${compose[@]}" down -v --remove-orphans

# The tested image must be the one that was handed in, byte for byte.
if [ -n "$image_id" ] && [ "$(docker image inspect --format '{{.Id}}' "$ROBIN_IMAGE")" != "$image_id" ]; then
    echo "ERROR: $ROBIN_IMAGE changed during the run; the suite did not test the requested image" >&2
    status=1
fi

if [ "$status" -ne 0 ]; then
    echo "Integration suite failed (exit $status); see .integration/reports/" >&2
fi
exit "$status"
