# Full Suite Integration Testing

Detailed testing procedures for validating Robin MTA against an isolated full-stack
integration environment. These tests do not run against the production suite.

> **See also:** [Robin Full Suite](../developer/robin-suite.md) for deployment and architecture overview.

## Production configuration acceptance without touching a deployment

The Python checks under `.suite/tests` use a generated standalone Compose root,
not the real suite's includes, `.env`, secrets, store or logs. Each run uses a
random `robin-acceptance-*` project, project-scoped volumes/networks, synthetic
credentials and dynamically allocated **loopback-only** host ports. Fixed suite
names mean that merely adding `-p` to the real suite is not isolation.

```powershell
# Windows local Docker; Linux uses python3 and ROBIN_SUITE_DOCKER_TEST=1.
$env:ROBIN_SUITE_DOCKER_TEST='1'
py -m unittest discover -s .suite\tests -p test_mail_policy.py -v
py -m unittest discover -s .suite\tests -p test_mailbox.py -v
py -m unittest discover -s .suite\tests -p test_preflight.py -v
py -m unittest discover -s .suite\tests -p test_compose.py -v
py -m unittest discover -s .suite\tests -p test_rspamd.py -v
py -m unittest discover -s .suite\tests -p test_cyrus_auth.py -v
```

Pre-pull reviewed `postgres:18-alpine`, `dovecot/dovecot:latest` and `alpine:3.23`
fixture images, or set `ROBIN_TEST_POSTGRES_IMAGE` / `ROBIN_TEST_DOVECOT_IMAGE`
to compatible reviewed image references. The mailbox check was exercised with
Dovecot 2.4.2. These defaults are test conveniences, **not production image pins**.
The scanner fixture additionally uses reviewed cached Redis and Rspamd images
(`ROBIN_TEST_REDIS_IMAGE` / `ROBIN_TEST_RSPAMD_IMAGE`, defaulting to `redis:alpine`
and `rspamd/rspamd:latest`); it refuses pulls. Native OpenSSL and Bash (Git Bash
on Windows) are required. It runs only disposable Redis (128 MiB/0.5 CPU) and
Rspamd (768 MiB/1 CPU), with memory/swap and PID caps. It exercises the normalized
production commands, healthchecks and generated secret includes, not an
unauthenticated Redis substitute.
The fixtures bound Postgres to 256 MiB/1 CPU/PID 128 and Dovecot to 512 MiB/1 CPU/
PID 256, sequentially. Fixture-only `initdb --no-sync` avoids expensive initial
filesystem synchronization; running PostgreSQL keeps its default fsync policy.
This is not a power-loss durability proof.

Identity acceptance covers additive migration, operator-policy preservation,
normalized identity checks, explicit primary grants, multi-target aliases and
revocation on account/address removal or rename. Mailbox acceptance covers
LMTP-to-IMAP delivery, SPECIAL-USE folders, shared-folder read/insert denial/
revocation, exact Admin `doveadm` set/get/delete readback, APPEND quota rejection,
TLS ManageSieve script activation and actual Junk filing, next-login password
revocation, and certificate-directory replacement followed by reload and a
verified served certificate serial change. Twelve simultaneous authenticated
TLS sessions exercise the former ten-login-process ceiling without enabling
shared login processes. The managed-alias SQL check executes the actual Java
query against disposable PostgreSQL and checks automatic multi-target aliases,
explicit-alias precedence and real-mailbox precedence. It does not modify or validate a
running Cyrus/JMAP/webmail deployment.

