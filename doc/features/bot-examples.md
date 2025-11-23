# Email Bot Response Examples

This document provides examples of responses from Robin's email infrastructure analysis bots.

## Overview

Robin supports two types of automated analysis bots:

1. **Session Bot** (`session`) - Analyzes the complete SMTP session
2. **Email Analysis Bot** (`email`) - Performs comprehensive email security and infrastructure checks

Both bots respond with **text/plain** content. The Session Bot additionally includes session data as JSON.

## Bot Configuration

### Example Configuration (cfg/bots.json5)

```json5
{
  bots: [
    {
      addressPattern: "^robotSession(\\+.+)?@robin\\.local$",
      allowedIps: ["127.0.0.1", "::1", "192.168.0.0/16"],
      allowedTokens: [],
      botName: "session"
    },
    {
      addressPattern: "^robotEmail(\\+.+)?@robin\\.local$",
      allowedIps: ["127.0.0.1", "::1", "192.168.0.0/16"],
      allowedTokens: [],
      botName: "email"
    }
  ]
}
```

## Input Email Example

### sample.eml

```
From: test.sender@example.com
To: robotSession@robin.local, robotEmail@robin.local
Subject: Test Email for Bot Analysis
Date: Fri, 22 Nov 2025 12:00:00 +0000
Message-ID: <test123@example.com>
MIME-Version: 1.0
Content-Type: text/plain; charset=UTF-8

This is a test email to trigger both bots.
The bots will analyze this email and send back reports.
```

### Sending Test Email

```bash
# Send to both bots
cat sample.eml | sendmail robotSession@robin.local robotEmail@robin.local

# Or via command line
echo "Test message" | mail -s "Bot Test" \
  -r "test.sender@example.com" \
  "robotSession@robin.local,robotEmail@robin.local"
```

## Response 1: Session Bot

### Response Format

The Session Bot sends a **text/plain** email containing:
1. Human-readable session summary
2. Complete session data as JSON

### Example Response Email

```
From: robotSession@robin.local
To: test.sender@example.com
Subject: Re: Test Email for Bot Analysis - Session Analysis
Date: Fri, 22 Nov 2025 12:00:05 +0000
Content-Type: text/plain; charset=UTF-8

======================================================================
SMTP SESSION ANALYSIS
======================================================================
Session UID: a1b2c3d4-e5f6-7890-abcd-ef1234567890
Generated: 2025-11-22 12:00:05 UTC
======================================================================

CONNECTION
----------------------------------------------------------------------
Local Server:    robin.local (192.168.1.10)
Remote Client:   mail.example.com (192.168.1.100)
Connection Time: 2025-11-22 11:59:58 UTC

HELO/EHLO
----------------------------------------------------------------------
EHLO Domain:     mail.example.com
Extensions:      STARTTLS, AUTH PLAIN LOGIN, SIZE 52428800,
                 8BITMIME, PIPELINING, CHUNKING

TLS
----------------------------------------------------------------------
TLS Active:      Yes
Protocol:        TLSv1.3
Cipher:          TLS_AES_256_GCM_SHA384

AUTHENTICATION
----------------------------------------------------------------------
Authenticated:   No

ENVELOPE
----------------------------------------------------------------------
MAIL FROM:       test.sender@example.com
RCPT TO:         robotSession@robin.local
                 robotEmail@robin.local

TRANSACTION LOG
----------------------------------------------------------------------
SMTP    220 robin.local ESMTP Robin MTA
EHLO    250-robin.local
        250 STARTTLS
STARTTLS 220 Ready to start TLS
TLS     TLSv1.3:TLS_AES_256_GCM_SHA384
MAIL    250 Sender OK
RCPT    250 Recipient OK
RCPT    250 Recipient OK
DATA    250 Message accepted

SESSION JSON
----------------------------------------------------------------------
{
  "uid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "direction": "INBOUND",
  "addr": "192.168.1.10",
  "rdns": "robin.local",
  "friendAddr": "192.168.1.100",
  "friendRdns": "mail.example.com",
  "ehlo": "mail.example.com",
  "tls": true,
  "startTls": true,
  "authenticated": false,
  "envelopes": [{
    "mail": "test.sender@example.com",
    "rcpts": ["robotSession@robin.local", "robotEmail@robin.local"]
  }]
}

======================================================================
END OF SESSION ANALYSIS
======================================================================
```

