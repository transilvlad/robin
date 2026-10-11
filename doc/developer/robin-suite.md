# Robin Full Suite

Complete email infrastructure suite bundling Robin MTA with Dovecot, PostgreSQL, Redis, ClamAV, Rspamd, Roundcube, and Robin Admin.

## Purpose

This suite provides a **development and demonstration email infrastructure** that can be used for:
- Integration testing and validation
- Development and staging environments
- Feature demonstrations

The Compose configuration is not production-ready as-is. It includes sample
mail accounts and other demonstration defaults. Robin Admin is bound to
`127.0.0.1` by default; keep it there or put a trusted reverse proxy/firewall
in front before exposing it. The co-located Robin Admin agent runs as root and
mounts the Docker socket, which grants host-level control. Robin Admin's module
installer also mounts the Docker socket; it is disabled by default and should
only be enabled on a trusted host.

## Quick Start

```bash
# First run: copy the settings; replace the demo secrets before use
cp .suite/.env.example .suite/.env

# Start the complete suite
docker compose --project-directory .suite up -d

# Verify all services are healthy
docker compose --project-directory .suite ps

# Access webmail
open http://localhost:8888

# Access Robin Admin
open https://localhost:3443
```

The Robin Admin installer is optional. On a trusted host, enable it explicitly:

```bash
docker compose --project-directory .suite --profile installer up -d
```

Set `DB_PASSWORD` in `.suite/.env` to a unique password for shared deployments;
the Compose configuration passes it to the suite PostgreSQL server, Robin,
Dovecot, Roundcube, and Robin Admin. Do not use the example's `robin` value
outside local demonstrations. PostgreSQL only applies `POSTGRES_PASSWORD` when
initializing an empty data directory; for an existing `store/postgres`, change
the database role password to match before restarting the suite.

Use `--project-directory .suite` (or run `docker compose` from inside `.suite/`)
rather than `-f .suite/docker-compose.yaml`: passing `-f` skips
`docker-compose.override.yaml`, which points Robin Admin's mounts at this suite.
The included Robin Admin Compose files and images share `ROBIN_ADMIN_VERSION`;
update that value in `.suite/.env` when moving to another published version.
The gateway's default host address and port are `127.0.0.1:3443`; if changing
the bind address for a reverse proxy, set `ADMIN_HTTPS_BIND` (for example,
`0.0.0.0`) only when network access is appropriately restricted.

## Architecture

```
                        ┌─────────────────┐
                        │   PostgreSQL    │
                        │   (Auth + Queue)│
                        └────────┬────────┘
                                 │
                     ┌───────────┴───────────┐
                     │                       │
                     ▼                       ▼
┌──────────────┐  ┌─────────────────┐  ┌─────────────────┐
│ SMTP Client  │─▶│   Robin MTA     │  │    Dovecot      │
│              │  │  SMTP Server    │  │  IMAP / LMTP    │
└──────────────┘  └────────┬────────┘  └────────▲────────┘
   SMTP:2525               │                    │
                           │                    │ LMTP:24
                ┌──────────┼────────┐           │
                │          │        │           │
                ▼          ▼        ▼           │
         ┌──────────┐ ┌────────┐ ┌──────────────┴─┐
         │  ClamAV  │ │ Rspamd │ │ DovecotStorage │
         │  :3310   │ │ :11333 │ │   Processor    │
         └──────────┘ └────────┘ └────────────────┘
              │            │
              └─────┬──────┘
                    │
                    ▼
           ┌─────────────────┐
           │  Email Accepted │
           │   or Rejected   │
           └─────────────────┘

┌──────────────┐  IMAP:2143   ┌─────────────────┐
│ Email Client │◀─────────────│    Dovecot      │
│              │              │                 │
└──────────────┘              └────────▲────────┘
                                       │
┌──────────────┐  HTTP:8888            │
│   Browser    │─────────────▶┌────────┴───────┐
│              │              │   Roundcube    │
└──────────────┘              └────────────────┘
```

## Services

Every service is named `suite-<component>` (container and Compose service) and
is reached on the `suite` network by its role alias. Other applications that
join the suite, such as Robin Admin, use the role aliases.

| Service | Container | Role alias | Host ports | Purpose |
|---------|-----------|------------|------------|---------|
| Robin MTA | `suite-robin` | `mta-backend` | 2525, 2587, 2465, 28080, 28090 | SMTP server with APIs |
| Dovecot | `suite-dovecot` | `imap-backend`, `lmtp-backend` | 2143, 2993, 2110, 224 | IMAP/LMTP mail delivery |
| PostgreSQL | `suite-postgres` | `postgres-backend` | 5434 | User auth & relay queue |
| Redis | `suite-redis` | `redis-backend` | none | Rspamd statistics, history and module state |
| ClamAV | `suite-clamav` | `antivirus-backend` | 3310 | Virus scanning |
| Rspamd | `suite-rspamd` | `spam-backend` | 11333, 11334 | Spam/phishing detection |
| Roundcube | `suite-roundcube` | `webmail-backend` | 8888 | Webmail interface |
| Robin Admin | `suite-admin-gateway`, `suite-admin`, `suite-admin-ui`, `suite-admin-agent` | `admin-gateway`, `admin-backend`, `admin-frontend` | 3443 | Web UI for the suite |

## Directory Structure

All suite-specific files are organized under `.suite/`:

