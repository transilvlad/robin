#!/bin/sh
set -eu

MAILBOX_DB_PASSWORD=
IFS= read -r MAILBOX_DB_PASSWORD < /run/secrets/mailbox_db_password || [ -n "$MAILBOX_DB_PASSWORD" ]
if [ "${#MAILBOX_DB_PASSWORD}" -lt 32 ]; then
    echo "ERROR: mailbox database password must contain at least 32 characters" >&2
    exit 1
fi
export MAILBOX_DB_PASSWORD
exec /dovecot/sbin/dovecot -F "$@"
