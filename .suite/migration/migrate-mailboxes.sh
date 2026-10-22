#!/bin/bash
# Copies mailboxes from the Dovecot backend's store into Cyrus IMAP with imapsync.
#
# Usage: .suite/migration/migrate-mailboxes.sh [user@domain ...]
#
# Without arguments every user in Robin's users table is copied. Run it with
# suite-cyrus up and suite-dovecot stopped. imapsync is incremental, so the
# script can be re-run safely to pick up anything missed.
# Extra imapsync options can be passed in IMAPSYNC_OPTIONS (e.g. --dry) and an
# extra compose file in MIGRATION_COMPOSE_OVERRIDE.
set -euo pipefail

MIGRATION_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUITE_DIR="$(dirname "$MIGRATION_DIR")"
SECRET="$SUITE_DIR/secrets/migration_master_password"

compose=(docker compose -f "$MIGRATION_DIR/compose.yaml")
# Optional extra compose file, e.g. to point at a mail store in another location.
if [ -n "${MIGRATION_COMPOSE_OVERRIDE:-}" ]; then
    compose+=(-f "$MIGRATION_COMPOSE_OVERRIDE")
fi
if [ -f "$SUITE_DIR/.env" ]; then
    compose+=(--env-file "$SUITE_DIR/.env")
fi

running() {
    [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" = true ]
}

if running suite-dovecot; then
    echo "ERROR: stop suite-dovecot first; the store must not be in use by the live backend." >&2
    exit 1
fi
if ! running suite-cyrus || ! running suite-postgres; then
    echo "ERROR: suite-cyrus and suite-postgres must be running." >&2
    exit 1
fi
if [ ! -s "$SUITE_DIR/secrets/cyrus_admin_password" ]; then
    echo "ERROR: missing Cyrus admin password; run .suite/init-secrets.sh." >&2
    exit 1
fi

if [ ! -s "$SECRET" ]; then
    (umask 077; printf '%s' "$(openssl rand -hex 32)" > "$SECRET")
fi

if [ "$#" -gt 0 ]; then
    users=("$@")
else
    mapfile -t users < <(docker exec suite-postgres psql -U postgres -d robin -Atc "SELECT email FROM users ORDER BY email")
fi
if [ "${#users[@]}" -eq 0 ]; then
    echo "No users to migrate."
    exit 0
fi

cleanup() {
    "${compose[@]}" down --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

"${compose[@]}" up -d --wait migration-dovecot

failed=()
for user in "${users[@]}"; do
    echo "==> $user"
    # shellcheck disable=SC2086
    if ! "${compose[@]}" run --rm --no-deps --quiet-pull imapsync \
        --host1 migration-dovecot --port1 993 --ssl1 \
        --user1 "$user" --authuser1 migrator --authmech1 PLAIN \
        --passfile1 /run/secrets/migration_master_password \
        --host2 suite-cyrus --port2 143 --notls2 \
        --user2 "$user" --authuser2 cyrus --authmech2 PLAIN \
        --passfile2 /run/secrets/cyrus_admin_password \
        --automap --nolog --noreleasecheck ${IMAPSYNC_OPTIONS:-}; then
        failed+=("$user")
    fi
done

if [ "${#failed[@]}" -gt 0 ]; then
    echo "Failed: ${failed[*]}" >&2
    exit 1
fi
echo "Migrated ${#users[@]} mailbox(es)."
