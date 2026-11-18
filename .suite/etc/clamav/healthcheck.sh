#!/bin/sh
set -eu
export LC_ALL=C

maximum="${CLAMAV_MAX_SIGNATURE_AGE_SECONDS:-172800}"
case "$maximum" in
    ''|*[!0-9]*) echo "ERROR: Invalid ClamAV signature-age policy" >&2; exit 1 ;;
esac
if ! [ "$maximum" -gt 0 ] || ! [ "$maximum" -le 604800 ]; then
    echo "ERROR: ClamAV signature-age policy must be between 1 second and 7 days" >&2
    exit 1
fi

version="$(printf 'VERSION\n' | nc -w 5 127.0.0.1 3310)"
case "$version" in
    "ClamAV "*/*/*) built="${version#*/*/}" ;;
    *) echo "ERROR: ClamAV did not report loaded signature metadata" >&2; exit 1 ;;
esac
if ! timestamp="$(date -u -D '%a %b %d %H:%M:%S %Y' -d "$built" +%s)"; then
    echo "ERROR: ClamAV signature timestamp is invalid" >&2
    exit 1
fi
age="$(($(date -u +%s) - timestamp))"
if [ "$age" -lt -3600 ] || [ "$age" -gt "$maximum" ]; then
    echo "ERROR: ClamAV signatures are stale or the clock is inconsistent (age=$age seconds)" >&2
    exit 1
fi
echo "ClamAV responds with current loaded signature metadata"
