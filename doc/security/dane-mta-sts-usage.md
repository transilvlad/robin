# DANE and MTA-STS Security Policies

## Overview

Robin MTA implements RFC-compliant DANE (DNS-Based Authentication of Named Entities) and MTA-STS (MTA Strict Transport Security) for secure SMTP connections. These security policies protect against downgrade attacks and ensure encrypted, authenticated delivery.

## RFC Compliance

- **RFC 7672**: SMTP Security via Opportunistic DANE TLS
- **RFC 6698**: DANE TLSA Record Format
- **RFC 8461**: SMTP MTA Strict Transport Security (MTA-STS)
- **RFC 3207**: SMTP STARTTLS Extension

## Priority and Precedence

Per **RFC 8461 Section 2**, DANE takes absolute precedence over MTA-STS:

> "senders who implement MTA-STS validation MUST NOT allow MTA-STS Policy validation to override a failing DANE validation."

### Resolution Order

1. **DANE** - Check for TLSA records at `_25._tcp.<mxhostname>`
   - If TLSA records exist → Use DANE policy
   - MTA-STS is completely skipped (DANE has priority)

2. **MTA-STS** - If no DANE, check for MTA-STS policy
   - Fetch from `https://mta-sts.<domain>/.well-known/mta-sts.txt`
   - If valid policy exists → Use MTA-STS policy

3. **Opportunistic** - If neither available
   - TLS attempted if advertised
   - Cleartext acceptable

## DANE (RFC 7672)

### What is DANE?

DANE uses DNSSEC-secured DNS records (TLSA) to specify which certificates are valid for a mail server. This eliminates reliance on Certificate Authorities and provides cryptographic proof of certificate validity.

### DANE Requirements

When DANE TLSA records are present:

- ✅ **TLS is MANDATORY** - STARTTLS MUST be used
- ✅ **Certificate validation** - Server cert MUST match TLSA records
- ✅ **No fallback** - Validation failure → message delay/bounce (no cleartext)
- ✅ **DNSSEC required** - TLSA records must be DNSSEC-validated (best practice)

### TLSA Record Format

```
_25._tcp.mail.example.com. IN TLSA 3 1 1 <hex-hash>
                                   │ │ │ └─ Certificate Association Data
                                   │ │ └─── Matching Type (0=exact, 1=SHA256, 2=SHA512)
                                   │ └───── Selector (0=full cert, 1=pubkey)
                                   └─────── Usage (0-3, see below)
```

### TLSA Usage Types

| Usage | Name | Description | Robin Support |
|-------|------|-------------|---------------|
| 0 | PKIX-TA | CA constraint | ✅ Supported |
| 1 | PKIX-EE | Service certificate constraint | ✅ Supported |
| 2 | DANE-TA | Trust anchor assertion | ✅ Supported |
| 3 | DANE-EE | Domain-issued certificate | ✅ Supported (most common) |

### Example DANE Setup

```bash
# Check DANE for an MX host
dig _25._tcp.mail.example.com TLSA +dnssec

# Example TLSA record (Usage 3, Selector 1, Matching 1)
_25._tcp.mail.example.com. IN TLSA 3 1 1 a1b2c3d4e5f6...
```

## MTA-STS (RFC 8461)

### What is MTA-STS?

MTA-STS provides a mechanism for mail domains to signal that they support TLS and want sending MTAs to refuse mail delivery if TLS cannot be successfully negotiated. Unlike DANE, it does not require DNSSEC.

### MTA-STS Requirements

When MTA-STS policy is present:

- ✅ **TLS is MANDATORY** - STARTTLS MUST be used
- ✅ **PKI validation** - Server cert MUST validate via Web PKI
- ✅ **MX matching** - MX hostname MUST match policy patterns
- ✅ **No fallback** - Validation failure → message delay/bounce (no cleartext)

### MTA-STS Policy Modes

| Mode | Behavior |
|------|----------|
| **enforce** | Strict - delivery MUST fail on validation errors |
| **testing** | Report only - deliver despite errors (for testing) |
| **none** | No policy active |

### Example MTA-STS Setup

```bash
# DNS TXT record
_mta-sts.example.com. IN TXT "v=STSv1; id=20250122T120000;"

# Policy file at https://mta-sts.example.com/.well-known/mta-sts.txt
version: STSv1
mode: enforce
mx: mail.example.com
mx: *.example.com
max_age: 86400
```

## Implementation Architecture

### 1. MX Resolution with Security Policy

**Class**: `com.mimecast.robin.mx.MXResolver`

