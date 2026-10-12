# Full Suite Integration Testing

Detailed testing procedures for validating Robin MTA against an isolated full-stack
integration environment. These tests do not run against the production suite.

> **See also:** [Robin Full Suite](../developer/robin-suite.md) for deployment and architecture overview.

## Suite Organization

Integration infrastructure is organized under `.integration/`. Unit/component
fixtures remain in `src/test/resources/cfg/`; development uses `cfg/`, performance
workloads use `.perf/`, and production-oriented deployment uses `.suite/`.

```
.integration/
├── cfg/                  # Robin MTA configuration
│   ├── server.json5      # Server settings
│   ├── dovecot.json5     # Dovecot LMTP/SQL integration
│   ├── clamav.json5      # ClamAV settings
│   ├── rspamd.json5      # Rspamd settings
│   └── log4j2.xml        # Logging config (suite.log)
├── client-cfg/           # JUnit/client configuration, not the remote server
├── docker-compose.yaml   # Isolated services, no host ports
├── docker-compose.host.yaml # Optional loopback ports for host/manual tests
├── run-tests.sh          # Container test entrypoint
├── tests.Dockerfile      # Maven 3.9.9 / Corretto 21
├── etc/                  # Test-specific application configurations
│   ├── dovecot/          # Dovecot config (mounted)
│   └── rspamd/           # Rspamd test overrides
└── db-init/              # Database init scripts
    └── 01-create-users.sql

# Generated output:
.integration/reports/     # Surefire reports (ignored by Git)
```

Databases, mailboxes, spool files, logs, certificates, and the Maven cache use
Compose project-scoped named volumes. There are no fixed container or network
names, no production `log/` or `store/` mounts, and no Admin/installer services.
The default project is `robin-integration`; use `-p` consistently for independent
test environments. Test users and certificates must never be used in production.

## Test Environment Setup

### 0. First-Time Setup

Docker with Linux containers is required. No `.suite/.env` or production
initialization script is needed. Dovecot's self-signed certificate is generated
inside an integration-only volume; Robin uses its existing test keystore.

### 1. Start the Suite

```bash
# From the repository root
docker compose --project-directory .integration up -d --build --wait
```

**Wait for services to be healthy:**
- ClamAV: 2-3 minutes (downloads virus definitions)
- PostgreSQL: 10-15 seconds (initializes users)
- Rspamd: 20-30 seconds (loads maps)
- Dovecot: 15-20 seconds (connects to PostgreSQL)
- Robin: 10-15 seconds (after Dovecot is ready)

### 2. Verify Health

```bash
# All containers healthy
docker compose --project-directory .integration ps

# Test users created
docker compose --project-directory .integration exec integration-postgres psql -U robin -d robin -c "SELECT email FROM users;"

# Dovecot worker status (not a delivery or IMAP readiness check)
docker compose --project-directory .integration exec integration-dovecot doveadm who

# Service logs
docker compose --project-directory .integration logs integration-robin
```

## Running Tests

### JUnit Integration Tests

Integration tests (tagged `integration`) are excluded from normal builds and run in the
`integration-tests` container, which pins Maven 3.9.9 and Corretto 21. It starts
its isolated dependencies if needed, waits for them to be healthy, and exits with
Maven's exit code.

```bash
# From the repository root: run all integration tests
docker compose --project-directory .integration --profile test run --build --rm integration-tests

# Extra arguments are passed to Maven, e.g. a single test
docker compose --project-directory .integration --profile test run --rm integration-tests -Dtest='IntegrationTest#test01_basicSmtp'
```

Surefire reports are copied to a fresh `.integration/reports/<run-id>/` directory
per run, so older results cannot masquerade as current reports. The runner recreates
`2525`, `28090`, and `2143` inside its container with forwards to the
integration services; no host port publishing is needed. It explicitly loads
`.integration/client-cfg`, while the remote MTA mounts `.integration/cfg`
read-only with database configuration synchronization disabled.
The default `suite` tag selects the ten delivery/scanning/chaos cases and the
PostgreSQL config-store integration tests, not unrelated future integration suites.
That test requires an explicit `robin.integration.jdbcUrl`; the runner supplies
the private integration PostgreSQL address, with no production endpoint default.

To run from the host, explicitly opt into loopback ports (stop conflicting
services first):

```bash
docker compose -f .integration/docker-compose.yaml -f .integration/docker-compose.host.yaml up -d --build --wait
mvn -B test -Dgroups=suite -Dtest.excludedGroups= -Drobin.integration.clientConfig=.integration/client-cfg -Drobin.integration.jdbcUrl=jdbc:postgresql://localhost:15434/robin
```

For a clean fixture database and mailboxes, remove only this project's volumes.
Never run these commands against the production suite:

```bash
docker compose --project-directory .integration --profile test down -v
```

### Individual Test Cases

Any test case can be run directly:

```bash
# Build Robin first
mvn clean package

# Run individual test case
java -jar target/robin.jar --client --conf .integration/client-cfg/ --gson src/test/resources/cases/config/integration/07-spam-gtube.json5
```

## Test Case Details

The ten JSON5 cases live in `src/test/resources/cases/config/integration/`.
The existing assertions remain the source of truth.

