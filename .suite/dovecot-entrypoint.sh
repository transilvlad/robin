#!/bin/sh
set -eu

POSTMASTER_ADDRESS="${POSTMASTER_ADDRESS-postmaster@localhost}"
invalid_postmaster() {
    echo "ERROR: POSTMASTER_ADDRESS must be an unquoted ASCII mailbox address" >&2
    exit 1
}
case "$POSTMASTER_ADDRESS" in
    ?*@?*) ;;
    *) invalid_postmaster ;;
esac
case "$POSTMASTER_ADDRESS" in
    *[!A-Za-z0-9._%+@-]*|*@*@*|*..*|.*@*|*.@*) invalid_postmaster ;;
esac
case "${POSTMASTER_ADDRESS#*@}" in
    [!A-Za-z0-9]*|*[!A-Za-z0-9.-]*|*[!A-Za-z0-9]|*.-*|*-.*) invalid_postmaster ;;
esac
export POSTMASTER_ADDRESS

MAILBOX_DB_PASSWORD=
IFS= read -r MAILBOX_DB_PASSWORD < /run/secrets/mailbox_db_password || [ -n "$MAILBOX_DB_PASSWORD" ]
if [ "${#MAILBOX_DB_PASSWORD}" -lt 32 ]; then
    echo "ERROR: mailbox database password must contain at least 32 characters" >&2
    exit 1
fi
export MAILBOX_DB_PASSWORD
exec /dovecot/sbin/dovecot -F "$@"
