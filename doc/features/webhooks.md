# Webhook Integration

## Overview

The webhook feature allows you to intercept SMTP extension processing and call external HTTP endpoints before the extension is processed.
This enables custom validation, logging, policy enforcement, and integration with external systems.

## Configuration

Webhooks are configured in `cfg/webhooks.json5`. Each SMTP extension can have its own webhook configuration.

### Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `enabled` | boolean | false | Enable/disable webhook for this extension |
| `url` | string | "" | Webhook endpoint URL |
| `method` | string | "POST" | HTTP method (GET, POST, PUT, PATCH, DELETE) |
| `timeout` | number | 5000 | Timeout in milliseconds |
| `waitForResponse` | boolean | true | Wait for webhook response before processing extension |
| `ignoreErrors` | boolean | false | Continue processing even if webhook fails |
| `direction` | string | "both" | Direction filter: "inbound", "outbound", or "both" |
| `authType` | string | "none" | Authentication type: "none", "basic", "bearer" |
| `authValue` | string | "" | Authentication credentials (username:password for basic, token for bearer) |
| `includeSession` | boolean | true | Include session data in payload |
| `includeEnvelope` | boolean | true | Include envelope data in payload |
| `includeVerb` | boolean | true | Include verb/command data in payload |
| `headers` | object | {} | Custom HTTP headers |

## RAW Webhook (DATA/BDAT Post-Processing)

The RAW webhook posts the complete email message as `text/plain` after a successful DATA/BDAT transfer and file save. It's ideal for:
- Email archiving systems
- Content analysis services
- Spam/virus scanners
- Email forwarding services

RAW is configured in its own top-level section named `raw`.
This section uses the same standard webhook options as above, plus one extra option for content encoding.
Given the RAW nature of this webhook, it does not include session/envelope/verb data.

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `enabled` | boolean | false | Enable RAW webhook |
| `url` | string | "" | RAW webhook endpoint URL |
| `method` | string | "POST" | RAW webhook HTTP method |
| `timeout` | number | 10000 | RAW webhook timeout in milliseconds |
| `waitForResponse` | boolean | false | Wait for RAW webhook response |
| `ignoreErrors` | boolean | true | Continue if RAW webhook fails |
| `direction` | string | "both" | Direction filter: "inbound", "outbound", or "both" |
| `base64` | boolean | false | Base64 encode email content |
| `authType` | string | "none" | RAW webhook authentication type |
| `authValue` | string | "" | RAW webhook authentication credentials |
| `headers` | object | {} | RAW webhook custom HTTP headers |

### Example Configuration

```json5
{
  "rcpt": {
    enabled: true,
    url: "http://localhost:8080/webhooks/rcpt",
    method: "POST",
    timeout: 5000,
    waitForResponse: true,
    ignoreErrors: false,
    direction: "both",
    authType: "bearer",
    authValue: "your-api-token-here",
    includeSession: true,
    includeEnvelope: true,
    includeVerb: true,
    headers: {
      "X-Service": "recipient-validator"
    }
  },
  "data": {
    enabled: false,
    url: "http://localhost:8080/webhooks/data",
    method: "POST",
    timeout: 10000,
    waitForResponse: true
  },
  "bdat": {
    enabled: false
  },
  // RAW webhook configuration (called AFTER successful DATA/BDAT).
  "raw": {
    enabled: true,
    url: "http://localhost:8080/webhooks/data/raw",
    method: "POST",
    timeout: 10000,
    waitForResponse: false, // Fire and forget for performance.
    ignoreErrors: true, // Don't fail email delivery if webhook fails.
    base64: false, // Set true to base64 encode content.
    authType: "bearer",
    authValue: "your-api-token",
    headers: {
      "X-Email-Processor": "robin-smtp"
    }
  }
}
```

### RAW Webhook Request

Without Base64 Encoding (`base64: false`):

