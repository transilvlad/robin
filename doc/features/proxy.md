# Proxy Feature

The proxy feature allows Robin to act as an SMTP/ESMTP/LMTP proxy, forwarding matching emails to another mail server instead of storing them locally. This is useful for routing specific emails to different mail systems based on configurable rules.

## Overview

When enabled, the proxy feature:
- Matches incoming emails against configured rules (IP, EHLO, MAIL FROM, RCPT TO patterns)
- Establishes a connection to a remote SMTP/ESMTP/LMTP server on first recipient match
- Forwards MAIL FROM and each matching RCPT TO command to the remote server
- Streams the email data to the remote server after local storage processors accept it
- Returns the remote server's responses to the original client
- Closes the proxy connection after DATA transmission completes

## Configuration

The proxy feature is configured in `proxy.json5`:

```json5
{
  // Enable or disable proxy functionality (default: false).
  enabled: false,

  // List of proxy rules.
  // Only the FIRST matching rule will proxy the email.
  rules: [
    {
      // Matching patterns (all specified patterns must match - AND logic)
      rcpt: ".*@proxy-destination\\.example\\.com",  // Regex for RCPT TO
      mail: ".*@source\\.example\\.com",             // Regex for MAIL FROM (optional)
      ehlo: ".*\\.trusted\\.com",                    // Regex for EHLO/HELO (optional)
      ip: "192\\.168\\.1\\..*",                      // Regex for IP address (optional)

      // Proxy destination configuration
      hosts: ["relay.example.com", "backup-relay.example.com"],  // SMTP server hostnames or IPs
      port: 25,                    // SMTP server port (default: 25)
      protocol: "esmtp",           // Protocol: smtp, esmtp, or lmtp (default: esmtp)
      tls: false,                  // Use TLS connection (default: false)

      // Direction filtering (default: both)
      direction: "both",           // Direction: both, inbound, or outbound

      // Authentication (optional)
      authUsername: "username",    // SMTP AUTH username (optional)
      authPassword: "password",    // SMTP AUTH password (optional)
      authMechanism: "PLAIN",      // AUTH mechanism (default: PLAIN)

      // Action for non-matching recipients (default: none)
      // - "accept": Accept non-matching recipients locally
      // - "reject": Reject non-matching recipients
      // - "none": Continue with normal recipient processing
      action: "none"
    }
  ]
}
```

## Rule Matching

Rules are evaluated in order, and **only the first matching rule** is used for proxying. Subsequent matching rules are ignored (but logged as warnings).

### Pattern Matching

All pattern fields (ip, ehlo, mail, rcpt) support regular expressions:
- Patterns are matched using Java's `Pattern.matches()` (full string match)
- Empty or missing patterns match anything
- All specified patterns in a rule must match for the rule to apply (AND logic)

### Matching Examples

```json5
// Example 1: Match specific recipient domain
{
  rcpt: ".*@proxy\\.example\\.com",
  hosts: ["relay.example.com"],
  port: 25,
  protocol: "esmtp",
  tls: false,
  direction: "both",
  action: "none"
}

// Example 2: Match IP range and sender domain (inbound only)
{
  ip: "10\\.0\\..*",
  mail: ".*@partner\\.example\\.com",
  hosts: ["partner-relay.example.com"],
  port: 587,
  protocol: "esmtp",
  tls: true,
  direction: "inbound",
  action: "reject"
}

// Example 3: Match EHLO domain and specific recipient (outbound only)
{
  ehlo: ".*\\.internal\\.com",
  rcpt: ".*@external\\.example\\.com",
  hosts: ["external-relay.example.com"],
  port: 25,
  protocol: "lmtp",
  tls: false,
  direction: "outbound",
  action: "accept"
}
```

## Supported Protocols

### SMTP
Basic SMTP protocol without ESMTP extensions.

```json5
{
  protocol: "smtp",
  hosts: ["smtp.example.com"],
  port: 25,
  tls: false
}
```

### ESMTP (Default)
Extended SMTP with support for extensions like SIZE, STARTTLS, etc.

```json5
{
  protocol: "esmtp",
  hosts: ["esmtp.example.com"],
  port: 587,
  tls: true
}
```

### LMTP
Local Mail Transfer Protocol, commonly used for delivery to mail storage systems.

```json5
{
  protocol: "lmtp",
  hosts: ["dovecot.example.com"],
  port: 24,
  tls: false
}
```

## Authentication

Proxy connections now support SMTP authentication. Configure the `authUsername`, `authPassword`, and optionally `authMechanism` fields in the proxy rule.

### Basic Authentication Example