Compose model checks use synthetic local Admin includes, never a remote fetch
or sibling checkout. They cover both mailbox backends, loopback defaults,
production TLS mounts, Basic credential propagation and missing-input refusals.
They also check core memory/swap/CPU/PID budgets and the production signing
template override; the Cyrus proxy must use one directory TLS mount without
the demo file mounts.
The Ansible environment-template regression also requires Jinja2 and PyYAML
(included with Ansible); it renders the checked-in example into both production
Compose models and verifies the API credential and generated Redis DSN.
Offline initialization checks cover explicit demo/production selection,
existing-database refusal, secret idempotence, invalid credentials and stale
derived includes. The scanner check proves Redis authentication, controller
read/write separation, cryptographically verified authenticated DKIM signing,
unsigned inbound/missing-key cases, retained Redis state and actual password/key
rotation through recreation. DNS key lookup is disabled only in that synthetic
scanner fixture. This is not Robin-to-scanner end-to-end signing, real Admin
reload, public DNS or whole-suite production acceptance.
The Cyrus callback check uses cached `robin-suite-cyrus:local` and `nginx:alpine`
images (or `ROBIN_TEST_CYRUS_AUTH_IMAGE` / `ROBIN_TEST_NGINX_IMAGE`) without pulls.
It mounts only copied current callback/entrypoint/proxy scripts and synthetic
configuration, starts a disposable actual Cyrus/IMAPS pair (256/64 MiB with
matching swap, CPU and PID caps), and tests alias login with authenticated
proxy/PAM callbacks plus wrong-credential refusals. Atomic certificate/key replacement in the mounted
directory followed by validated nginx reload must serve the new serial and
still authenticate successfully; no certificate-verification bypass is used.
The default demo certificate filenames also work without the production pair,
and an unknown TLS profile is refused.
Its protected Robin HTTP callbacks are synthetic: this does not establish a deployed Robin/Cyrus SQL
identity or live-account migration pass.

The offline `test_preflight.py` checks require no Docker opt-in. Production
preflight takes already-captured private Compose JSON, database/runtime-status
evidence, an image lock and Docker inspect JSON; it does not establish public
DNS, reputation or end-to-end delivery. Resource evidence must include each
deployed container's `HostConfig`, not only intended Compose budgets:
`Memory` and `PidsLimit` must be positive integers exactly matching normalized
`mem_limit` bytes and `pids_limit`. Compose's decimal-string byte counts are
accepted, but unnormalized unit strings, booleans and overflow are refused;
CPU must match numeric `cpus` through
`NanoCpus / 1e9` or positive `CpuQuota / CpuPeriod`. Docker's default CFS period
is 100000 microseconds when unspecified. Zero `NanoCpus` alone is not failure
when a matching hard quota is present. Missing/unlimited/invalid limits,
conflicting CPU representations and drift in any deployed replica block
acceptance. Every replica must also use the reviewed image and be running,
unpaused, healthy and not OOM-killed. CPU shares/reservations are not hard limits. These offline
regressions do not prove that host cgroups actually enforce captured settings.

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
│   └── relay.json5       # Outbound MX relay
├── sink-cfg/             # robin-sink: the remote MTA for remote.test
│   └── scenarios.json5   # Scripted tempfail/permfail addresses
├── http-sink/            # HTTP request recorder (http_sink.py)
├── client-cfg/           # JUnit/client configuration, not the remote server
├── docker-compose.yaml   # Isolated services, no host ports
├── docker-compose.host.yaml # Optional loopback ports for host/manual tests
├── docker-compose.prebuilt.yaml # Test a prebuilt image (ci.sh with ROBIN_IMAGE)
├── ci.sh                 # Run the suite the way CI does
├── run-tests.sh          # Container test entrypoint
├── tests.Dockerfile      # Maven 3.9.9 / Corretto 21
├── etc/                  # Test-specific application configurations
│   ├── coredns/          # Test DNS: Corefile and zones (remote.test, rbl.test)
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

### Harness services

Besides the Robin under test and its backends (Postgres, Redis, ClamAV, Rspamd, Dovecot),
the stack has three services that let cases test outbound paths without the internet:

| Service | Network names | Role |
|---|---|---|
| `integration-sink` | `sink-backend`, `mx.sink.test` | A second Robin, the remote MTA. It accepts any recipient, stores mail in local maildirs readable through `GET /store/<domain>/<user>/new/`, and answers scripted addresses from `sink-cfg/scenarios.json5` (`tempfail@`, `tempfail-full@`, `greylist@`, `permfail@`, `permfail-quota@`, `permfail-policy@remote.test`). Each also takes a `+tag` (`permfail+my-case@remote.test`) so concurrent cases use their own address. |
| `integration-dns` | `dns-backend` | CoreDNS. Serves `remote.test` (MX `mx.sink.test`), `example.com` (MX `mx.robin.test`, Robin under test, so DSNs to local senders come back as in production) and `rbl.test` (`127.0.0.2` listed), and forwards everything else to Docker DNS. Robin under test uses it through `server.json5` `dns.servers`. |
| `integration-http-sink` | `http-sink` | Records every HTTP request and serves them back: `GET /_sink/requests?path=/prefix`. Replies can be scripted with `POST /_sink/responses` (`path`, `status`, `body`, `contentType`, `delaySeconds`). See `http-sink/http_sink.py`. |

