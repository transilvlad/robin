# Full Suite Integration Testing

Detailed testing procedures for validating Robin MTA with the complete production suite.

> **See also:** [Robin Full Suite](../developer/robin-suite.md) for deployment and architecture overview.

## Suite Organization

All suite files are organized under `.suite/`:

```
.suite/
├── cfg/                  # Robin MTA configuration
│   ├── server.json5      # Server settings
│   ├── dovecot.json5     # Dovecot LMTP/SQL integration
│   ├── clamav.json5      # ClamAV settings
│   ├── rspamd.json5      # Rspamd settings
│   └── log4j2.xml        # Logging config (suite.log)
├── etc/                  # Application configurations
│   ├── dovecot/          # Dovecot config (mounted)
│   ├── rspamd/           # Rspamd config (future)
│   └── postgres/         # PostgreSQL config (future)
└── db-init/              # Database init scripts
    ├── 01-create-users.sql
    └── 02-create-roundcube-db.sql

# Shared directories:
log/                      # Suite logs: suite.log, suite-YYYYMMDD.log
store/                    # All persistent data (postgres, clamav, rspamd, etc.)
```

## Test Environment Setup

### 0. First-Time Setup

**After cloning the repository, run the init script:**

```bash
# From repository root
./.suite/init-suite.sh
```

This script creates required `log/` and `store/` directories for all suite services (postgres, clamav, rspamd, robin, dovecot, roundcube).

### 1. Start the Suite

```bash
# From .suite directory
cd .suite
docker-compose up -d
```

**Wait for services to be healthy:**
- ClamAV: 2-3 minutes (downloads virus definitions)
- PostgreSQL: 10-15 seconds (initializes users)
- Rspamd: 20-30 seconds (loads maps)
- Dovecot: 15-20 seconds (connects to PostgreSQL)
- Robin: 10-15 seconds (after Dovecot is ready)

### 2. Verify Health

```bash
# All containers healthy (from .suite directory)
docker-compose ps | grep healthy

# Test users created
docker exec robin-suite-postgres psql -U robin -d robin -c "SELECT email FROM users;"

# LMTP listening
docker exec robin-suite-dovecot netstat -tlnp | grep :24

# Robin API responsive
curl http://localhost:28090/health

# Suite logs being written
tail -5 log/suite.log
```

## Running Tests

### JUnit Integration Tests

```bash
# Run all active tests (currently 3)
mvn test -Dtest=SuiteIntegrationTest

# Run specific test
mvn test -Dtest=SuiteIntegrationTest#test00_basicSmtp
mvn test -Dtest=SuiteIntegrationTest#test01_deliverySuccess
mvn test -Dtest=SuiteIntegrationTest#test08_virusEicar
```

### Individual Test Cases

Any test case can be run directly:

```bash
# Build Robin first
mvn clean package

# Run individual test case
java -jar target/robin.jar --client --case src/test/resources/cases/config/suite/06-spam-gtube.json5
```

## Test Case Details

### 00. Basic SMTP (Baseline)
**File:** `00-basic-smtp.json5`
**Validates:** SMTP protocol compliance, delivery acceptance
**Assertions:** Protocol only (MAIL, RCPT, DATA responses)

### 01. Successful Delivery (Core Test)
**File:** `01-delivery-success.json5`
**Validates:** Complete delivery pipeline
**Flow:** SMTP → ClamAV scan → Rspamd scan → LMTP delivery → IMAP verification
**Assertions:**
- Protocol: SMTP responses (220, 250, 221)
- IMAP: Email exists in recipient's INBOX with correct headers

### 02. Multiple Recipients
**File:** `02-delivery-multiple-recipients.json5`
**Validates:** Multiple RCPT TO handling, batch LMTP delivery
**Recipients:** pepper@example.com, happy@example.com
**Assertions:** Both recipients receive the email

### 03. User Not Found
**File:** `03-user-not-found.json5`
**Validates:** PostgreSQL user lookup, proper rejection
**Expected:** 550 or 554 rejection for nonexistent@example.com

### 04. Invalid Sender
**File:** `04-invalid-sender.json5`
**Validates:** SMTP address validation
**Expected:** 501 or 553 rejection at MAIL FROM stage

### 05. Partial Delivery
**File:** `05-partial-delivery-mixed.json5`
**Validates:** Partial delivery with mixed recipients
**Expected:** Valid recipient gets email, invalid rejected

