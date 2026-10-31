# DoS Protection Implementation

## Overview

This document describes the DoS (Denial of Service) protection features implemented in Robin MTA to defend against common SMTP server attacks including connection floods, slowloris attacks, command floods, and resource exhaustion.

Listener worker capacity is bounded by `maximumPoolSize`. When all workers are
busy, the newly accepted socket is closed and the overload is logged and counted
as a connection-limit rejection. The acceptor never executes an SMTP session or
TLS handshake itself, and connection-tracking counts are released on rejection.
This capacity bound also applies to whitelisted clients and when optional DoS
controls are disabled.

## Implementation Status

1. **ListenerConfig Extensions** (`ListenerConfig.java`)
   - Added 10 new configuration properties for DoS protection
   - All properties have sensible defaults
   - Can be disabled globally via `dosProtectionEnabled` flag

2. **ConnectionTracker Utility** (`ConnectionTracker.java`)
   - Thread-safe connection state tracking per IP address
   - Automatic cleanup of stale entries every 60 seconds
   - Tracks:
     - Active connections per IP and globally
     - Connection rate history with configurable time windows
     - Command execution frequency
     - Bytes transferred
   - Zero-configuration singleton pattern

3. **SmtpMetrics Extensions** (`SmtpMetrics.java`)
   - Added 5 new Micrometer counters for monitoring:
     - `robin.dos.ratelimit.rejection` - Rate limit violations
     - `robin.dos.connectionlimit.rejection` - Connection limit violations
     - `robin.dos.tarpit` - Tarpit delay applications
     - `robin.dos.slowtransfer.rejection` - Slow transfer disconnections
     - `robin.dos.commandflood.rejection` - Command flood disconnections
   - Integrated with both Prometheus and Graphite registries

4. **SmtpListener Connection Admission Control** (`SmtpListener.java`)
   - Pre-connection validation before thread pool submission
   - Enforces three levels of protection:
     - **Global connection limit** - Total concurrent connections across all IPs
     - **Per-IP connection limit** - Concurrent connections from single IP
     - **Connection rate limiting** - New connections per time window per IP
   - Rejected connections are logged and metered
   - Proper tracking on connect/disconnect for accurate counts

5. **EmailReceipt Command Rate Limiting** (`EmailReceipt.java`)
   - Per-command tracking with ConnectionTracker integration
   - Progressive tarpit delays (1x, 2x, 3x base delay)
   - Three-strikes disconnection policy
   - Commands are tracked before processing to catch floods early

6. **Configuration Files** (`server.json5`)
   - Updated all three listener configs (smtp, secure, submission)
   - Documented all DoS protection parameters with sensible defaults
   - Settings can be tuned per-listener for different threat models

7. **SlowTransferOutputStream** (`SlowTransferOutputStream.java`)
   - Standalone output stream wrapper for detecting slowloris attacks
   - Tracks DATA/BDAT transfer rates in real-time
   - Enforces minimum bytes/second and maximum timeout
   - Checks transfer rate every 5 seconds after 5-second grace period
   - Integrated with ConnectionTracker for metrics

8. **ServerData Slow Transfer Protection** (`ServerData.java`)
   - Integrated SlowTransferOutputStream into DATA command processing
   - Wraps storage client output stream when DoS protection enabled
   - Automatically detects and rejects slow transfers
   - Metrics tracked via SmtpMetrics.incrementDosSlowTransferRejection()

## Configuration Reference

### Authenticated submission limits

The root `submissionLimits` policy is opt-in (`enabled: false` by default) and
independent of listener IP controls. It charges the verified AUTH principal and,
for email-shaped principals, its account domain, not the envelope sender or the
peer IP. Delegated senders cannot evade a principal's budget; clients sharing a
Roundcube proxy or NAT do not share an account budget. Existing listener/IP
limits still apply and must be sized separately for trusted shared proxies.

```json5
submissionLimits: {
  enabled: true,
  windowSeconds: 3600,
  account: {
    messages: 200,
    recipients: 1000,
    bytes: 104857600,
    concurrentConnections: 5,
    authFailures: 20
  },
  domain: {
    messages: 2000,
    recipients: 10000,
    bytes: 1073741824,
    concurrentConnections: 50
  }
}
```

Missing individual budgets or zero mean unlimited, subject to bounded tracker
capacity. Unknown keys, malformed sections, fractional/overflowing/negative
budgets, and nonpositive/overflowing windows fail explicitly; `enabled` must be
a JSON boolean.
Counters use monotonic, per-key windows, not calendar-hour resets. Message
attempts are charged after sender authorization and before MAIL acceptance.
Each RCPT attempt costs at least one recipient; managed aliases additionally
reserve newly expanded recipients before mutating the envelope. Rejected later
processing, RSET, or reconnecting do not refund already charged attempts/bytes.

