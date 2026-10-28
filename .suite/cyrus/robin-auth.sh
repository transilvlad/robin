#!/bin/sh
# PAM helper: verifies a mailbox login against Robin's users API.
# Invoked by pam_exec with the password on stdin and the login in PAM_USER.
# Only canonical users are accepted; aliases are resolved by the IMAP proxy.
set -u

url="${1:?usage: robin-auth <users-authenticate-url>}"
password="$(tr -d '\000')"

[ -n "${PAM_USER:-}" ] && [ -n "$password" ] || exit 1

result="$(printf '%s' "$password" | jq -Rsc --arg u "$PAM_USER" '{username: $u, password: .}' \
    | curl -fsS --max-time 10 -H 'Content-Type: application/json' --data-binary @- "$url")" || exit 1

[ "$(printf '%s' "$result" | jq -r '.authenticated')" = "true" ]