```java
MXResolver resolver = new MXResolver();

// New secure resolution method (recommended).
List<SecureMxRecord> secureMxList = resolver.resolveSecureMx("example.com");

for (SecureMxRecord secureMx : secureMxList) {
    String mxHost = secureMx.getHostname();
    SecurityPolicy policy = secureMx.getSecurityPolicy();

    // Policy type: DANE, MTA_STS, or OPPORTUNISTIC.
    SecurityPolicy.PolicyType type = policy.getType();

    // Check if TLS is mandatory.
    boolean tlsRequired = policy.isTlsMandatory();
}
```

### 2. Security Policy Enforcement

**Classes**:
- `com.mimecast.robin.smtp.security.SecurityPolicy` - Policy representation
- `com.mimecast.robin.smtp.session.Session` - Holds policy for connection
- `com.mimecast.robin.smtp.extension.client.ClientStartTls` - Enforces mandatory TLS

```java
// Set security policy on session before connecting.
session.setSecurityPolicy(securityPolicy);

// Create and connect.
Connection connection = new Connection(session);
connection.connect();

// ClientStartTls automatically enforces policy:
// - If policy.isTlsMandatory() and !server.advertisesSTARTTLS() → FAIL.
// - If policy.isDane() → Use DaneTrustManager for cert validation.
// - If policy.isMtaSts() → Use PKI validation.
```

### 3. DANE Certificate Validation

**Class**: `com.mimecast.robin.smtp.security.DaneTrustManager`

Validates server certificates against DANE TLSA records:

- **Usage 0/1** (PKIX-TA/PKIX-EE): Validates with CA chain constraints
- **Usage 2** (DANE-TA): Validates against trust anchor
- **Usage 3** (DANE-EE): Validates against end entity cert (most common)

Supports:
- **Selector 0**: Full certificate matching
- **Selector 1**: SubjectPublicKeyInfo matching
- **Matching Type 0**: Exact match (no hash)
- **Matching Type 1**: SHA-256 hash
- **Matching Type 2**: SHA-512 hash

### 4. TLS Socket Creation

**Classes**:
- `com.mimecast.robin.smtp.security.TLSSocket` - Interface
- `com.mimecast.robin.smtp.security.DefaultTLSSocket` - Implementation

```java
// TLS negotiation with policy enforcement.
socket = Factories.getTLSSocket()
    .setSocket(socket)
    .setProtocols(protocols)
    .setCiphers(ciphers)
    .setSecurityPolicy(securityPolicy)  // Policy determines TrustManager.
    .startTLS(true);
```

## Usage Examples

### Example 1: Basic Secure Delivery

```java
// Resolve MX with security policies.
MXResolver resolver = new MXResolver();
List<SecureMxRecord> secureMxList = resolver.resolveSecureMx("example.com");

for (SecureMxRecord secureMx : secureMxList) {
    try {
        // Create session and set policy.
        Session session = new Session();
        session.setMx(List.of(secureMx.getHostname()));
        session.setPort(25);
        session.setSecurityPolicy(secureMx.getSecurityPolicy());

        // Connect and deliver.
        EmailDelivery delivery = new EmailDelivery(session);
        delivery.send();

        // Success - TLS was enforced per policy.
        break;

    } catch (SmtpException e) {
        // Policy violation or connection failure.
        log.warn("Delivery failed for {}: {}", secureMx.getHostname(), e.getMessage());
        // Try next MX.
    }
}
```

### Example 2: Checking Security Policy Type

```java
List<SecureMxRecord> secureMxList = resolver.resolveSecureMx("example.com");

for (SecureMxRecord secureMx : secureMxList) {
    SecurityPolicy policy = secureMx.getSecurityPolicy();

    switch (policy.getType()) {
        case DANE:
            log.info("DANE policy active - {} TLSA records",
                    policy.getDaneRecords().size());
            // TLS mandatory, TLSA validation required
            break;

        case MTA_STS:
            log.info("MTA-STS policy active - mode: {}",
                    policy.getMtaStsPolicy());
            // TLS mandatory, PKI validation required
            break;

        case OPPORTUNISTIC:
            log.info("No security policy - opportunistic TLS");
            // TLS if available, cleartext acceptable.
            break;
    }
}
```

### Example 3: Manual Policy Application

```java
// Manually check DANE for an MX.
List<DaneRecord> daneRecords = DaneChecker.checkDane("mail.example.com");

if (!daneRecords.isEmpty()) {
    // Create DANE policy.
    SecurityPolicy danePolicy = SecurityPolicy.dane("mail.example.com", daneRecords);

    // Set on session.
    session.setSecurityPolicy(danePolicy);
    session.setMx(List.of("mail.example.com"));

    // Connect - DANE will be enforced.
    connection.connect();
}
```