## Response 2: Email Analysis Bot

### Response Format

The Email Analysis Bot sends a **text/plain** email containing comprehensive security and infrastructure analysis.

### Example Response Email

```
From: robotEmail@robin.local
To: test.sender@example.com
Subject: Re: Test Email for Bot Analysis - Email Analysis
Date: Fri, 22 Nov 2025 12:00:06 +0000
Content-Type: text/plain; charset=UTF-8

======================================================================
EMAIL INFRASTRUCTURE ANALYSIS
======================================================================
Generated: 2025-11-22 12:00:06 UTC
Sender IP: 192.168.1.100
Sender Domain: example.com
======================================================================

DNSBL Check
----------------------------------------------------------------------
Sender IP: 192.168.1.100
Status: NOT BLACKLISTED

Checked RBLs:
  - zen.spamhaus.org
  - bl.spamcop.net
  - b.barracudacentral.org
  - dnsbl.sorbs.net

Reverse DNS (rDNS)
----------------------------------------------------------------------
IP                   rDNS
192.168.1.100        mail.example.com

Forward Confirmed Reverse DNS (FCrDNS)
----------------------------------------------------------------------
IP                   rDNS                 Forward IP       Result
192.168.1.100        mail.example.com     192.168.1.100    PASS

SPF (Sender Policy Framework)
----------------------------------------------------------------------
SPF Record: v=spf1 ip4:192.168.1.0/24 -all
Result: R_SPF_ALLOW
Score: 0.0

DKIM (DomainKeys Identified Mail)
----------------------------------------------------------------------
DKIM Signature: Found
Verification: R_DKIM_ALLOW
Score: 0.0

DMARC (Domain-based Message Authentication)
----------------------------------------------------------------------
DMARC Record: v=DMARC1; p=quarantine
Policy: quarantine
Result: R_DMARC_ALLOW
Score: 0.0

MX Records
----------------------------------------------------------------------
Priority  Server
10        mail1.example.com
20        mail2.example.com

MTA-STS Policy
----------------------------------------------------------------------
Policy Found: Yes
Policy Mode: enforce
Max Age: 86400 seconds

DANE (DNS-Based Authentication of Named Entities)
----------------------------------------------------------------------
MX Host: mail1.example.com
DANE Enabled: Yes
TLSA Records: 2

TLSA Record 1:
  Usage: DANE-EE
  Selector: SubjectPublicKeyInfo
  Matching: SHA-256

MX Host: mail2.example.com
DANE Enabled: No

Virus Scan
----------------------------------------------------------------------
Scanner: ClamAV
Status: CLEAN

Spam Analysis
----------------------------------------------------------------------
Score: 2.5 / 15.0
Status: HAM (Not Spam)
Action: no action

======================================================================
END OF REPORT
======================================================================
```

## Reply Address Resolution

Both bots determine where to send the reply using this priority order:

1. **Sieve reply address** (embedded in bot address)
2. **Reply-To header** (from email)
3. **From header** (from email)
4. **Envelope MAIL FROM** (SMTP envelope)

### Sieve Reply Address Format

The sieve format allows embedding a custom reply address in the bot address itself. The token is optional if the sender IP is authorized.

**Format 1 (without token)**: `robot+localpart+domain.tld@botdomain.com`

**Format 2 (with token)**: `robot+token+localpart+domain.tld@botdomain.com`