```
.suite/
├── cfg/                  # Robin MTA configuration
│   ├── config.json5      # Optional PostgreSQL config store sync
│   ├── server.json5      # Server settings
│   ├── dovecot.json5     # Dovecot LMTP/SQL integration
│   ├── clamav.json5      # ClamAV virus scanning
│   ├── rspamd.json5      # Rspamd spam detection
│   ├── storage.json5     # Email storage
│   ├── queue.json5       # Queue settings
│   ├── relay.json5       # PostgreSQL queue backend
│   ├── properties.json5  # System properties
│   ├── client.json5      # Client defaults
│   └── log4j2.xml        # Logging (suite.log)
├── etc/                  # Application configurations
│   ├── rspamd/local.d/   # Rspamd overrides (Redis, DKIM signing, logging)
│   └── dovecot/          # Dovecot configuration files
│       ├── conf.d/       # Dovecot config includes
│       ├── dovecot.conf  # Main Dovecot config
│       └── dovecot-sql.conf.ext  # SQL auth config
└── db-init/              # Database initialization scripts
    ├── 01-create-users.sql
    └── 02-create-roundcube-db.sql

# Shared with main project:
log/                      # All suite logs (suite.log, suite-YYYYMMDD.log)
store/                    # All persistent data
├── postgres/             # PostgreSQL database
├── clamav/               # ClamAV virus definitions
├── rspamd/               # Rspamd learning data
├── roundcube/            # Roundcube sessions
├── tmp/                  # Robin temporary storage
└── queue/                # Robin relay queue storage
```

## Default Users

| Email | Password | Purpose |
|-------|----------|---------|
| tony@example.com | stark | Test sender/recipient |
| pepper@example.com | potts | Test recipient |
| happy@example.com | hogan | Test recipient |

## Common Operations

### Start/Stop Suite
```bash
# Start
docker compose --project-directory .suite up -d

# Stop
docker compose --project-directory .suite down

# Stop and remove all data
docker compose --project-directory .suite down -v
```

### Monitor Logs

**Robin MTA logs** (mounted to host):
```bash
# Current suite log
tail -f log/suite.log

# Previous days
tail -f log/suite-20251209.log

# Via API
curl "http://localhost:28090/logs?q=error"

# Via docker
docker logs -f suite-robin
```

**Other service logs** (via docker):
```bash
docker logs -f suite-dovecot
docker logs -f suite-postgres
docker logs -f suite-redis
docker logs -f suite-clamav
docker logs -f suite-rspamd
docker logs -f suite-roundcube
```

### Queue Management
```bash
# List queue
curl http://localhost:28090/client/queue/list

# Clear queue (deletes all items)
curl -s http://localhost:28090/client/queue/list | \
  grep -o 'data-uid="[^"]*"' | \
  sed 's/data-uid="//;s/"$//' | \
  while read uid; do
    curl -s -X POST "http://localhost:28090/client/queue/delete?uid=$uid"
  done
```

### Access Webmail
```
URL: http://localhost:8888
User: pepper@example.com
Pass: potts
```

## Production Deployment

Before any shared or production deployment:

1. **Update passwords** - Change all default passwords in `.suite/db-init/01-create-users.sql`
2. **Replace demo secrets** - Use unique strong values for `DB_PASSWORD`, Admin secrets, agent credentials and module proxy secrets in `.suite/.env`
3. **Protect Robin Admin** - Keep the gateway on loopback behind a trusted reverse proxy/firewall; the co-located agent runs as root with Docker socket access
4. **Keep the installer disabled** unless required, and only enable it on a trusted host; it also has Docker socket access
5. **Enable authentication** - Configure `authType` and `authValue` in `.suite/cfg/server.json5` for API endpoints
6. **Configure TLS certificates** - Replace self-signed certificates with production ones
7. **Harden Dovecot** - Set `ssl = required` in `.dovecot/etc/conf.d/10-ssl.conf`
8. **Configure domains** - Update hostname and domains in configurations
9. **Disable chaos headers** - Set `chaosHeaders: false` in `.suite/cfg/server.json5`
10. **Disable XCLIENT** - Set `xclientEnabled: false` in `.suite/cfg/server.json5`
11. **Review RBL settings** - Configure appropriate RBL providers

## Testing

Integration tests run in a separate `.integration/` stack with their own
configuration, sample accounts, certificates, networks, and project-scoped data.
Do not run fault-injection tests against this suite:

```bash
docker compose --project-directory .integration --profile test run --build --rm integration-tests
```

For comprehensive testing documentation, see:
- **[Full Suite Testing Guide](../testing/full-suite.md)** - Detailed test cases and validation
- **[SMTP Test Cases](../testing/case-smtp.md)** - SMTP test case examples
- **[HTTP Test Cases](../testing/case-http.md)** - HTTP/API test case examples

## Related Documentation

- **[Getting Started](../user/getting-started.md)** - Robin basics
- **[Server Configuration](../user/server.md)** - Server setup
- **[Dovecot Integration](../lib/dovecot-sasl.md)** - Dovecot SASL
- **[Queue Management](../features/queue.md)** - Relay queue
- **[ClamAV](../features/clamav.md)** - Virus scanning
- **[Rspamd](../features/rspamd.md)** - Spam detection
- **[Endpoints](../features/endpoints.md)** - HTTP APIs

## Architecture Benefits

### Separation of Concerns
- **Robin MTA** - SMTP protocol and routing
- **Dovecot** - Mailbox storage and IMAP/POP3
- **PostgreSQL** - Shared state (auth + queue)
- **ClamAV/Rspamd** - Independent security layers
- **Redis** - Rspamd state

### Scalability
- Each component scales independently
- Network protocols (LMTP, SQL) work across containers
- No shared filesystem dependencies

### Security
- Multi-layer scanning (virus + spam)
- SQL-backed authentication
- TLS/STARTTLS support
- Queue persistence for reliability