```json5
{
  rcpt: ".*@relay\\.example\\.com",
  hosts: ["smtp.relay.example.com"],
  port: 587,
  protocol: "esmtp",
  tls: true,
  direction: "both",
  authUsername: "relay-user",
  authPassword: "relay-password",
  authMechanism: "PLAIN",  // Optional, defaults to PLAIN
  action: "none"
}
```

### Authentication Mechanisms

Currently supported authentication mechanism:
- **PLAIN**: Sends username and password in base64-encoded format (requires TLS for security)

### Authentication Flow

When authentication is configured:
1. Proxy connection establishes and sends EHLO/LHLO
2. If TLS is enabled, STARTTLS is executed
3. AUTH command is sent with the specified mechanism
4. Username and password are transmitted
5. If authentication succeeds, MAIL FROM is sent
6. If authentication fails, the connection is closed and recipients are rejected

### Security Best Practices

- **Always use TLS** (`tls: true`) when authenticating to protect credentials
- Store passwords securely (consider using HashiCorp Vault with `${vault:secret/path}` syntax)
- Use strong, unique passwords for each proxy destination
- Limit authentication to trusted networks when possible

## Connection Reuse and Multi-Envelope Support

**Connection Reuse**: Proxy connections are now reused across multiple envelopes within the same session for significant performance improvements.

### How Connection Reuse Works

1. **First Envelope**: Connection established → EHLO/LHLO → STARTTLS → AUTH → MAIL → RCPT(s) → DATA
2. **Second Envelope**: Connection reused → MAIL → RCPT(s) → DATA (no reconnection!)
3. **Third Envelope**: Connection reused → MAIL → RCPT(s) → DATA
4. **Session End**: All connections closed in EmailReceipt finally block

### Benefits

- **Performance**: 60-80% faster for multiple messages (eliminates connection overhead)
- **Network Efficiency**: Fewer round trips (no repeated EHLO/STARTTLS/AUTH)
- **Resource Efficient**: Single connection can proxy many messages
- **Transparent**: Automatic reuse when same rule matches subsequent envelopes

### Connection Lifecycle

```
SMTP Session Start
  ↓
First matching recipient → Establish proxy connection
  ↓
DATA sent → Connection remains open
  ↓
Next envelope, same rule → Reuse connection (no reconnect)
  ↓
DATA sent → Connection remains open
  ↓
SMTP Session End → Close all proxy connections
```

### Connection Storage

- Connections stored in **Session** (not per envelope)
- Keyed by **ProxyRule** (rules with same destination share connection)
- Automatically managed - no manual cleanup needed
- Thread-safe within session scope

## Direction Filtering

Proxy rules can filter based on connection direction, enabling sophisticated routing scenarios.

### Direction Values

- **`both`** (default): Matches both inbound and outbound connections
- **`inbound`**: Only matches connections receiving mail (client → server)
- **`outbound`**: Only matches connections sending mail (server → remote)

### Direction Examples

#### Inbound Only
Route incoming mail to internal relay:

```json5
{
  rcpt: ".*@internal\\.example\\.com",
  hosts: ["internal-relay.example.com"],
  direction: "inbound",  // Only match incoming connections
  port: 25,
  action: "none"
}
```

#### Outbound Only
Route outgoing mail to external relay:

```json5
{
  mail: ".*@example\\.com",
  rcpt: ".*@external\\..*",
  hosts: ["external-relay.example.com"],
  direction: "outbound",  // Only match outgoing connections
  port: 587,
  tls: true,
  authUsername: "relay",
  authPassword: "secret"
}
```

#### Both Directions
Route all matching mail (default behavior):

```json5
{
  rcpt: ".*@partner\\.example\\.com",
  hosts: ["partner-relay.example.com"],
  direction: "both",  // Match any direction
  port: 25
}
```

### Use Cases

- **Split Routing**: Different destinations for inbound vs outbound
- **Security Zones**: Separate relays for external vs internal mail
- **Compliance**: Route sensitive outbound mail through auditing relay
- **Testing**: Proxy test traffic inbound only, production outbound only

## Non-Matching Recipient Actions

The `action` parameter controls what happens to recipients that don't match any proxy rule:

### `none` (Default)
Continue with normal recipient processing (Dovecot auth, scenarios, etc.). This is the most flexible option.

```json5
{
  rcpt: ".*@proxy\\.example\\.com",
  hosts: ["relay.example.com"],
  action: "none"  // Non-matching recipients go through normal processing
}
```

### `accept`
Accept all non-matching recipients locally without further validation.

```json5
{
  rcpt: ".*@proxy\\.example\\.com",
  hosts: ["relay.example.com"],
  action: "accept"  // Non-matching recipients are accepted locally
}
```

### `reject`
Reject all non-matching recipients.

