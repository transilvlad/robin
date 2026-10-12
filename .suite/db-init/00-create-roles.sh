#!/bin/bash
(
set -euo pipefail

# Credentials stay out of SQL files and psql command-line arguments.
export MTA_DB_PASSWORD="$(cat /run/secrets/mta_db_password)"
export MAILBOX_DB_PASSWORD="$(cat /run/secrets/mailbox_db_password)"
export ROUNDCUBE_DB_PASSWORD="$(cat /run/secrets/roundcube_db_password)"
: "${DB_PASSWORD:?Set the Robin Admin database password}"
: "${MONITOR_DB_PASSWORD:?Set the PostgreSQL monitoring password}"
for name in POSTGRES_PASSWORD MTA_DB_PASSWORD MAILBOX_DB_PASSWORD ROUNDCUBE_DB_PASSWORD DB_PASSWORD MONITOR_DB_PASSWORD; do
    value="${!name}"
    if [ "${#value}" -lt 32 ]; then
        echo "ERROR: $name must contain at least 32 characters" >&2
        exit 1
    fi
done

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv mta_password MTA_DB_PASSWORD
\getenv mailbox_password MAILBOX_DB_PASSWORD
\getenv roundcube_password ROUNDCUBE_DB_PASSWORD
\getenv admin_password DB_PASSWORD
\getenv monitor_password MONITOR_DB_PASSWORD
CREATE ROLE robin_mta LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'mta_password';
CREATE ROLE robin_mailbox LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'mailbox_password';
CREATE ROLE robin_roundcube LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'roundcube_password';
CREATE ROLE robin_admin LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'admin_password';
CREATE ROLE robin_monitor LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'monitor_password';
GRANT pg_monitor TO robin_monitor;
REVOKE ALL ON DATABASE robin FROM PUBLIC;
GRANT CONNECT ON DATABASE robin TO robin_mta, robin_mailbox, robin_admin, robin_monitor;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
-- Robin and Admin still bootstrap/migrate their own tables at startup.
GRANT USAGE, CREATE ON SCHEMA public TO robin_mta, robin_admin;
GRANT USAGE ON SCHEMA public TO robin_mailbox;
ALTER DEFAULT PRIVILEGES FOR ROLE robin_mta IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO robin_admin;
ALTER DEFAULT PRIVILEGES FOR ROLE robin_mta IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO robin_admin;
SET ROLE robin_mta;
CREATE TABLE robin_config_store (
    server_id VARCHAR(64) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    payload_json JSONB NOT NULL,
    updated_epoch BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    PRIMARY KEY (server_id, file_name)
);
RESET ROLE;
SQL
)
