#!/bin/sh
# Starts Dovecot over the existing store with the temporary migration master user.
set -eu

password=
IFS= read -r password < /run/secrets/migration_master_password || [ -n "$password" ]
if [ "${#password}" -lt 32 ]; then
    echo "ERROR: migration master password must contain at least 32 characters" >&2
    exit 1
fi

umask 022
printf 'migrator:{PLAIN}%s\n' "$password" > /run/migration/master-users
unset password

exec sh /usr/local/bin/suite-dovecot-entrypoint.sh -c /etc/dovecot-migration/dovecot.conf
