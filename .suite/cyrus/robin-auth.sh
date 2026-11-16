#!/bin/sh
# PAM helper: verifies a mailbox login against Robin's users API.
# Invoked by pam_exec with the password on stdin and the login in PAM_USER.
# Only canonical users are accepted; aliases are resolved by the IMAP proxy.
set -eu

header="${ROBIN_API_AUTH_HEADER_FILE:-/run/robin-api-auth.header}"
if [ "${1:-}" = "--init" ]; then
    umask 077
    : > "$header"
    if [ -n "${ROBIN_API_AUTH:-}" ]; then
        case "$ROBIN_API_AUTH" in
            :*|*:) echo "ERROR: ROBIN_API_AUTH requires username:password" >&2; exit 1 ;;
            *:*) ;;
            *) echo "ERROR: ROBIN_API_AUTH requires username:password" >&2; exit 1 ;;
        esac
        encoded="$(printf '%s' "$ROBIN_API_AUTH" | base64 | tr -d '\n')"
        [ -n "$encoded" ]
        printf 'Authorization: Basic %s\n' "$encoded" > "$header"
    fi
    exit 0
fi

url="${1:?usage: robin-auth <users-authenticate-url>}"
password="$(tr -d '\000')"

[ -n "${PAM_USER:-}" ] && [ -n "$password" ] || exit 1

set -- -fsS --max-time 10 -H 'Content-Type: application/json'
if [ -s "$header" ]; then
    set -- "$@" --header "@$header"
fi
result="$(printf '%s' "$password" | jq -Rsc --arg u "$PAM_USER" '{username: $u, password: .}' \
    | curl "$@" --data-binary @- "$url")" || exit 1

[ "$(printf '%s' "$result" | jq -r '.authenticated')" = "true" ]