| File | Coverage |
|------|----------|
| `01-basic-smtp.json5` | SMTP acceptance |
| `02-delivery-success.json5` | Delivery through scanners and LMTP, verified by IMAP |
| `03-delivery-multiple-recipients.json5` | Delivery to both valid recipients |
| `04-user-not-found.json5` | SQL-backed recipient rejection |
| `05-invalid-sender.json5` | Invalid MAIL FROM rejection |
| `06-partial-delivery-mixed.json5` | Valid delivery alongside invalid recipient rejection |
| `07-spam-gtube.json5` | GTUBE rejection with 541 and spam log evidence |
| `08-virus-eicar.json5` | EICAR rejection by ClamAV |
| `09-chaos-lda-failure.json5` | Injected delivery failure accepted with 250 and queued for retry |
| `10-chaos-storage-failure.json5` | Injected storage failure rejected with 451 |

`ConfigStoreSyncIntegrationTest` additionally checks rejected empty payloads,
per-server configuration merges, and persistence of secret references rather
than resolved credentials using temporary tables in integration PostgreSQL.

## Assertion Patterns

### Common Protocol Assertions

```json5
// Connection established
["SMTP", "^220"]

// EHLO/HELO accepted
["EHLO", "^250"]

// Sender accepted
["MAIL", "^250.*Sender OK"]

// Recipient accepted
["RCPT", "^250.*Recipient OK"]

// Data accepted
["DATA", "^250.*Received OK"]

// Rejection patterns
["RCPT", "^(550|554)"]  // User not found
["MAIL", "^(501|553)"]  // Invalid format
["DATA", "^554.*[Vv]irus"]  // Virus detected

// Connection closed
["QUIT", "^221"]
```

### IMAP Verification

```json5
{
  type: "imap",
  wait: 5,        // Initial wait before first attempt (seconds)
  retry: 10,      // Number of retry attempts
  delay: 3,       // Delay between retries (seconds)
  host: "127.0.0.1",
  port: 2143,
  user: "recipient@example.com",
  pass: "password",
  folder: "INBOX",
  delete: true,   // Delete verified message after successful assertion
  // purge: true, // Alternative: delete ALL messages in folder (takes precedence)
  matches: {
    // Message header patterns (regex)
    headers: [
      ["subject", "regex or exact match"],
      ["from", "sender@example.com"],
      ["to", "recipient@example.com"]
    ],
    // MIME part patterns (optional)
    parts: [
      {
        // Filter to specific part by Content-Type header (optional)
        headers: [
          ["content-type", "text/plain"]
        ],
        // Assert against decoded body content (regex)
        body: [
          ["expected body content"],
          ["another pattern"]
        ],
        // Assert against content hashes (optional, Base64-encoded)
        hashes: {
          sha256: "expected-sha256-hash",
          md5: "expected-md5-hash"
        }
      }
    ]
  }
}
```

See `doc/lib/imap.md` for detailed documentation on IMAP assertion options.

## Clean Testing Environment

### Reset Integration Data

```bash
docker compose --project-directory .integration --profile test down -v
docker compose --project-directory .integration up -d --build --wait
```

This resets the integration database, mailboxes, spool, logs, certificates, and
Maven cache together. It does not remove production data or local reports.

## Manual Testing

Use the optional `docker-compose.host.yaml` overlay described above; the
default integration stack does not publish host ports.

### Send Test Email via SMTP

```bash
(echo "EHLO test.example.com"
 sleep 0.5
 echo "MAIL FROM:<tony@example.com>"
 sleep 0.5
 echo "RCPT TO:<pepper@example.com>"
 sleep 0.5
 echo "DATA"
 sleep 0.5
 cat src/test/resources/cases/sources/lipsum.eml
 echo "."
 sleep 0.5
 echo "QUIT") | nc localhost 2525
```

### Verify via IMAP

```bash
# Login and fetch emails
(echo "a1 LOGIN pepper@example.com potts"
 sleep 1
 echo "a2 SELECT INBOX"
 sleep 1
 echo "a3 FETCH 1:* (BODY[HEADER.FIELDS (SUBJECT FROM)])"
 sleep 1
 echo "a4 LOGOUT") | nc localhost 2143
```

Roundcube is not part of the integration stack; use the IMAP client assertions
or the manual connection above.

## Coverage Boundaries

These tests exercise the delivery, scanner, failure-injection, and config-store
paths listed above. They are not production acceptance tests: passing does not
prove restart recovery, durable retry completion, security hardening, full RFC
compliance, or high availability.

## Test-Specific Configuration

The integration MTA uses test-specific settings in `.integration/cfg/`.
The production suite no longer contains or invokes the integration test runner.

- **chaosHeaders: true** - Enables failure injection testing
- **xclientEnabled: true** - Allows XCLIENT extension testing
- **rbl.enabled: false** - Disables RBL checks for faster testing
- **ClamAV/Rspamd enabled** - Full scanning in test environment

## Troubleshooting

IMAP or SMTP timeouts are failures to investigate, not expected successful runs.
Check `integration-robin` and `integration-dovecot` logs first. Case log assertions
use the isolated Robin HTTP API, not production log files.

## Related Documentation

- **[Robin Suite Deployment](../developer/robin-suite.md)** - Architecture and configuration
- **[SMTP Test Cases](case-smtp.md)** - General SMTP testing
- **[HTTP Test Cases](case-http.md)** - HTTP/API testing
- **[Queue Features](../features/queue.md)** - Queue management
- **[Dovecot Integration](../lib/dovecot-sasl.md)** - SASL and LMTP details
