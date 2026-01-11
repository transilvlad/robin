# Performance Testing Suite

Comprehensive performance testing infrastructure for comparing Robin MTA against industry-standard MTAs with various storage backends.

> **See also:** [.perf/readme.md](../../.perf/readme.md) for complete performance comparison analysis and results.

## Setup

**First-time setup after cloning the repository:**

```bash
# Initialize directories and file permissions (requires sudo for Postfix config)
sudo ./.perf/init-perf.sh
```

This script creates required `log/` and `store/` directories and sets proper file ownership for Postfix's `dynamicmaps.cf` (required to be root-owned for security).

## Test Configurations

The `.perf/` directory contains six different performance testing configurations comparing various MTA + storage backend combinations.

### 1. Robin + Dovecot LMTP

**Location:** `.perf/robin-dovecot/docker-compose.robin.yaml`

**Architecture:**
- **MTA:** Robin MTA (Java thread pool, connection pooling)
- **Storage:** Dovecot LMTP delivery
- **Backend:** Maildir filesystem storage
- **Protocol:** SMTP → Robin → LMTP → Dovecot

**Containers:**
- `perf-robin` - Robin MTA server
- `perf-dovecot` - Dovecot LMTP-only (no IMAP)
- `perf-postgres` - PostgreSQL for authentication and queue

**Usage:**
```bash
cd .perf/robin-dovecot
../.shared/run-test.sh -t 20 -l 50  # 1k test
```

---

### 2. Postfix + Dovecot LMTP

**Location:** `.perf/robin-dovecot/docker-compose.postfix.yaml`

**Architecture:**
- **MTA:** Postfix (process forking model)
- **Storage:** Dovecot LMTP delivery
- **Backend:** Maildir filesystem storage
- **Protocol:** SMTP → Postfix → LMTP → Dovecot

**Containers:**
- `perf-postfix` - Postfix MTA
- `perf-dovecot` - Dovecot LMTP-only (no IMAP)
- `perf-postgres` - PostgreSQL for virtual domains

**Usage:**
```bash
cd .perf/robin-dovecot
COMPOSE_FILE=docker-compose.postfix.yaml ../.shared/run-test.sh -t 20 -l 50
```

**Critical Configuration Notes:**
- Postfix requires `dynamicmaps.cf` to be root-owned (set by init-perf.sh)
- PostgreSQL virtual domain support via `pgsql-virtual-mailbox-domains.cf`
- High concurrency requires tuning: `default_process_limit`, `postlogd_watchdog_timeout`

---

### 3. Robin + Dovecot LDA

**Location:** `.perf/robin-dovecot-lda/docker-compose.yaml`

**Architecture:**
- **MTA:** Robin MTA
- **Storage:** Dovecot LDA subprocess delivery
- **Backend:** Maildir filesystem storage
- **Protocol:** SMTP → Robin → LDA subprocess → Dovecot

**Containers:**
- `perf-robin-dovecot-lda` - Combined Robin + Dovecot in single container
- `perf-postgres` - PostgreSQL for authentication and queue

**Usage:**
```bash
cd .perf/robin-dovecot-lda
docker-compose up -d
# Manual testing required - no automated test runner yet
```

**Status:** Under development - addressing LDA subprocess concurrency issues.

