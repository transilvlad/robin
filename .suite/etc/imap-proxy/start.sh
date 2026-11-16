#!/bin/sh
set -eu
umask 077

file=/run/robin-api-auth-nginx.conf
: > "$file"
if [ -n "${ROBIN_API_AUTH:-}" ]; then
    case "$ROBIN_API_AUTH" in
        :*|*:) echo "ERROR: ROBIN_API_AUTH requires username:password" >&2; exit 1 ;;
        *:*) ;;
        *) echo "ERROR: ROBIN_API_AUTH requires username:password" >&2; exit 1 ;;
    esac
    encoded="$(printf '%s' "$ROBIN_API_AUTH" | base64 | tr -d '\n')"
    [ -n "$encoded" ]
    printf 'auth_http_header Authorization "Basic %s";\n' "$encoded" > "$file"
fi

exec nginx -g 'daemon off;'
