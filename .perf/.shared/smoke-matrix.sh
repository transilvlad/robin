#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
THREADS="${THREADS:-2}"
LOOPS="${LOOPS:-2}"

configs=(
  "Robin + Dovecot LMTP (queued)|.perf/robin-dovecot|docker-compose.robin.yaml"
  "Robin + Dovecot LMTP (inline)|.perf/robin-dovecot|docker-compose.robin-inline.yaml"
  "Robin + Dovecot LDA|.perf/robin-dovecot-lda|docker-compose.robin.yaml"
  "Postfix + Dovecot LMTP|.perf/robin-dovecot|docker-compose.postfix.yaml"
  "Postfix + Dovecot LDA|.perf/robin-dovecot-lda|docker-compose.postfix.yaml"
  "Haraka + Dovecot LMTP|.perf/robin-dovecot|docker-compose.haraka.yaml"
  "Haraka + Dovecot LDA|.perf/robin-dovecot-lda|docker-compose.haraka.yaml"
  "Robin + Stalwart|.perf/robin-stalwart|docker-compose.robin.yaml"
  "Postfix + Stalwart|.perf/robin-stalwart|docker-compose.postfix.yaml"
  "Stalwart Bare|.perf/stalwart-bare|docker-compose.yaml"
)

for config in "${configs[@]}"; do
  IFS='|' read -r label rel_dir compose <<<"${config}"
  dir="${ROOT}/${rel_dir}"
  printf '\n[SMOKE] %s\n' "${label}"
  (
    cd "${dir}"
    docker compose -f "${compose}" down -v >/dev/null 2>&1 || true
    if grep -q '^[[:space:]]*build:' "${compose}"; then
      docker compose -f "${compose}" build >/dev/null
      docker compose -f "${compose}" up -d >/dev/null
      sleep 35
    fi
    printf 'y\n' | COMPOSE_FILE="${compose}" THREADS="${THREADS}" LOOPS="${LOOPS}" "${SCRIPT_DIR}/run-test.sh" >/tmp/perf-smoke-last.log
    docker compose -f "${compose}" down -v >/dev/null 2>&1 || true
  )
  printf '[PASS] %s\n' "${label}"
done

printf '\nAll smoke tests passed.\n'