The reply address is encoded with `+` instead of `@`:
- Everything after the last pair of `+` symbols is the reply address
- The first `+` in the reply part becomes the `@`

**Examples**:

| Bot Address | Reply Sent To |
|-------------|---------------|
| `robotSession+admin+internal.com@robin.local` | `admin@internal.com` |
| `robotSession+abc+admin+internal.com@robin.local` | `admin@internal.com` (with token) |
| `robotEmail+user+example.org@robin.local` | `user@example.org` |
| `robotEmail+token+user+example.org@robin.local` | `user@example.org` (with token) |
| `robotSession+john.doe+company.net@robin.local` | `john.doe@company.net` |
| `robot+token@botdomain.com` | *(no reply address extracted)* |
| `robot+user@botdomain.com` | *(no reply address extracted)* |
| `robot+token+user@botdomain.com` | *(no reply address extracted)* |
| `robot+user+sub.domain.com@botdomain.com` | `user@sub.domain.com` |
| `robot+token+user+sub.domain.com@botdomain.com` | `user@sub.domain.com` |
| `robot+tok-en_123+user+domain.com@botdomain.com` | `user@domain.com` |
| `robot+user.name+domain.com@botdomain.com` | `user.name@domain.com` |
| `robot+1234+user+domain.com@botdomain.com` | `user@domain.com` |
| `robot+tok-en+user+do-main.com@botdomain.com` | `user@do-main.com` |

### Usage Example

```bash
# Without token (if IP is authorized)
echo "Analysis request" | mail -s "Analysis" \
  -r "sender@example.com" \
  "robotEmail+admin+internal.company.com@robin.local"

# With token and custom reply address
echo "Analysis request" | mail -s "Analysis" \
  -r "sender@example.com" \
  "robotEmail+mytoken+admin+internal.company.com@robin.local"

# Bot will reply to: admin@internal.company.com
```

## Token Authentication Example

### Configuration with Tokens

```json5
{
  bots: [
    {
      addressPattern: "^robotSession(\\+.+)?@robin\\.local$",
      allowedIps: [],  // No IP restriction
      allowedTokens: ["secret123", "token456"],
      botName: "session"
    }
  ]
}
```

### Sending with Token

```bash
# Valid token - authorized
echo "Test" | mail -s "Token Test" robotSession+secret123@robin.local

# Invalid token - rejected
echo "Test" | mail -s "Token Test" robotSession+wrongtoken@robin.local

# No token - rejected (when allowedTokens is not empty)
echo "Test" | mail -s "Token Test" robotSession@robin.local
```

### Token with Reply Address

```bash
# Combine token and custom reply address
echo "Analysis" | mail -s "Secure Analysis" \
  "robotEmail+secret123+admin+ops.company.com@robin.local"

# Token: secret123
# Reply to: admin@ops.company.com
```

## Bot Comparison

| Feature | Session Bot | Email Analysis Bot |
|---------|-------------|-------------------|
| **Output Format** | Text + JSON | Text only |
| **SMTP Details** | ✅ Full transaction log | ❌ Not included |
| **Connection Info** | ✅ IP, rDNS, TLS | ✅ IP, rDNS, FCrDNS |
| **Authentication** | ✅ Auth status, username | ❌ Not included |
| **DNSBL/RBL** | ❌ Not included | ✅ Multiple providers |
| **SPF/DKIM/DMARC** | ❌ Not included | ✅ Full details |
| **MX Records** | ❌ Not included | ✅ All MX hosts |
| **MTA-STS** | ❌ Not included | ✅ Policy details |
| **DANE** | ❌ Not included | ✅ TLSA records |
| **Virus Scan** | ❌ Not included | ✅ ClamAV results |
| **Spam Score** | ❌ Not included | ✅ Rspamd analysis |
| **JSON Data** | ✅ Complete session | ❌ Not included |

## Use Cases