```
POST /webhooks/data/raw HTTP/1.1
Host: localhost:8080
Content-Type: text/plain; charset=utf-8
Authorization: Bearer your-api-token
X-Email-Processor: robin-smtp
Hostname: mail.example.com
Direction: INBOUND
UID: 1234567890abcdef
TLS: true
EHLO: client.example.com
Username: authenticated-user
SenderIP: 192.168.1.100
SenderRDNS: client.example.com
Sender: tony@example.com
Recipients: pepper@example.com,happy@example.com

From: tony@example.com
To: pepper@example.com, happy@example.com
Subject: Test Email

This is the raw email content...
```

With Base64 Encoding (`base64: true`):

```
POST /webhooks/data/raw HTTP/1.1
Host: localhost:8080
Content-Type: text/plain; charset=utf-8
Content-Transfer-Encoding: base64
Authorization: Bearer your-api-token
X-Email-Processor: robin-smtp
Hostname: mail.example.com
Direction: INBOUND
UID: 1234567890abcdef
TLS: true
EHLO: client.example.com
Username: authenticated-user
SenderIP: 192.168.1.100
SenderRDNS: client.example.com
Sender: tony@example.com
Recipients: pepper@example.com,happy@example.com

RnJvbTogc2VuZGVyQGV4YW1wbGUuY29tClRvOiByZWNpcGllbnRAZXhhbXBsZS5jb20...
```

### RAW Webhook Response

The RAW webhook response does not affect email acceptance. The email has already been accepted before the RAW webhook is called.
However, the response can be used for:
- Logging/tracking purposes
- Triggering subsequent actions
- Error reporting (if `ignoreErrors: false`)

## Direction Filtering

The `direction` option allows you to filter webhooks based on the SMTP session direction. This is useful when you need different handling for:
- **Inbound** emails (external senders to your server)
- **Outbound** emails (your server to external recipients)

### Direction Values

| Value | Description |
|-------|-------------|
| `"inbound"` | Webhook fires only for incoming SMTP connections |
| `"outbound"` | Webhook fires only for outgoing SMTP connections |
| `"both"` (default) | Webhook fires for all connections |

### Examples

Only validate recipients on inbound connections:
```json5
{
  "rcpt": {
    enabled: true,
    url: "http://localhost:8080/webhooks/rcpt",
    direction: "inbound", // Only for incoming emails.
    waitForResponse: true,
    ignoreErrors: false
  }
}
```

Log all outbound emails to archive:
```json5
{
  "raw": {
    enabled: true,
    url: "http://archive.example.com/store",
    direction: "outbound", // Only for outgoing emails.
    waitForResponse: false,
    ignoreErrors: true
  }
}
```

Webhook Response

The RAW webhook response does not affect email acceptance. The email has already been accepted before the RAW webhook is called.
However, the response can be used for:
- Logging/tracking purposes
- Triggering subsequent actions
- Error reporting (if `ignoreErrors: false`)

## Webhook Request

### Request Payload

The standard webhooks receive a JSON payload with the following structure:

```json
{
  "session": { /* ... session fields ... */ },
  "envelope": { /* ... envelope fields ... */ },
  "verb": {
    "command": "RCPT TO:<recipient@example.com>",
    "key": "rcpt",
    "verb": "RCPT"
  }
}
```

### Request Headers

#### Session Headers (All Webhooks)

The following session-related headers are automatically added to **all webhook requests** (both standard and RAW):

- `Hostname` - Server hostname from configuration
- `Direction` - Session direction (INBOUND/OUTBOUND)
- `UID` - Unique session identifier
- `TLS` - Whether STARTTLS was negotiated (true/false)
- `HELO` - HELO command value (if present)
- `EHLO` - EHLO command value (if present)  
- `LHLO` - LHLO command value (if present)
- `Secure` - Whether connection uses secure port (only if true)
- `Username` - Authenticated username (if present)
- `SenderIP` - Client IP address
- `SenderRDNS` - Client reverse DNS

#### Standard Webhook Headers

Additional headers sent with standard (JSON) webhooks:
- `Content-Type: application/json`
- `Accept: application/json`
- `Authorization: Basic <credentials>` (if authType is "basic")
- `Authorization: Bearer <token>` (if authType is "bearer")
- Any custom headers specified in the configuration

