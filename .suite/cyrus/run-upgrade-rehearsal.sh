#!/usr/bin/env bash
# Exclusive Docker slot required. Only synthetic/new volumes; never live suite.
set -euo pipefail
umask 077
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
suffix="${BASHPID}"
legacy_project="robin-cyrus-legacy-$suffix"
candidate_project="robin-cyrus-candidate-$suffix"
evidence="$(mktemp -d -t robin-cyrus-rehearsal-XXXXXXXX)"
export CYRUS_LEGACY_IMAGE="${CYRUS_LEGACY_IMAGE:?Set the inspected immutable legacy image ID}"
export JMAP_NGINX_IMAGE="${JMAP_NGINX_IMAGE:?Set a reviewed local nginx image ID}"
export CYRUS_EVIDENCE_DIR="$evidence/evidence"
export CYRUS_ADMIN_SECRET_FILE="$evidence/admin-secret"
export CYRUS_SNAPSHOT_DIR="$evidence/snapshot"
export JMAP_TLS_DIR="$evidence/tls"
export JMAP_SERVER_NAME=jmap-rehearsal.test
export CYRUS_AUTH_NETWORK="${legacy_project}_rehearsal"
export CYRUS_PROBE_IMAGE=robin-suite-cyrus:3.12.4-jmap
export CYRUS_JMAP_IMAGE=robin-suite-cyrus:3.12.4-jmap
legacy_volume="robin-cyrus-legacy-$suffix"
clone_volume="robin-cyrus-rehearsal-$suffix"
rollback_volume="robin-cyrus-rollback-$suffix"
export CYRUS_LEGACY_VOLUME="$legacy_volume"
export CYRUS_REHEARSAL_VOLUME="$clone_volume"
legacy=(docker compose -p "$legacy_project" -f "$root/.suite/cyrus/rehearsal-legacy.yaml")
candidate=(docker compose -p "$candidate_project" -f "$root/.suite/cyrus/rehearsal.yaml")
upgrade() {
    # Re-anchor after Docker Desktop bind-mount operations: a WSL cwd handle
    # can become stale even though the same absolute path is accessible again.
    cd "$root"
    python3 "$root/.suite/cyrus/upgrade.py" "$@"
}
cleanup() {
    cd "$root"
    "${candidate[@]}" logs --no-color > "$evidence/candidate.log" 2>&1 || true
    "${legacy[@]}" logs --no-color > "$evidence/legacy.log" 2>&1 || true
    local cyrus_container
    cyrus_container="$("${candidate[@]}" ps --all --quiet rehearsal-cyrus 2>/dev/null)" || true
    if [ -n "$cyrus_container" ]; then
        docker cp "$cyrus_container:/var/log/cyrus.log" "$evidence/cyrus.log" >/dev/null 2>&1 || true
    fi
    if [ -n "${auth_container:-}" ]; then
        docker network disconnect "${candidate_project}_rehearsal" "$auth_container" >/dev/null 2>&1 || true
    fi
    "${candidate[@]}" down --timeout 120 >/dev/null 2>&1 || true
    "${legacy[@]}" down --timeout 120 >/dev/null 2>&1 || true
    echo "Evidence retained: $evidence"
    echo "Owned volumes retained for review: $legacy_volume $clone_volume $rollback_volume"
    echo 'No live suite container or original volume was touched.'
}
trap cleanup EXIT
mkdir -p "$CYRUS_EVIDENCE_DIR" "$JMAP_TLS_DIR"
openssl rand -hex 32 > "$CYRUS_ADMIN_SECRET_FILE"
openssl req -x509 -newkey rsa:2048 -nodes -days 3 -keyout "$JMAP_TLS_DIR/privkey.pem" \
    -out "$JMAP_TLS_DIR/fullchain.pem" -subj /CN=jmap-rehearsal.test \
    -addext subjectAltName=DNS:jmap-rehearsal.test >/dev/null 2>&1
