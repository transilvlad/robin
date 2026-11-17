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

case "${MAIL_TLS_PROFILE:-demo}" in
    demo) certificate=/run/mail-tls/imap.crt; key=/run/mail-tls/imap.key ;;
    production) certificate=/run/mail-tls/fullchain.pem; key=/run/mail-tls/privkey.pem ;;
    *) echo "ERROR: Unknown MAIL_TLS_PROFILE" >&2; exit 1 ;;
esac
if [ ! -r "$certificate" ] || [ ! -r "$key" ]; then
    echo "ERROR: Mail TLS certificate/key pair is unreadable" >&2
    exit 1
fi
printf 'ssl_certificate %s;\nssl_certificate_key %s;\n' "$certificate" "$key" \
    > /run/robin-mail-tls-nginx.conf

exec nginx -g 'daemon off;'