Robin under test relays authenticated (outbound) mail by MX lookup (`cfg/relay.json5`), so a
message submitted on port 587 to `anyone@remote.test` ends up in the sink. Unauthenticated
mail to `remote.test` is refused as an unserved domain. The queue ticks every 5 seconds.

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

# Extra arguments are passed to Maven, e.g. only some case folders (a regex on area/name.json5)
docker compose --project-directory .integration --profile test run --rm integration-tests -Drobin.integration.cases='^(inbound|relay)/'
```

Surefire reports are copied to a fresh `.integration/reports/<run-id>/` directory
per run, so older results cannot masquerade as current reports. The runner recreates
these ports inside its container with forwards to the integration services; no host
port publishing is needed:

| Port | Target |
|---|---|
| `2525` / `2587` | Robin under test, SMTP / submission |
| `28090` | Robin under test, API (`/logs`, `/client/...`) |
| `2143` | Dovecot IMAP |
| `2526` / `28091` | robin-sink, SMTP / API (`/store`, `/logs`) |
| `28099` | http-sink |

It explicitly loads `.integration/client-cfg`, while the remote MTA mounts `.integration/cfg`
read-only with database configuration synchronization disabled.
The default `suite` tag selects every integration case, the assertion self-tests and the
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

### Continuous Integration

`.integration/ci.sh` runs the suite the way CI does. It starts from removed volumes,
saves Surefire reports and every service's logs under `.integration/reports/`, and
removes the stack again, keeping the suite's exit code.

```bash
# Build Robin from the working tree and run the suite
.integration/ci.sh

# Test a prebuilt image instead
ROBIN_IMAGE=robin:candidate .integration/ci.sh
```

GitHub Actions runs it in two places:

| Workflow | Trigger | What it tests |
|---|---|---|
| `integration.yml` | Pushes and pull requests to `master` (except docs-only changes), nightly, manual | The image built from that commit. The nightly run catches breakage from new `latest` service images. Reports and logs are uploaded as the `integration-reports` artifact. |
| `docker-publish.yml` | `v*` tags | Builds the release image once, runs the suite against it, and pushes that same image only if the suite passes. |

The nightly run matters because the stack uses `latest` tags for Dovecot, Rspamd and
ClamAV. For example, Dovecot 2.4.5 dropped the `dovecot` group the configuration relied on.

### Individual Test Cases

Any test case can be run directly:

```bash
# Build Robin first
mvn clean package

