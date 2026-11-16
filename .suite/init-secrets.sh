#!/bin/bash
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$SCRIPT_DIR/secrets"
for name in postgres_password mta_db_password mailbox_db_password cyrus_admin_password roundcube_db_password roundcube_des_key rspamd_controller_password rspamd_enable_password redis_password; do
    file="$SCRIPT_DIR/secrets/$name"
    if [ ! -e "$file" ]; then
        if [ "$name" = roundcube_des_key ]; then
            openssl rand -hex 12 | tr -d '\r' > "$file"
        else
            openssl rand -hex 32 | tr -d '\r' > "$file"
        fi
    elif [ ! -s "$file" ]; then
        echo "ERROR: existing secret file is empty: $file" >&2
        exit 1
    fi
    chmod 600 "$file"
done
for name in rspamd_controller_password rspamd_enable_password redis_password; do
    value="$(cat "$SCRIPT_DIR/secrets/$name")"
    if [ "${#value}" -ne 64 ] || [[ "$value" = *[!0-9a-fA-F]* ]]; then
        echo "ERROR: $name must contain exactly 64 hexadecimal characters." >&2
        exit 1
    fi
done

temporary="$(mktemp "$SCRIPT_DIR/secrets/.config.XXXXXX")"
trap 'rm -f "$temporary"' EXIT
ensure_config() {
    local file="$SCRIPT_DIR/secrets/$1"
    shift
    printf "$@" > "$temporary"
    if [ -e "$file" ]; then
        if ! cmp -s "$temporary" "$file"; then
            echo "ERROR: derived secret config differs from its password files: $file" >&2
            echo "For an intentional rotation, remove only that derived file and rerun this script." >&2
            exit 1
        fi
    else
        cp "$temporary" "$file"
    fi
    chmod 600 "$file"
}
ensure_config rspamd_controller.inc 'password = "%s";\nenable_password = "%s";\n' \
    "$(cat "$SCRIPT_DIR/secrets/rspamd_controller_password")" \
    "$(cat "$SCRIPT_DIR/secrets/rspamd_enable_password")"
ensure_config rspamd_redis.inc 'servers = "redis-backend:6379";\npassword = "%s";\n' \
    "$(cat "$SCRIPT_DIR/secrets/redis_password")"
ensure_config redis_auth.conf 'aclfile /run/secrets/redis_auth.acl\n'
ensure_config redis_auth.acl 'user default reset on >%s ~* &* +@all\n' \
    "$(cat "$SCRIPT_DIR/secrets/redis_password")"
echo "Suite secret files are ready; existing values were not rotated."
echo "Set DB_PASSWORD and MONITOR_DB_PASSWORD independently in .suite/.env."