#### RAW Webhook Headers

Additional headers sent with RAW webhooks:
- `Content-Type: text/plain; charset=utf-8`
- `Content-Transfer-Encoding: base64` (only if `base64: true`)
- `Sender` - MAIL FROM address from the envelope
- `Recipients` - Comma-separated list of RCPT TO addresses
- Same authentication and custom headers as above

## Webhook Response

### Response Handling

The webhook's HTTP response code determines how processing continues:

| Status Code | Behavior |
|-------------|----------|
| 200-299 (Success) | Continue with normal extension processing |
| 4xx-5xx (Error) | Return "451 4.3.2 Internal server error" to SMTP client (unless `ignoreErrors: true`) |

### Custom SMTP Response

To override the default SMTP response, your webhook can return a JSON payload with a `smtpResponse` field:

```json
{
  "smtpResponse": "550 5.7.1 Recipient rejected by policy"
}
```

When a custom SMTP response is provided:
- The response is sent to the SMTP client
- The normal extension processing is skipped
- The SMTP conversation continues (unless the response code indicates closure)

### Response Examples

- Allow with custom message: `{ "smtpResponse": "250 2.1.5 Recipient OK - Verified by external system" }`
- Reject recipient: `{ "smtpResponse": "550 5.7.1 Recipient not authorized" }`
- Temporary failure: `{ "smtpResponse": "451 4.7.1 Greylisting in effect, try again later" }`
- Continue normal processing: HTTP 200 with no body or empty JSON `{}`

## Supported Extensions

The following SMTP extensions support webhooks:

- ehlo / helo / lhlo - Initial connection greeting
- starttls - TLS negotiation
- auth - Authentication
- mail - MAIL FROM (sender)
- rcpt - RCPT TO (recipient)
- data - Message data transfer
- bdat - Binary data transfer (CHUNKING)
- raw - Raw message content delivery (post-processing)
- rset - Reset transaction
- help - Help command
- quit - Close connection

## Use Cases

### 1. Recipient Validation

```json5
{
  "rcpt": {
    enabled: true,
    url: "http://api.example.com/validate/recipient",
    waitForResponse: true,
    ignoreErrors: false
  }
}
```

### 2. Sender Policy Enforcement

```json5
{
  "mail": {
    enabled: true,
    url: "http://policy.example.com/check/sender",
    waitForResponse: true,
    ignoreErrors: false
  }
}
```

### 3. Logging and Analytics

```json5
{
  "data": {
    enabled: true,
    url: "http://analytics.example.com/smtp/event",
    waitForResponse: false,
    ignoreErrors: true
  }
}
```

### 4. Custom Authentication

```json5
{
  "auth": {
    enabled: true,
    url: "http://auth.example.com/validate",
    waitForResponse: true,
    ignoreErrors: false,
    authType: "bearer",
    authValue: "service-to-service-token"
  }
}
```

### 5. Greylisting

```json5
{
  "rcpt": {
    enabled: true,
    url: "http://greylist.example.com/check",
    timeout: 2000,
    waitForResponse: true,
    ignoreErrors: false
  }
}
```

Webhook returns:
```json
{ "smtpResponse": "451 4.7.1 Greylisted, please try again in 5 minutes" }
```

### 6. Email Archiving

```json5
{
  "raw": {
    enabled: true,
    url: "http://archive.example.com/store",
    waitForResponse: false,
    ignoreErrors: true,
    authType: "bearer",
    authValue: "archive-service-token"
  }
}
```

### 7. Virus Scanning

```json5
{
  "raw": {
    enabled: true,
    url: "http://antivirus.example.com/scan",
    waitForResponse: true, // Wait for scan result
    ignoreErrors: false, // Log if scan fails
    timeout: 30000, // Allow time for scanning
    base64: true // Some scanners prefer base64
  }
}
```

### 8. Email Forwarding

```json5
{
  "raw": {
    enabled: true,
    url: "http://forward.example.com/relay",
    waitForResponse: false,
    ignoreErrors: true,
    headers: {
      "X-Original-Recipient": "user@example.com"
    }
  }
}
```