# Run individual test case
java -jar target/robin.jar --client --conf .integration/client-cfg/ --gson src/test/resources/cases/config/integration/scanning/spam-gtube.json5
```

## Test Case Details

Cases live in `src/test/resources/cases/config/integration/<area>/` and are discovered by
`cases.IntegrationTest`: adding a case needs no Java. A case with a top-level `request` block
is an HTTP case; any other is an SMTP case. Cases run concurrently, so each must use its own
recipients or messages. A case placed directly in the integration folder fails discovery.
The assertions in each file remain the source of truth.

| Case | Coverage |
|------|----------|
| `inbound/basic-smtp.json5` | SMTP acceptance |
| `inbound/user-not-found.json5` | SQL-backed recipient rejection |
| `inbound/invalid-sender.json5` | Invalid MAIL FROM rejection |
| `inbound/ehlo-capabilities.json5` | EHLO extensions (including 8BITMIME) before and after STARTTLS; no AUTH on the inbound port; no STARTTLS once TLS is up |
| `inbound/tls-negotiated.json5` | STARTTLS negotiates TLS 1.2 or 1.3 and the message is delivered |
| `inbound/tls-legacy-refused.json5` | A TLS 1.0-only client cannot negotiate TLS |
| `inbound/bdat-byte-exact.json5` | CHUNKING in several chunks, including chunks that split lines, delivers a binary part byte for byte |
| `inbound/data-transparency.json5` | Lines starting with a dot survive DATA (dot-stuffing) and a binary part keeps its hash |
| `inbound/header-bare-cr-refused.json5` | A bare CR in the header block is refused with 554 and nothing is delivered |
| `inbound/size-limit-data.json5` | A message over the size limit is refused with 552 over DATA |
| `inbound/size-limit-bdat.json5` | The size limit applies to the whole BDAT message, not each chunk |
| `inbound/multiple-envelopes.json5` | Several transactions on one connection are each delivered |
| `inbound/rset-sequence.json5` | RSET ends the transaction, so a following RCPT is refused with 503 5.5.1 |
| `submission/auth-plain.json5`, `auth-login.json5`, `auth-login-combined.json5` | Submission with each AUTH mechanism, relayed and read back at robin-sink |
| `submission/auth-bad-password.json5` | A wrong password gets 535 |
| `submission/mail-without-auth.json5` | MAIL on the submission port without AUTH gets 530 |
| `submission/auth-requires-tls.json5` | AUTH is not offered before STARTTLS |
| `delivery/delivery-success.json5` | Delivery through scanners and LMTP, verified by IMAP |
| `delivery/multiple-recipients.json5` | Delivery to both valid recipients |
| `delivery/partial-delivery-mixed.json5` | Valid delivery alongside invalid recipient rejection |
| `delivery/chaos-storage-failure.json5` | Injected storage failure rejected with 451 |
| `delivery/chaos-matrix.json5` | Injected failure in each processor (antivirus, spam, local storage, Dovecot) gets 451 and nothing is delivered |
| `delivery/rich-mime-roundtrip.json5` | A built multipart message (QP text with non-ASCII, HTML, PDF, JPEG) arrives with every part intact, with Robin's Authentication-Results and X-Spam headers |
| `delivery/sql-alias.json5` | Mail to an SQL alias is delivered to the alias target |
| `delivery/sql-auto-alias.json5` | Mail to an SQL auto alias (`tony@example.net`) is delivered to its destination |
| `scanning/spam-gtube.json5` | GTUBE rejection with 541 and spam log evidence |
| `scanning/virus-eicar.json5` | EICAR rejection by ClamAV |
| `scanning/virus-eicar-epilogue.json5` | EICAR rejection when the attachment is smuggled into a MIME epilogue |
| `scanning/virus-eicar-zip.json5` | EICAR inside a zip attachment is found |
| `scanning/virus-eicar-forwarded.json5` | EICAR inside a forwarded message (message/rfc822) is found |
| `queue/chaos-lmtp-retry.json5` | Injected LMTP failure for one recipient: the other is delivered inline, the failed one is queued and then delivered from the queue |
| `queue/queue-api.json5` | `/client/queue` returns the item uid; list, retry, bounce and delete each change the queue as expected |
| `queue/admin-bounce-dsn.json5` | A deferred message bounced through `/client/queue/bounce` reaches its sender as a DSN carrying the administrator's reason |
| `queue/queue-raw-eml.json5` | A raw EML upload to `/client/queue` is queued, listed and deleted |
| `relay/outbound-mx-relay.json5` | Authenticated submission relayed by MX lookup to robin-sink; the received copy, read back through the sink's `/store` API, carries no Authentication-Results from Robin |
| `relay/open-relay-refused.json5` | Unauthenticated mail to a remote domain is refused and never reaches robin-sink |
| `relay/permfail-bounce.json5` | A 5xx from the remote MTA is bounced at once: a DSN with the 5.1.1 status, the original headers and an `In-Reply-To` reaches the sender |
| `relay/tempfail-retry-bounce.json5` | A 4xx is retried until the retry budget is spent, then bounced with `Action: failed` |
| `api/health.json5` | `/health` reports UP |
| `api/logs-absent.json5` | `/logs` can prove a string is absent: the search does not log its own query |
| `api/users.json5` | `/users`, `/users/{user}/exists` and `/users/authenticate` against the SQL backend, both outcomes |
| `api/client-send.json5` | `/client/send` runs a case inside Robin and returns the SMTP session |
| `harness/http-sink-recorder.json5` | HTTP case: the http-sink records a POST and serves it back |
| `selftest/*.json5` | Cases that must fail, each for a stated reason; proves every assertion type can fail |

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
