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
      host: "relay.example.com",   // SMTP server hostname or IP
      port: 25,                    // SMTP server port (default: 25)
      protocol: "esmtp",           // Protocol: smtp, esmtp, or lmtp (default: esmtp)
      tls: false,                  // Use TLS connection (default: false)
      
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
  host: "relay.example.com",
  port: 25,
  protocol: "esmtp",
  tls: false,
  action: "none"
}

// Example 2: Match IP range and sender domain
{
  ip: "10\\.0\\..*",
  mail: ".*@partner\\.example\\.com",
  host: "partner-relay.example.com",
  port: 587,
  protocol: "esmtp",
  tls: true,
  action: "reject"
}

// Example 3: Match EHLO domain and specific recipient
{
  ehlo: ".*\\.internal\\.com",
  rcpt: ".*@external\\.example\\.com",
  host: "external-relay.example.com",
  port: 25,
  protocol: "lmtp",
  tls: false,
  action: "accept"
}
```

## Supported Protocols

### SMTP
Basic SMTP protocol without ESMTP extensions.

```json5
{
  protocol: "smtp",
  host: "smtp.example.com",
  port: 25,
  tls: false
}
```

### ESMTP (Default)
Extended SMTP with support for extensions like SIZE, STARTTLS, etc.

```json5
{
  protocol: "esmtp",
  host: "esmtp.example.com",
  port: 587,
  tls: true
}
```

### LMTP
Local Mail Transfer Protocol, commonly used for delivery to mail storage systems.

```json5
{
  protocol: "lmtp",
  host: "dovecot.example.com",
  port: 24,
  tls: false
}
```

## Non-Matching Recipient Actions

The `action` parameter controls what happens to recipients that don't match any proxy rule:

### `none` (Default)
Continue with normal recipient processing (Dovecot auth, scenarios, etc.). This is the most flexible option.

```json5
{
  rcpt: ".*@proxy\\.example\\.com",
  host: "relay.example.com",
  action: "none"  // Non-matching recipients go through normal processing
}
```

### `accept`
Accept all non-matching recipients locally without further validation.

```json5
{
  rcpt: ".*@proxy\\.example\\.com",
  host: "relay.example.com",
  action: "accept"  // Non-matching recipients are accepted locally
}
```

### `reject`
Reject all non-matching recipients.

```json5
{
  rcpt: ".*@proxy\\.example\\.com",
  host: "relay.example.com",
  action: "reject"  // Non-matching recipients are rejected
}
```

## SMTP Exchange Flow

When a proxy rule matches:

1. **RCPT TO Phase** (ServerRcpt):
   - First matching recipient triggers proxy connection establishment
   - Connection executes EHLO/LHLO, STARTTLS (if configured), AUTH, and MAIL FROM
   - Each matching RCPT TO is forwarded to proxy server
   - Proxy server's response is returned to client
   - Recipients are added to local envelope for tracking

2. **DATA Phase** (ServerData):
   - Email is read and temporarily stored locally
   - Storage processors validate the email
   - If accepted, email data is streamed to proxy server via DATA command
   - Proxy connection is closed after DATA completes
   - Proxy server's response is returned to client

3. **Error Handling**:
   - If proxy connection fails before first recipient, all recipients are rejected with error
   - If proxy connection fails during RCPT, that recipient is rejected
   - If proxy server rejects DATA, client receives the rejection
   - Proxy connection is automatically closed on errors

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
2. **Authentication**: Proxy connections don't currently support authentication (future enhancement)
3. **Trust**: Only proxy to trusted mail servers
4. **Validation**: Storage processors still validate emails before proxying
5. **Logging**: All proxy activity is logged for audit trails

## Performance

The proxy feature:
- Establishes one connection per envelope (multiple recipients share connection)
- Streams email data efficiently without extra buffering
- Closes connections immediately after DATA
- Does not impact non-proxied email performance

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
      host: "partner-mx.example.com",
      port: 25,
      protocol: "esmtp",
      tls: true,
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
      host: "tenant1-mail.example.com",
      port: 25,
      protocol: "esmtp",
      tls: true,
      action: "reject"
    },
    {
      rcpt: ".*@tenant2\\.example\\.com",
      host: "tenant2-mail.example.com",
      port: 25,
      protocol: "esmtp",
      tls: true,
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
      host: "external-relay.example.com",
      port: 587,
      protocol: "esmtp",
      tls: true,
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
- `getRules()`: Returns list of proxy rules

### ProxyMatcher
Utility class for matching emails against proxy rules.

Methods:
- `findMatchingRule(ip, ehlo, mail, rcpt, config)`: Returns first matching rule
- `getAction(rule)`: Gets action for non-matching recipients
- `getHost(rule)`: Gets proxy host
- `getPort(rule)`: Gets proxy port
- `getProtocol(rule)`: Gets proxy protocol
- `isTls(rule)`: Returns true if TLS enabled

### ProxyBehaviour
Client behaviour for proxy connections.

Methods:
- `process(connection)`: Executes EHLO, STARTTLS, AUTH, MAIL FROM
- `sendRcpt(recipient)`: Sends single RCPT TO
- `sendData()`: Sends DATA command and streams email
- `sendQuit()`: Closes connection

### ProxyEmailDelivery
Wrapper for proxy email delivery.

Methods:
- `connect()`: Establishes connection and sends MAIL FROM
- `sendRcpt(recipient)`: Sends RCPT TO for recipient
- `sendData()`: Sends DATA command
- `close()`: Closes connection
- `isConnected()`: Returns connection status

## Future Enhancements

Potential improvements to the proxy feature:
- SMTP authentication support for proxy connections
- Connection pooling for high-volume proxying
- Conditional proxying based on email content
- Metrics and monitoring integration
- Load balancing across multiple proxy servers