### 06. GTUBE Spam Pattern
**File:** `06-spam-gtube.json5`
**Validates:** Rspamd GTUBE detection
**Expected:** High spam score, rejection or tagging
**Pattern:** `XJS*C4JDBQADN1.NSBN3*2IDNEN*GTUBE-STANDARD-ANTI-UBE-TEST-EMAIL*C.34X`

### 07. High-Score Spam
**File:** `07-spam-high-score.json5`
**Validates:** Rspamd multi-indicator scoring
**Indicators:** ALL CAPS, urgency language, phishing patterns
**Expected:** Spam score > 15, rejection or tagging

### 08. EICAR Virus
**File:** `08-virus-eicar.json5`
**Validates:** ClamAV virus detection
**Expected:** 554 rejection with "virus" message
**Pattern:** EICAR test string (safe malware simulator)

### 09. Chaos: LDA Failure
**File:** `09-chaos-lda-failure.json5`
**Validates:** Temporary failure handling, retry queueing
**Header:** `X-Robin-Chaos: DovecotLdaClient; exitCode=75`
**Expected:** 4xx response or queued for retry

### 10. Chaos: Storage Failure
**File:** `10-chaos-storage-failure.json5`
**Validates:** Storage processor failure handling
**Header:** `X-Robin-Chaos: LocalStorageClient; processor=AVStorageProcessor; return=false`
**Expected:** 5xx rejection, email NOT delivered

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
  matches: {
    headers: [
      ["subject", "regex or exact match"],
      ["from", "sender@example.com"],
      ["to", "recipient@example.com"]
    ]
  }
}
```

## Clean Testing Environment

### Reset Mailboxes

```bash
# Delete all emails from all mailboxes
docker exec robin-suite-dovecot rm -rf /var/mail/vhosts/example.com/*/new/*
docker exec robin-suite-dovecot rm -rf /var/mail/vhosts/example.com/*/cur/*
```

### Clear Queue

```bash
curl -s http://localhost:28090/client/queue/list | \
  grep -o 'data-uid="[^"]*"' | \
  sed 's/data-uid="//;s/"$//' | \
  while read uid; do
    curl -s -X POST "http://localhost:28090/client/queue/delete?uid=$uid"
  done
```

### Clear Storage

```bash
# Clear temporary storage
docker exec robin-suite-robin rm -rf /usr/local/robin/store/robin/tmp/*
docker exec robin-suite-robin rm -rf /usr/local/robin/store/robin/queue/*
```

## Manual Testing

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

### Check via Roundcube

1. Open http://localhost:8888
2. Login: pepper@example.com / potts
3. View INBOX

## Validation Checklist

When all tests pass, the suite validates:

- ✅ SMTP protocol compliance (RFC 5321)
- ✅ TLS/STARTTLS support
- ✅ SQL-backed authentication (PostgreSQL)
- ✅ Virus scanning integration (ClamAV)
- ✅ Spam detection integration (Rspamd)
- ✅ LMTP mailbox delivery (Dovecot)
- ✅ IMAP email retrieval
- ✅ Queue persistence and retry logic
- ✅ Error handling and rejection
- ✅ Chaos engineering (failure injection)
- ✅ Multi-recipient delivery
- ✅ Partial delivery handling

## Test-Specific Configuration

The suite uses test-specific settings in `.suite/cfg/`:

- **chaosHeaders: true** - Enables failure injection testing
- **xclientEnabled: true** - Allows XCLIENT extension testing
- **rbl.enabled: false** - Disables RBL checks for faster testing
- **ClamAV/Rspamd enabled** - Full scanning in test environment

## Known Limitations

1. **IMAP Assertion Timing** - IMAP assertions may timeout in automated tests due to LMTP delivery delays. Manual IMAP verification works reliably.
2. **Logs Assertions** - Log file assertions require container filesystem access. Use manual log queries via API instead.

## Related Documentation

- **[Robin Suite Deployment](../developer/robin-suite.md)** - Architecture and configuration
- **[SMTP Test Cases](case-smtp.md)** - General SMTP testing
- **[HTTP Test Cases](case-http.md)** - HTTP/API testing
- **[Queue Features](../features/queue.md)** - Queue management
- **[Dovecot Integration](../lib/dovecot-sasl.md)** - SASL and LMTP details