## Testing DANE and MTA-STS

### Testing DANE

```bash
# Check if domain has DANE
java -jar target/robin.jar --client --case test-dane.json5
```

**test-dane.json5**:
```json5
{
  mx: ["mail.example.com"],  // MX with DANE
  port: 25,
  tls: true,  // Will be mandatory if DANE detected
  envelopes: [{
    mail: "test@example.com",
    rcpt: ["recipient@example.com"],
    subject: "DANE Test",
    message: "Testing DANE enforcement"
  }]
}
```

### Testing MTA-STS

```bash
# Check MTA-STS policy for domain
java -jar target/robin.jar --mtasts --domain example.com
```

## Security Policy Behavior Matrix

| Scenario | DANE | MTA-STS | TLS Required | Cert Validation | Fallback on Fail |
|----------|------|---------|--------------|-----------------|------------------|
| DANE TLSA found | ✅ Active | ❌ Skipped | ✅ Mandatory | TLSA records | ❌ No - bounce |
| No DANE, MTA-STS enforce | ❌ None | ✅ Active | ✅ Mandatory | Web PKI | ❌ No - bounce |
| No DANE, MTA-STS testing | ❌ None | ⚠️ Testing | ⚠️ Attempted | Web PKI | ✅ Yes - deliver |
| Neither present | ❌ None | ❌ None | ⚠️ Opportunistic | Web PKI | ✅ Yes - cleartext OK |

## Troubleshooting

### DANE Validation Failures

**Error**: `Certificate does not match any DANE TLSA records`

**Causes**:
1. Server certificate changed but TLSA records not updated
2. TLSA record misconfiguration (wrong usage/selector/matching type)
3. Certificate chain issues

**Debug**:
```java
// Enable debug logging.
log4j2.xml: <Logger name="com.mimecast.robin.smtp.security" level="debug"/>

// Check DANE records
List<DaneRecord> records. = DaneChecker.checkDane("mail.example.com");
for (DaneRecord record : records) {
    log.info("TLSA: {} {} {} {}",
            record.getUsageDescription(),
            record.getSelectorDescription(),
            record.getMatchingTypeDescription(),
            record.getCertificateData());
}
```

### MTA-STS Validation Failures

**Error**: `Security policy MTA_STS requires TLS but server does not advertise STARTTLS`

**Causes**:
1. MX server doesn't support STARTTLS
2. MX configuration error
3. Network/firewall blocking STARTTLS

**Check**:
```bash
# Test STARTTLS support
openssl s_client -connect mail.example.com:25 -starttls smtp

# Check MTA-STS policy
curl https://mta-sts.example.com/.well-known/mta-sts.txt
```

### Policy Not Detected

**Issue**: Security policy not being applied

**Debug**:
1. Check MX resolution logs:
   ```
   log4j2.xml: <Logger name="com.mimecast.robin.mx" level="debug"/>
   ```

2. Verify DANE records exist:
   ```bash
   dig _25._tcp.mail.example.com TLSA +short
   ```

3. Verify MTA-STS policy accessible:
   ```bash
   dig _mta-sts.example.com TXT +short
   curl -v https://mta-sts.example.com/.well-known/mta-sts.txt
   ```

## Configuration

### Enabling DANE/MTA-STS in Robin

No special configuration needed - DANE and MTA-STS are automatically detected during MX resolution when using `MXResolver.resolveSecureMx()`.

### TLS Protocol and Cipher Configuration

Configure acceptable TLS protocols and ciphers in test cases:

```json5
{
  mx: ["mail.example.com"],
  port: 25,
  tls: true,

  // Restrict TLS versions (optional)
  protocols: ["TLSv1.3", "TLSv1.2"],

  // Restrict cipher suites (optional)
  ciphers: [
    "TLS_AES_256_GCM_SHA384",
    "TLS_AES_128_GCM_SHA256"
  ],

  envelopes: [/* ... */]
}
```

**Note**: DANE and MTA-STS policies may require minimum TLS versions. Robin enforces the configured protocols/ciphers alongside policy requirements.

## Implementation Details

### Code Flow for Outbound Delivery

1. **MX Resolution** (MXResolver.resolveSecureMx)
   - Query MX records for domain
   - Check each MX for DANE TLSA records
   - If no DANE, check for MTA-STS policy
   - Return `List<SecureMxRecord>` with policies attached

2. **Session Setup**
   - Set `session.setSecurityPolicy(policy)`
   - Set `session.setMx(List.of(mxHost))`