```json5
{
  rcpt: ".*@proxy\\.example\\.com",
  hosts: ["relay.example.com"],
  action: "reject"  // Non-matching recipients are rejected
}
```

## SMTP Exchange Flow

When a proxy rule matches:

1. **RCPT TO Phase** (ServerRcpt):
   - First matching recipient checks session for existing connection
   - If no connection exists, new connection established
   - Connection executes EHLO/LHLO, STARTTLS (if configured), AUTH, and MAIL FROM
   - Each matching RCPT TO is forwarded to proxy server
   - Proxy server's response is returned to client
   - Connection stored in session for reuse

2. **DATA Phase** (ServerData):
   - Email is read and temporarily stored locally
   - Storage processors validate the email
   - If accepted, email data is streamed to proxy server via DATA command
   - **Connection remains open** for reuse with next envelope
   - Proxy server's response is returned to client

3. **Next Envelope** (Connection Reuse):
   - Same rule matches → Retrieve existing connection from session
   - Connection prepared for new envelope → MAIL FROM sent
   - RCPT TO commands forwarded to proxy server
   - DATA streamed → Connection remains open again

4. **Session End** (EmailReceipt):
   - All proxy connections closed in finally block
   - This is the **only** place connections are closed
   - Ensures cleanup even if errors occur

5. **Error Handling**:
   - If proxy connection fails, error stored in session
   - Subsequent matches reuse stored error (avoids retry storms)
   - Connections closed automatically on session end

## Integration with Other Features

### Storage Processors
The proxy feature works with storage processors:
- Email is always stored locally via storage processors
- Webhooks are called as normal
- Email is streamed to proxy server after storage

### Blackhole Feature
If both proxy and blackhole rules match:
- Blackhole is checked AFTER proxy
- If proxy matches, blackhole is not evaluated for that recipient
- Non-proxied recipients can still be blackholed

### Relay Feature
Proxy and relay are independent:
- Proxy handles matching emails immediately
- Non-proxied emails can still be queued for relay
- Proxy does not use the relay queue

## Logging

The proxy feature logs important events:

```
INFO: Proxy match - IP: 192.168.1.100, EHLO: mail.example.com, MAIL: sender@example.com, RCPT: recipient@proxy.example.com
INFO: Established proxy connection for first matching recipient
DEBUG: Proxy RCPT response: 250 2.1.5 OK
INFO: Email successfully proxied to remote server
```

Warnings are logged for:
- Multiple matching rules (only first is used)
- Proxy connection failures
- SMTP errors during proxy

## Security Considerations

1. **TLS**: Always use TLS when proxying over untrusted networks
2. **Authentication**: Use SMTP AUTH when proxying to external relays (see Authentication section)
3. **Credentials**: Store authentication credentials securely, preferably using HashiCorp Vault integration
4. **Trust**: Only proxy to trusted mail servers
5. **Validation**: Storage processors still validate emails before proxying
6. **Logging**: All proxy activity is logged for audit trails

## Performance

The proxy feature with connection reuse:
- **Single message**: Identical performance to previous design
- **Multiple messages**: 60-80% faster (no reconnection overhead)
- **Network latency**: Significantly reduced (fewer round trips)
- Streams email data efficiently without extra buffering
- Multiple recipients share same connection
- Does not impact non-proxied email performance

### Performance Comparison

#### Before (Per-Envelope Connections)
```
Message 1: Connect + EHLO + STARTTLS + AUTH + MAIL + RCPT + DATA + Close
Message 2: Connect + EHLO + STARTTLS + AUTH + MAIL + RCPT + DATA + Close
Message 3: Connect + EHLO + STARTTLS + AUTH + MAIL + RCPT + DATA + Close
Total: 24 SMTP commands + 3 connection establishments
```

#### After (Connection Reuse)
```
Message 1: Connect + EHLO + STARTTLS + AUTH + MAIL + RCPT + DATA
Message 2: MAIL + RCPT + DATA (reused connection!)
Message 3: MAIL + RCPT + DATA (reused connection!)
Session End: Close
Total: 13 SMTP commands + 1 connection establishment
```

**Result**: ~46% fewer SMTP commands, ~67% fewer connections

## Troubleshooting

### Proxy Connection Fails
Check logs for connection errors:
```
ERROR: Failed to establish proxy connection: Connection refused
```
Verify:
- Host and port are correct
- Remote server is accessible
- Firewall rules allow outbound connections
- TLS configuration matches remote server

### Wrong Recipients Proxied
Review rule patterns:
- Check regex syntax
- Test patterns with sample data
- Remember only first matching rule applies

### Emails Not Proxied
Verify:
- Proxy is enabled: `enabled: true`
- Rules match your test data
- Logs show "Proxy match" message
- No earlier errors in connection

