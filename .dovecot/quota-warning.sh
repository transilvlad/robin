#!/usr/bin/env sh
# Quota warning script.
# Usage: invoked by Dovecot with level and possibly user context.
LEVEL="$1"
DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# Dovecot passes user via environment USER or USERNAME; capture if present.
USER_VAR="${USER:-${USERNAME:-unknown}}"
LOGFILE="/var/log/quota-warnings.log"
echo "${DATE} level=${LEVEL}% user=${USER_VAR}" >> "$LOGFILE" 2>/dev/null || exit 0