3. **Connection Establishment**
   - `connection.connect()` establishes socket
   - EHLO/HELO negotiation

4. **STARTTLS Negotiation** (ClientStartTls)
   - If `policy.isTlsMandatory()`:
     - Verify server advertises STARTTLS (fail if not)
     - Send STARTTLS command
     - Fail if STARTTLS rejected
   - Call `connection.startTLS(true)`

5. **TLS Handshake** (DefaultTLSSocket)
   - If `policy.isDane()`:
     - Use `DaneTrustManager` for validation
     - Validate cert against TLSA records
   - If `policy.isMtaSts()`:
     - Use standard PKI `TrustManager`
   - Complete handshake or fail

6. **SMTP Delivery**
   - Continue with MAIL/RCPT/DATA over encrypted connection
   - Security policy satisfied

### Modified Components

| Component | Changes |
|-----------|---------|
| MXResolver | Added `resolveSecureMx()` method for security policy resolution |
| Session | Added `securityPolicy` field and getter/setter |
| Connection | Override `getSecurityPolicy()` to pass policy to TLS layer |
| SmtpFoundation | Modified `startTLS()` to pass policy to TLSSocket |
| TLSSocket | Added `setSecurityPolicy()` method |
| DefaultTLSSocket | Implements policy-aware TrustManager selection |
| ClientStartTls | Enforces mandatory TLS when policy requires it |
| DaneTrustManager | New class - validates certificates against TLSA records |
| SecurityPolicy | New class - represents DANE/MTA-STS/Opportunistic policies |
| SecureMxRecord | New class - MX record with associated security policy |

## Production Considerations

### DNSSEC Validation

**Important**: Robin currently uses `XBillDnsRecordClient` which does not enforce DNSSEC validation. For production DANE deployments:

1. Ensure your DNS resolver validates DNSSEC
2. Configure system DNS to use DNSSEC-validating resolvers
3. Consider using a DNSSEC-aware DNS library
4. Validate "bogus" and "indeterminate" responses are treated as failures

### Certificate Management

**DANE**:
- TLSA records MUST be updated before certificate changes
- Use Usage 3 (DANE-EE) for maximum flexibility
- Publish TLSA for both current and next certificates during rollover

**MTA-STS**:
- Certificates must be valid per Web PKI
- Certificate hostname must match MX hostname
- Use valid CA-signed certificates

### Policy Caching

MTA-STS policies should be cached per RFC 8461:
- Cache duration specified in `max_age` field
- Implement `PolicyCache` for production use
- Robin includes abstract `PolicyCache` class in `com.mimecast.robin.mx.cache`

### Monitoring and Logging

Enable detailed logging for security policy enforcement:

```xml
<!-- log4j2.xml -->
<Logger name="com.mimecast.robin.mx" level="info"/>
<Logger name="com.mimecast.robin.smtp.security" level="info"/>
<Logger name="com.mimecast.robin.smtp.extension.client.ClientStartTls" level="info"/>
```

Key log messages:
- `DANE TLSA records found for MX host`
- `MTA-STS policy found for domain`
- `Security policy {type} requires mandatory TLS`
- `Certificate validated successfully against DANE TLSA record`
- `TLS negotiation successful: TLSv1.3:... [Policy: DANE]`

## Limitations

1. **DNSSEC Validation**: Not currently enforced - relies on system DNS resolver
2. **Usage 0/1 TLSA**: PKIX-based TLSA types supported but trust anchor validation is basic
3. **IPv6**: CIDR matching in IP restrictions uses simple prefix matching
4. **Testing Mode**: MTA-STS testing mode behaves like enforce mode (reports not implemented)

## See Also

- [Bots Documentation](../features/bots.md) - Email analysis bot includes DANE/MTA-STS checks
- [MTA-STS Library Documentation](../lib/mta-sts/lib.md) - Detailed MTA-STS library usage
- [DANE Package JavaDoc](../../src/main/java/com/mimecast/robin/mx/dane/package-info.java)
- [Security Package JavaDoc](../../src/main/java/com/mimecast/robin/smtp/security/package-info.java)

## References

- [RFC 7672 - SMTP Security via Opportunistic DANE TLS](https://tools.ietf.org/html/rfc7672)
- [RFC 6698 - DANE TLSA](https://tools.ietf.org/html/rfc6698)
- [RFC 8461 - SMTP MTA-STS](https://tools.ietf.org/html/rfc8461)
- [RFC 3207 - SMTP STARTTLS](https://tools.ietf.org/html/rfc3207)