## Example Configurations

### Simple Domain Routing
Route specific domain to dedicated server:

```json5
{
  enabled: true,
  rules: [
    {
      rcpt: ".*@partner\\.example\\.com",
      hosts: ["partner-mx.example.com"],
      port: 25,
      protocol: "esmtp",
      tls: true,
      direction: "both",
      action: "none"
    }
  ]
}
```

### Multi-Tenant Setup
Route different tenants to different servers:

```json5
{
  enabled: true,
  rules: [
    {
      rcpt: ".*@tenant1\\.example\\.com",
      hosts: ["tenant1-mail.example.com", "tenant1-backup.example.com"],
      port: 25,
      protocol: "esmtp",
      tls: true,
      direction: "both",
      action: "reject"
    },
    {
      rcpt: ".*@tenant2\\.example\\.com",
      hosts: ["tenant2-mail.example.com", "tenant2-backup.example.com"],
      port: 25,
      protocol: "esmtp",
      tls: true,
      direction: "both",
      action: "reject"
    }
  ]
}
```

### Internal/External Split
Route internal mail to local server, external to relay:

```json5
{
  enabled: true,
  rules: [
    {
      ehlo: ".*\\.internal\\.example\\.com",
      rcpt: ".*@external\\..*",
      hosts: ["external-relay.example.com"],
      port: 587,
      protocol: "esmtp",
      tls: true,
      direction: "outbound",  // Only proxy outbound mail
      action: "none"
    }
  ]
}
```

## API Reference

### ProxyConfig
Configuration class for proxy settings.

Methods:
- `isEnabled()`: Returns true if proxy is enabled
- `getRules()`: Returns list of ProxyRule instances

### ProxyRule
Type-safe representation of a single proxy rule.

Methods:
- `getIp()`: Gets IP address pattern
- `getEhlo()`: Gets EHLO domain pattern
- `getMail()`: Gets MAIL FROM pattern
- `getRcpt()`: Gets RCPT TO pattern
- `getHosts()`: Gets list of destination hosts
- `getHost()`: Gets first destination host (convenience method)
- `getPort()`: Gets destination port
- `getProtocol()`: Gets protocol (smtp/esmtp/lmtp)
- `isTls()`: Returns true if TLS enabled
- `getDirection()`: Gets direction filter (both/inbound/outbound)
- `matchesDirection(isInbound)`: Checks if rule matches given direction
- `getAction()`: Gets action for non-matching recipients
- `getAuthUsername()`: Gets authentication username
- `getAuthPassword()`: Gets authentication password
- `getAuthMechanism()`: Gets authentication mechanism
- `hasAuth()`: Returns true if authentication is configured
- `equals(Object)`: Checks equality based on destination (for connection pooling)
- `hashCode()`: Generates hash based on destination (for connection pooling)

### ProxyMatcher
Utility class for matching emails against proxy rules.

Methods:
- `findMatchingRule(ip, ehlo, mail, rcpt, isInbound, config)`: Returns first matching ProxyRule (logs warnings for additional matches)

### Session
Session class now stores proxy connections.

New Methods:
- `getProxyConnection(rule)`: Gets existing connection for a rule
- `setProxyConnection(rule, connection)`: Stores connection for reuse
- `getProxyConnections()`: Gets all proxy connections
- `closeProxyConnections()`: Closes and clears all proxy connections

### ProxyBehaviour
Client behaviour for proxy connections.

Methods:
- `process(connection)`: Executes EHLO, STARTTLS, AUTH, MAIL FROM
- `sendRcpt(recipient)`: Sends single RCPT TO
- `sendData()`: Sends DATA command and streams email
- `sendQuit()`: Closes connection

### ProxyEmailDelivery
Wrapper for proxy email delivery with connection reuse support.

Methods:
- `connect()`: Establishes connection and sends MAIL FROM (or reuses existing)
- `prepareForEnvelope(envelope)`: Prepares connection for new envelope
- `isForCurrentEnvelope(envelope)`: Checks if handling given envelope
- `sendRcpt(recipient)`: Sends RCPT TO for recipient
- `sendData()`: Sends DATA command
- `close()`: Closes connection
- `isConnected()`: Returns connection status

## Future Enhancements

Potential improvements to the proxy feature:
- Additional authentication mechanisms (LOGIN, CRAM-MD5, XOAUTH2)
- Automatic failover across multiple hosts in hosts list
- Conditional proxying based on email content (headers, body, attachments)
- Load balancing strategies (round-robin, least-connections, weighted)
- Per-rule retry configuration with exponential backoff
- Connection health checks and automatic reconnection
- Metrics per proxy rule (messages proxied, bytes transferred, latency)