docker image inspect "$CYRUS_LEGACY_IMAGE" "$JMAP_NGINX_IMAGE" "$CYRUS_PROBE_IMAGE" >/dev/null
if [[ ! "$CYRUS_LEGACY_IMAGE" =~ ^sha256:[a-f0-9]{64}$ ]]; then
    echo 'Use an immutable inspected legacy image ID, not a mutable tag' >&2
    exit 1
fi
legacy_version="$(docker run --rm --network none --memory 64m --entrypoint dpkg-query \
    "$CYRUS_LEGACY_IMAGE" -W '-f=${Version}' cyrus-imapd)"
if [[ "$legacy_version" != 3.6.1-* ]]; then
    echo 'This synthetic baseline requires the inspected Debian Cyrus 3.6.1 image' >&2
    exit 1
fi
if docker volume inspect "$legacy_volume" >/dev/null 2>&1; then
    echo 'Refusing an existing legacy fixture volume' >&2
    exit 1
fi
docker volume create --label org.robin.cyrus.rehearsal=synthetic-fixture "$legacy_volume" >/dev/null
"${legacy[@]}" up -d --wait --wait-timeout 120
auth_container="$("${legacy[@]}" ps --quiet auth)"
legacy_container="$("${legacy[@]}" ps --quiet legacy)"
docker exec "$auth_container" python3 /fixture/rehearsal-mail.py --host legacy --seed --output /evidence/before.json
"${legacy[@]}" stop --timeout 120 legacy
upgrade snapshot --container "$legacy_container" --destination "$CYRUS_SNAPSHOT_DIR"
upgrade restore --snapshot "$CYRUS_SNAPSHOT_DIR" --volume "$clone_volume"
for version in 3.8.8 3.10.4 3.12.4; do
    export CYRUS_JMAP_IMAGE="robin-suite-cyrus:$version-jmap"
    upgrade stage --snapshot "$CYRUS_SNAPSHOT_DIR" --volume "$clone_volume" --image "$CYRUS_JMAP_IMAGE"
    "${candidate[@]}" up -d --wait --wait-timeout 150
    candidate_container="$("${candidate[@]}" ps --quiet rehearsal-cyrus)"
    upgrade verify-stage --snapshot "$CYRUS_SNAPSHOT_DIR" --volume "$clone_volume" --container "$candidate_container"
    docker exec "$auth_container" python3 /fixture/rehearsal-mail.py --host rehearsal-cyrus --baseline /evidence/before.json
    if [ "$version" = 3.12.4 ]; then
        docker network connect "${candidate_project}_rehearsal" "$auth_container"
        printf '%s\n' '{"username":"alice@example.test","password":"synthetic-only-password"}' > "$CYRUS_EVIDENCE_DIR/probe-credentials.json"
        docker exec "$auth_container" python3 /fixture/probe.py --base-url https://jmap-rehearsal.test:8443 \
            --ca-file /tls/fullchain.pem --credentials-file /evidence/probe-credentials.json \
            --wrong-name-url https://rehearsal-proxy:8443 --proxy-bounds
        docker network disconnect "${candidate_project}_rehearsal" "$auth_container"
        "${candidate[@]}" restart --timeout 120
        "${candidate[@]}" up -d --wait --wait-timeout 150
        docker exec "$auth_container" python3 /fixture/rehearsal-mail.py --host rehearsal-cyrus --baseline /evidence/before.json
    fi
    "${candidate[@]}" down --timeout 120
    upgrade compare --snapshot "$CYRUS_SNAPSHOT_DIR" --volume "$clone_volume"
done
upgrade restore --snapshot "$CYRUS_SNAPSHOT_DIR" --volume "$rollback_volume"
export CYRUS_LEGACY_VOLUME="$rollback_volume"
"${legacy[@]}" up -d --wait --wait-timeout 120 legacy
docker exec "$auth_container" python3 /fixture/rehearsal-mail.py --host legacy --baseline /evidence/before.json
"${legacy[@]}" stop --timeout 120 legacy
upgrade compare --snapshot "$CYRUS_SNAPSHOT_DIR" --volume "$rollback_volume"
echo 'PASS staged synthetic clone upgrade, protected TLS reads, restart and old-image rollback'