**Known Issues:**
- LDA subprocess spawning can exhaust resources under high concurrency
- No timeout on `process.waitFor()` causes indefinite blocking
- See [Plan](#current-work) for fixes in progress

---

### 4. Robin + Stalwart LMTP

**Location:** `.perf/robin-stalwart/docker-compose.robin.yaml`

**Architecture:**
- **MTA:** Robin MTA
- **Storage:** Stalwart LMTP delivery
- **Backend:** RocksDB embedded database
- **Protocol:** SMTP → Robin → LMTP → Stalwart

**Containers:**
- `perf-robin` - Robin MTA server
- `perf-stalwart` - Stalwart mail server (LMTP + IMAP)
- `perf-postgres` - PostgreSQL for authentication and queue

**Usage:**
```bash
cd .perf/robin-stalwart
../.shared/run-test.sh -t 20 -l 50  # 1k test
```

**Critical Configuration Notes:**
- **Rate limiting MUST be disabled** - see Stalwart Configuration section below
- Uses IMAP for verification (via `imap-tool.py`)
- Password hashes stored as SHA-512 crypt in PostgreSQL

---

### 5. Postfix + Stalwart LMTP

**Location:** `.perf/robin-stalwart/docker-compose.postfix.yaml`

**Architecture:**
- **MTA:** Postfix
- **Storage:** Stalwart LMTP delivery
- **Backend:** RocksDB embedded database
- **Protocol:** SMTP → Postfix → LMTP → Stalwart

**Containers:**
- `perf-postfix` - Postfix MTA
- `perf-stalwart` - Stalwart mail server (LMTP + IMAP)
- `perf-postgres` - PostgreSQL for virtual domains

**Usage:**
```bash
cd .perf/robin-stalwart
COMPOSE_FILE=docker-compose.postfix.yaml ../.shared/run-test.sh -t 20 -l 50
```

---

### 6. Stalwart Bare (All-in-One)

**Location:** `.perf/stalwart-bare/docker-compose.yaml`

**Architecture:**
- **MTA:** Stalwart (direct SMTP delivery)
- **Storage:** RocksDB embedded database
- **Backend:** RocksDB
- **Protocol:** SMTP → Stalwart (no LMTP)

**Containers:**
- `perf-stalwart` - Stalwart all-in-one (SMTP + IMAP + storage)
- `perf-postgres` - PostgreSQL for authentication

**Usage:**
```bash
cd .perf/stalwart-bare
../.shared/run-test.sh -t 20 -l 50  # 1k test
```

**Note:** This configuration tests Stalwart as a standalone all-in-one mail server without an external MTA.

---

## JMeter Test Plan

**File:** `.perf/.shared/performance-test.jmx`

**Test Plan Structure:**
- **Thread Group:** Configurable concurrent threads
- **Loop Count:** Emails per thread
- **SMTP Sampler:** Sends test emails via SMTP protocol
- **Assertions:** Response code validation (250 OK)

**Default Parameters:**
- `${threads}` - Number of concurrent SMTP connections
- `${loops}` - Number of emails per thread
- Total emails = threads × loops

**Sample Email:**
- **From:** tony@example.com
- **To:** pepper@example.com
- **Subject:** Test message
- **Body:** Lorem ipsum test content

**Outputs:**
- `.jtl` results file (CSV format with timestamps, latency, success/failure)
- HTML report with graphs, statistics, and throughput analysis

**Usage:**
```bash
# Direct JMeter invocation
jmeter -n -t .perf/.shared/performance-test.jmx \
  -Jthreads=20 \
  -Jloops=50 \
  -l results.jtl \
  -e -o report/

# Via run-test.sh (recommended)
cd .perf/robin-stalwart
../.shared/run-test.sh -t 20 -l 50
```

---

## Testing Utilities

### run-test.sh - Unified Test Runner

**File:** `.perf/.shared/run-test.sh`

Automated test orchestration script that handles the complete test lifecycle.

**Features:**
- Auto-detects backend (Dovecot/Stalwart) from compose file
- Starts containers and waits for health checks
- Cleans previous test data (IMAP or doveadm)
- Runs JMeter with configurable threads/loops
- Generates HTML report with detailed metrics
- Verifies delivery (IMAP count for Stalwart, JMeter results for Dovecot)
- Prompts to stop containers after test

**Usage:**
```bash
# Standard 1k test (20 threads × 50 loops)
../.shared/run-test.sh -t 20 -l 50

# Full load test (10k emails: 200 threads × 50 loops)
../.shared/run-test.sh --full

# Custom test
../.shared/run-test.sh -t 5 -l 10  # 50 emails

# Override compose file
COMPOSE_FILE=docker-compose.postfix.yaml ../.shared/run-test.sh -t 20 -l 50
```

**Environment Variables:**
- `COMPOSE_FILE` - Override docker-compose file (default: `docker-compose.robin.yaml`)
- `THREADS` - Override thread count
- `LOOPS` - Override loop count

**Auto-Detection:**
- Detects Stalwart vs Dovecot from compose file name
- Chooses IMAP verification for Stalwart, JMeter results for Dovecot
- Adjusts cleanup commands accordingly

**Output:**
```
[INFO] JMeter version: 5.6.3
[INFO] Compose file: docker-compose.robin.yaml
[INFO] Backend: stalwart

[INFO] Starting Docker containers...
[INFO] Waiting for containers to be healthy (30 seconds)...
[INFO] Cleaning previous test data from stalwart...
[INFO] Starting JMeter performance test...
[INFO]   Threads: 20
[INFO]   Emails per thread: 50
[INFO]   Total emails: 1000

[SUCCESS] JMeter test completed successfully
[INFO] Verifying email delivery...
[INFO] Emails delivered to stalwart: 1000
[SUCCESS] All 1000 emails delivered successfully!

📊 Test Results Summary
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Backend:          stalwart
  Compose File:     docker-compose.robin.yaml
  Total Emails:     1000
  Emails Delivered: 1000
  HTML Report:      ./results/robin-20260111-143022-report/index.html
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

### imap-tool.py - IMAP Verification

**File:** `.perf/.scripts/imap-tool.py`

IMAP-based email delivery verification for Stalwart performance tests. Replaces Dovecot's `doveadm` command for counting and deleting messages.

**Features:**
- Connect to IMAP/IMAPS servers
- Count messages in any folder
- Delete all messages (for cleanup)
- Automatic SSL/TLS detection

**Usage:**
```bash
# Count messages in INBOX
python3 .perf/.scripts/imap-tool.py \
  --host localhost --port 2143 \
  --user pepper@example.com --pass potts

# Delete all messages (cleanup between tests)
python3 .perf/.scripts/imap-tool.py \
  --host localhost --port 2143 \
  --user pepper@example.com --pass potts \
  --delete-all

# Check specific folder
python3 .perf/.scripts/imap-tool.py \
  --host localhost --port 2143 \
  --user tony@example.com --pass stark \
  --folder Sent
```

**Options:**
- `--host` - IMAP server hostname (default: localhost)
- `--port` - IMAP port (default: 993; use 2143 for testing)
- `--user` - Username (email address)
- `--pass` - Password
- `--folder` - Folder to check (default: INBOX)
- `--delete-all` - Delete all messages in folder

**Output:**
```
Message count: 1000
```

**Use Case:** Stalwart tests use IMAP for verification because the test container includes IMAP support. Dovecot performance containers are LMTP-only and use JMeter results for verification.

---

### test-smtp.py - Simple SMTP Test

**File:** `.perf/.scripts/test-smtp.py`

Simple SMTP test utility for manual testing and debugging.

**Features:**
- Send single test email via SMTP
- Configurable sender/recipient
- Quick validation of MTA connectivity

**Usage:**
```bash
# Default (localhost:2525, tony@example.com → pepper@example.com)
python3 .perf/.scripts/test-smtp.py

# Custom parameters
python3 .perf/.scripts/test-smtp.py localhost 25 sender@test.com recipient@test.com

# Test different port
python3 .perf/.scripts/test-smtp.py localhost 587 tony@example.com pepper@example.com
```

**Output:**
```
SUCCESS: Email sent from tony@example.com to pepper@example.com
```

**Use Case:** Quick validation that MTA is accepting mail before running full JMeter tests. Useful for debugging connection issues.

---

### analyze-flamegraph.py - Performance Profiling

**File:** `.perf/.scripts/analyze-flamegraph.py`

Parse async-profiler flamegraph HTML and extract Robin MTA hotspot statistics.

**Features:**
- Extracts constant pool from flamegraph HTML
- Decompresses async-profiler compression format
- Identifies Robin-specific frames
- Categorizes hotspots by component
- Calculates percentage distributions

**Usage:**
```bash
# Analyze flamegraph HTML output
python3 .perf/.scripts/analyze-flamegraph.py path/to/flamegraph.html
```

**Output Categories:**
- I/O Reading (LineInputStream, readLine, readMultiline)
- SMTP Protocol (ServerData, EmailReceipt)
- Storage (LocalStorageClient, StorageProcessor)
- LMTP Delivery (DovecotStorageProcessor, saveToLmtp)
- Email Parsing (EmailParser, MimeHeader)
- Configuration (Config, Properties)

**Sample Output:**
```
Extracted 1,234 frames from constant pool

Total samples: 45,678

==================================================================================
ROBIN MTA HOTSPOTS (sorted by sample count)
==================================================================================

 1.  3,456 samples ( 7.57% total, 12.34% robin)
    com/mimecast/robin/smtp/io/LineInputStream.readLine

 2.  2,345 samples ( 5.13% total,  8.37% robin)
    com/mimecast/robin/smtp/server/extension/server/ServerData.asciiRead

==================================================================================
Total Robin samples: 28,000 / 45,678 (61.32%)
==================================================================================

HOTSPOT CATEGORIES
==================================================================================

I/O Reading (LineInputStream, readLine, readMultiline)
  Total: 12,345 samples (27.03% of all, 44.09% of robin)
  Top frames:
     3,456 (28.0%)  LineInputStream.readLine
     2,345 (19.0%)  SmtpFoundation.read
```

**Use Case:** Profiling Robin MTA performance to identify bottlenecks. Requires async-profiler flamegraph HTML as input.

---

## Test Users

All configurations use the same test users in PostgreSQL:

| Email | Password | Hash Type |
|-------|----------|-----------|
| tony@example.com | stark | {PLAIN} for Dovecot, SHA-512 for Stalwart |
| pepper@example.com | potts | {PLAIN} for Dovecot, SHA-512 for Stalwart |
| happy@example.com | hogan | {PLAIN} for Dovecot, SHA-512 for Stalwart |

**Note:** Dovecot accepts `{PLAIN}password` format in PostgreSQL. Stalwart requires SHA-512 crypt hashes (e.g., `$6$rounds=5000$...`).

---

## Critical Configuration Issues

### Postfix: PostgreSQL Support

**Problem:** Postfix's PostgreSQL support requires correct `dynamicmaps.cf` configuration.

**Solution:** The `init-perf.sh` script creates a real file (not symlink) and sets root ownership:

```bash
sudo ./.perf/init-perf.sh
```

**Manual Fix (if needed):**
```bash
# Set ownership to root:root 644
sudo chown root:root .perf/.shared/postfix/etc/postfix/dynamicmaps.cf
sudo chmod 644 .perf/.shared/postfix/etc/postfix/dynamicmaps.cf
```

**Verification:**
```bash
# Check pgsql module loaded
docker exec perf-postfix postconf -m | grep pgsql

# Test virtual domain lookup
docker exec perf-postfix postmap -q example.com \
  pgsql:/etc/postfix/pgsql-virtual-mailbox-domains.cf
```

**Common Error:** "unsupported dictionary type: pgsql" or "undefined symbol: dict_pgsql_open"

---

### Stalwart: Rate Limiting

**Problem:** Stalwart has aggressive rate limiting enabled by default that severely throttles performance tests:
- **IP limit:** 5 connections per second
- **Sender limit:** 25 emails per hour

**Impact:** A 1k test that should complete in 25 seconds would take ~40 hours.

**Critical:** These limits CANNOT be disabled via `config.toml` file. They are stored in Stalwart's internal database and MUST be disabled via CLI.

**Solution:**
```bash
# Disable IP-based rate limiting
docker exec perf-stalwart stalwart-cli -u http://localhost:8080 -c admin:admin123 \
  server add-config queue.limiter.inbound.ip.enable false

# Disable sender-based rate limiting
docker exec perf-stalwart stalwart-cli -u http://localhost:8080 -c admin:admin123 \
  server add-config queue.limiter.inbound.sender.enable false

# Reload configuration
docker exec perf-stalwart stalwart-cli -u http://localhost:8080 -c admin:admin123 \
  server reload-config
```

**Note:** `run-test.sh` automatically disables rate limiting for `stalwart-bare` configuration.

---

### Stalwart: Spam Filtering

**Problem:** Spam filtering is enabled by default and classifies test emails as spam (delivered to Junk folder instead of INBOX).

**Solution:**
```bash
docker exec perf-stalwart stalwart-cli -u http://localhost:8080 -c admin:admin123 \
  server add-config spam-filter.enable false
```

---

### Stalwart: Password Format

**Problem:** Stalwart requires SHA-512 crypt hashes, NOT Dovecot's `{PLAIN}password` format.

**Solution:**
```bash
# Generate SHA-512 hash
python3 -c "import crypt; print(crypt.crypt('potts', crypt.mksalt(crypt.METHOD_SHA512)))"

# Store in PostgreSQL
INSERT INTO accounts (name, secret, type) VALUES
  ('pepper@example.com', '$6$rounds=5000$...', 'individual');
```

---

## Testing Best Practices

1. **Start Small** - Test with 1 email first, then 10, then 100, before full 1k load
2. **Verify Configuration** - Check PostgreSQL connectivity, LMTP listener, rate limiting
3. **Align Settings** - Use identical limits for fair comparison
4. **Monitor Health** - Watch container status during tests
5. **Clean Between Runs** - Delete test data before each run
6. **Use run-test.sh** - Leverage automation for consistent results
7. **Keep Only Success** - Remove failed test results, keep only successful runs

---

## Troubleshooting

### Postfix "Temporary lookup failure"

**Check PostgreSQL connectivity:**
```bash
docker exec perf-postfix bash -c \
  'PGPASSWORD=robin psql -h perf-postgres -U robin -d robin -c "SELECT 1;"'
```

**Verify dynamicmaps.cf:**
```bash
docker exec perf-postfix ls -la /etc/postfix/dynamicmaps.cf
docker exec perf-postfix postconf -m | grep pgsql
```

---

### Stalwart Rate Limiting Issues

**Symptom:** Very slow test (< 1 email/sec), Robin queue fills up

**Check logs:**
```bash
docker logs perf-stalwart | grep "Rate limit exceeded"
```

**Fix:** Disable rate limiting via CLI (see Critical Configuration Issues above)

---

### Stalwart Authentication Failures

**Symptom:** IMAP verification fails with authentication error

**Verify password hashes:**
```bash
docker exec perf-postgres psql -U robin -d robin -c \
  "SELECT name, LEFT(secret, 20) FROM accounts WHERE name = 'pepper@example.com';"
```

**Test manually:**
```bash
python3 .perf/.scripts/imap-tool.py \
  --host localhost --port 2143 \
  --user pepper@example.com --pass potts
```

---

### Zero Emails Delivered

**Check container health:**
```bash
docker ps -a --filter "name=perf-"
```

**Restart if crashed:**
```bash
docker-compose -f docker-compose.robin.yaml restart
```

**Verify LMTP listener:**
```bash
docker exec perf-dovecot netstat -tlnp | grep :24
# or
docker exec perf-stalwart sh -c "nc -z localhost 24 && echo 'LMTP OK' || echo 'LMTP NOT RUNNING'"
```

---

## Related Documentation

- **[.perf/readme.md](../../.perf/readme.md)** - Complete performance comparison analysis
- **[.perf/robin-dovecot/readme.md](../../.perf/robin-dovecot/readme.md)** - Robin vs Postfix with Dovecot
- **[.perf/robin-stalwart/readme.md](../../.perf/robin-stalwart/readme.md)** - Robin vs Postfix with Stalwart
- **[.perf/stalwart-bare/readme.md](../../.perf/stalwart-bare/readme.md)** - Stalwart standalone
- **[Full Suite Testing](full-suite.md)** - Integration testing procedures
