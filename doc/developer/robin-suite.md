# Robin Full Suite

Complete email infrastructure suite bundling Robin MTA with Dovecot, PostgreSQL, ClamAV, Rspamd, and Roundcube.

## Purpose

This suite provides a **production-ready email infrastructure** that can be used for:
- Production deployments (with proper security hardening)
- Integration testing and validation
- Development and staging environments
- Feature demonstrations

## Quick Start

```bash
# Start the complete suite
docker-compose -f docker-compose.suite.yaml up -d

# Verify all services are healthy
docker-compose -f docker-compose.suite.yaml ps

# Access webmail
open http://localhost:8888
```

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

| Service | Container | Ports | Purpose |
|---------|-----------|-------|---------|
| Robin MTA | `robin-suite-robin` | 2525, 2587, 2465, 28080, 28090 | SMTP server with APIs |
| Dovecot | `robin-suite-dovecot` | 2143, 2993, 2110, 224 | IMAP/LMTP mail delivery |
| PostgreSQL | `robin-suite-postgres` | 5433 | User auth & relay queue |
| ClamAV | `robin-suite-clamav` | 3310 | Virus scanning |
| Rspamd | `robin-suite-rspamd` | 11333, 11334 | Spam/phishing detection |
| Roundcube | `robin-suite-roundcube` | 8888 | Webmail interface |

## Directory Structure

All suite-specific files are organized under `.suite/`:

```
.suite/
├── cfg/                  # Robin MTA configuration
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
docker-compose -f docker-compose.suite.yaml up -d

# Stop
docker-compose -f docker-compose.suite.yaml down

# Stop and remove all data
docker-compose -f docker-compose.suite.yaml down -v
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
docker logs -f robin-suite-robin
```

**Other service logs** (via docker):
```bash
docker logs -f robin-suite-dovecot
docker logs -f robin-suite-postgres
docker logs -f robin-suite-clamav
docker logs -f robin-suite-rspamd
docker logs -f robin-suite-roundcube
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

For production use:

1. **Update passwords** - Change all default passwords in `.suite/db-init/01-create-users.sql`
2. **Enable authentication** - Configure `authType` and `authValue` in `.suite/cfg/server.json5` for API endpoints
3. **Configure TLS certificates** - Replace self-signed certificates with production ones
4. **Harden Dovecot** - Set `ssl = required` in `.dovecot/etc/conf.d/10-ssl.conf`
5. **Configure domains** - Update hostname and domains in configurations
6. **Disable chaos headers** - Set `chaosHeaders: false` in `.suite/cfg/server.json5`
7. **Disable XCLIENT** - Set `xclientEnabled: false` in `.suite/cfg/server.json5`
8. **Review RBL settings** - Configure appropriate RBL providers

## Testing

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

### Scalability
- Each component scales independently
- Network protocols (LMTP, SQL) work across containers
- No shared filesystem dependencies

### Security
- Multi-layer scanning (virus + spam)
- SQL-backed authentication
- TLS/STARTTLS support
- Queue persistence for reliability