Both DATA and BDAT charge content bytes before writing accepted chunks, including
blackholed messages, but exclude the server-generated Received header. Byte
quota exhaustion/unavailability emits `451` and closes the connection so unread
content cannot become SMTP commands; no storage processor or delivery accepts
that message. Message limits return `451`, recipient limits `452`, and AUTH
admission limits `454`. Failed passwords are tracked only for the named account,
never the whole domain/IP. Unverified failure tracking has its own bounded map
so arbitrary login names cannot exhaust the verified-submission tracker.

Concurrent sessions are admitted only after password verification and released
on disconnect, independent of message-spool auto-deletion. Window rollover does
not forget still-active sessions. Quotas are process-local, not persistent or
shared across Robin instances; process restart resets rate counters. Both
counter maps and active-session tracking are bounded to 10,000 entries each;
capacity exhaustion defers affected operations rather than silently bypassing
limits. Activate admission limits with a listener restart: already-authenticated
connections are not retroactively admitted. SMTP device credentials, distributed
Redis quotas, and domain-wide failed-login lockouts are not implemented here.

### DoS Protection Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `dosProtectionEnabled` | `true` | Master switch for all DoS protections |
| `maxConnectionsPerIp` | `10` | Maximum concurrent connections from one IP (0=disable) |
| `maxTotalConnections` | `100` | Maximum total concurrent connections (0=disable) |
| `rateLimitWindowSeconds` | `60` | Time window for measuring connection rate |
| `maxConnectionsPerWindow` | `30` | Maximum new connections per IP within window (0=disable) |
| `maxCommandsPerMinute` | `100` | Maximum SMTP commands per connection per minute (0=disable) |
| `minDataRateBytesPerSecond` | `10240` | Minimum DATA/BDAT transfer rate in B/s (0=disable) |
| `maxDataTimeoutSeconds` | `300` | Absolute maximum time for DATA/BDAT commands |
| `tarpitDelayMillis` | `1000` | Base delay for progressive tarpitting (0=disable) |

### Example Configuration

```json5
smtpConfig: {
  // ...existing settings...
  
  // DoS Protection
  dosProtectionEnabled: true,
  maxConnectionsPerIp: 10,
  maxTotalConnections: 100,
  rateLimitWindowSeconds: 60,
  maxConnectionsPerWindow: 30,
  maxCommandsPerMinute: 100,
  minDataRateBytesPerSecond: 10240,
  maxDataTimeoutSeconds: 300,
  tarpitDelayMillis: 1000
}
```

## Protection Mechanisms

### 1. Connection Flood Protection

**Threat**: Attacker opens many concurrent connections to exhaust server resources.

**Defense**:
- Global connection limit prevents total resource exhaustion
- Per-IP connection limit prevents single source from monopolizing resources
- Connections exceeding limits are rejected immediately without entering thread pool
- Metrics track rejection rates for monitoring

**Example Scenario**:
```
- IP 192.168.1.100 attempts 15 connections
- First 10 connections accepted (maxConnectionsPerIp=10)
- Connections 11-15 rejected with metric increment
- Connection rejection logged: "per-IP connection limit reached (10/10 connections)"
```

### 2. Connection Rate Limiting

**Threat**: Attacker rapidly opens and closes connections to exhaust connection handling.

**Defense**:
- Sliding window tracks new connections per IP over time
- Rate limit prevents rapid connection cycling
- History automatically cleaned up to prevent memory growth

**Example Scenario**:
```
- IP 203.0.113.50 opens 35 connections in 60 seconds (maxConnectionsPerWindow=30)
- First 30 connections accepted
- Connections 31-35 rejected with rate limit metric
- Connection rejection logged: "rate limit exceeded (35 connections in 60s window)"
```

### 3. Command Flood Protection

**Threat**: Attacker sends commands rapidly to consume CPU and delay legitimate traffic.

**Defense**:
- Commands tracked per connection per minute
- Progressive tarpit delays slow down attackers:
  - 1st violation: 1000ms delay
  - 2nd violation: 2000ms delay  
  - 3rd violation: Disconnect
- Legitimate clients rarely hit command limits

**Example Scenario**:
```
- Connection sends 120 commands in 60 seconds (maxCommandsPerMinute=100)
- First 100 commands processed normally
- Commands 101-110 trigger 1000ms tarpit delay
- Commands 111-120 trigger 2000ms tarpit delay
- After 3 tarpit violations, connection disconnected with 221 response
```

### 4. Slowloris Protection