### When to Use Session Bot:
- Debugging SMTP connection issues
- Verifying TLS negotiation
- Checking authentication problems
- Analyzing protocol-level issues
- Getting complete session state

### When to Use Email Analysis Bot:
- Security auditing
- Email authentication verification
- Infrastructure validation
- Spam/virus investigation
- DNS configuration checking

### Using Both Together:
Send to both addresses for comprehensive diagnostics combining session-level and infrastructure-level analysis.

## Testing Setup

### 1. Configure Bots

Edit `cfg/bots.json5` with bot definitions.

### 2. Start Robin Server

```bash
java -jar target/robin.jar --server --path cfg/
```

### 3. Send Test Email

```bash
# Simple test
echo "Test" | mail -s "Bot Test" robotSession@robin.local

# Both bots
echo "Test" | mail -s "Complete Analysis" \
  robotSession@robin.local,robotEmail@robin.local

# With token
echo "Test" | mail -s "Secure" robotEmail+secret123@robin.local

# With custom reply address
echo "Test" | mail -s "Custom Reply" \
  robotSession+token+admin+ops.local@robin.local
```

### 4. Check Responses

```bash
# Check queue for bot responses
ls -la store/queue/

# Or check inbox if using local delivery
```

## Integration Testing

### Test Configuration Example

Create `src/test/resources/cases/config/bot-test.json5`:

```json5
{
  mx: ["127.0.0.1"],
  port: 2525,
  tls: false,

  envelopes: [{
    mail: "sender@example.com",
    rcpt: [
      "robotSession@robin.local",
      "robotEmail@robin.local"
    ],
    subject: "Bot Integration Test",
    message: "Testing both bots"
  }],

  assertions: {
    protocol: [
      ["SMTP", "^220"],
      ["EHLO", "^250"],
      ["RCPT", "^250.*robotSession"],
      ["RCPT", "^250.*robotEmail"],
      ["DATA", "^250"]
    ]
  }
}
```

### Java Test

```java
@Test
void testBothBotsTrigger() throws Exception {
    new Client().send("src/test/resources/cases/config/bot-test.json5");
    // Bots process asynchronously - check queue for responses
}
```

## Troubleshooting

### Bot Not Responding

1. **Check bot address pattern** - Verify regex matches
2. **Check authorization** - Verify IP or token is valid
3. **Check reply address** - Ensure From/Reply-To headers exist or use sieve format
4. **Check logs** - Look for bot processing messages

### Invalid Reply Address

If bot cannot determine reply address:
- Add Reply-To or From header to email
- Use sieve format to embed reply address
- Check envelope MAIL FROM is valid

### Token Not Working

- Verify token is in `allowedTokens` list
- Check token is after first `+` in address
- Ensure bot address matches pattern
- Check logs for authorization messages

## Advanced Examples

### Example 1: Multiple Recipients with Token

```bash
# Both bots with same token
echo "Analysis" | mail -s "Dual Analysis" \
  robotSession+mytoken@robin.local,robotEmail+mytoken@robin.local
```

### Example 2: Custom Reply for Operations Team

```bash
# Route replies to ops team
echo "Security Check" | mail -s "Security Audit" \
  -r "security@example.com" \
  "robotEmail+audit2025+ops+monitoring.company.com@robin.local"

# Replies go to: ops@monitoring.company.com
```

### Example 3: Automated Monitoring

```bash
#!/bin/bash
# Daily infrastructure check script

# Check email security
echo "Daily Check" | mail -s "Daily Infrastructure Check" \
  "robotEmail+dailycheck+monitoring+ops.local@robin.local"

# Check SMTP session health
echo "Health Check" | mail -s "SMTP Health" \
  "robotSession+healthcheck+alerts+ops.local@robin.local"
```

## See Also

- [Bots Configuration Guide](bots.md) - Complete bot configuration documentation
- [DANE and MTA-STS](../security/dane-mta-sts-usage.md) - Security policy implementation details