**Threat**: Attacker sends DATA/BDAT payload extremely slowly to hold connections open.

**Defense**:
- SlowTransferOutputStream wrapper monitors transfer rate during DATA/BDAT
- Minimum bytes/second enforced (default 10 KB/s)
- Absolute maximum timeout prevents indefinite hangs (default 300s)
- Rate checked every 5 seconds after 5-second grace period
- Integrated with ConnectionTracker to record metrics

**Example Scenario**:
```
- Client sends DATA at 5 KB/s (below 10 KB/s minimum)
- After 5 seconds grace period, rate check begins
- Transfer rate: 5000 bytes in 10s = 500 B/s (too slow)
- Connection terminated with IOException
- Metric: robin_dos_slowtransfer_rejection_total incremented
- Log: "Slow transfer detected for 203.0.113.100: 5000 bytes in 10s (500 B/s, minimum: 10240 B/s)"
```

## Monitoring and Alerting

### Prometheus Metrics

All DoS metrics are exposed at `/metrics` endpoint:

```prometheus
# Connection rejections due to limits
robin_dos_connectionlimit_rejection_total

# Connection rejections due to rate limiting  
robin_dos_ratelimit_rejection_total

# Tarpit delays applied
robin_dos_tarpit_total

# Slow transfer disconnections
robin_dos_slowtransfer_rejection_total

# Command flood disconnections
robin_dos_commandflood_rejection_total
```

### Recommended Alerts

```yaml
# Alert on high connection rejection rate
- alert: HighConnectionRejectionRate
  expr: rate(robin_dos_connectionlimit_rejection_total[5m]) > 10
  annotations:
    summary: "High DoS connection rejection rate detected"

# Alert on rate limiting activity
- alert: RateLimitingActive
  expr: rate(robin_dos_ratelimit_rejection_total[5m]) > 5
  annotations:
    summary: "Rate limiting actively rejecting connections"

# Alert on command flood attacks
- alert: CommandFloodDetected
  expr: increase(robin_dos_commandflood_rejection_total[1m]) > 0
  annotations:
    summary: "Command flood attack detected and mitigated"
```

### Log Messages

DoS protections generate structured log messages:

```
WARN  - Rejecting connection from 192.168.1.100: per-IP connection limit reached (10/10 connections)
WARN  - Rejecting connection from 203.0.113.50: rate limit exceeded (35 connections in 60s window)
WARN  - Command rate limit exceeded for 198.51.100.25: 120 commands/min (limit: 100), violation #1
INFO  - Applying tarpit delay of 1000ms to 198.51.100.25
WARN  - Disconnecting 198.51.100.25 after 3 tarpit violations
```

## Tuning Guidelines

### High Volume Legitimate Traffic

For servers with high legitimate traffic:

```json5
smtpConfig: {
  maxConnectionsPerIp: 50,           // Higher for mail gateways
  maxTotalConnections: 500,          // Scale with available memory
  maxConnectionsPerWindow: 100,      // Allow bursts
  maxCommandsPerMinute: 200,         // Bulk mail senders need more
  tarpitDelayMillis: 500             // Shorter delays
}
```

### Strict Security Posture

For servers under active attack:

```json5
smtpConfig: {
  maxConnectionsPerIp: 3,            // Very restrictive
  maxTotalConnections: 50,           // Limit total exposure
  maxConnectionsPerWindow: 10,       // Slow down attackers
  maxCommandsPerMinute: 50,          // Lower threshold
  tarpitDelayMillis: 5000            // Aggressive tarpitting
}
```

### Testing/Development

For development environments:

```json5
smtpConfig: {
  dosProtectionEnabled: false        // Disable all protections
}
```

## Troubleshooting

### Legitimate Traffic Rejected

**Symptom**: High `robin_dos_connectionlimit_rejection_total` with legitimate clients

**Solution**: Increase `maxConnectionsPerIp` or `maxConnectionsPerWindow`

### Memory Growth

**Symptom**: ConnectionTracker memory increasing over time

**Solution**: Verify cleanup scheduler is running, check for IP tracking leaks

### False Positive Tarpitting

**Symptom**: Legitimate bulk mailers getting tarpitted

**Solution**: Increase `maxCommandsPerMinute` or reduce `tarpitDelayMillis`

## References

- RFC 5321 - Simple Mail Transfer Protocol
- OWASP - DoS Prevention Cheat Sheet
- Robin MTA Documentation: `doc/user/server.md`

## Conclusion

The implemented DoS protections provide multiple layers of defense against common SMTP attacks while maintaining minimal performance impact.
The modular design allows fine-tuning per deployment requirements, and comprehensive metrics enable proactive monitoring and response to attacks.
