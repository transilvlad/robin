# Webhook Integration

## Overview

The webhook feature allows you to intercept SMTP extension processing and call external HTTP endpoints before the extension is processed. This enables custom validation, logging, policy enforcement, and integration with external systems.

## Configuration

Webhooks are configured in `webhooks.json5` (or `cfg/webhooks.json5` / `cfg-local/webhooks.json5`). Each SMTP extension can have its own webhook configuration.

### Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `enabled` | boolean | false | Enable/disable webhook for this extension |
| `url` | string | "" | Webhook endpoint URL |
| `method` | string | "POST" | HTTP method (GET, POST, PUT, PATCH, DELETE) |
| `timeout` | number | 5000 | Timeout in milliseconds |
| `waitForResponse` | boolean | true | Wait for webhook response before processing extension |
| `ignoreErrors` | boolean | false | Continue processing even if webhook fails |
| `authType` | string | "none" | Authentication type: "none", "basic", "bearer" |
| `authValue` | string | "" | Authentication credentials (username:password for basic, token for bearer) |
| `includeSession` | boolean | true | Include session data in payload |
| `includeEnvelope` | boolean | true | Include envelope data in payload |
| `includeVerb` | boolean | true | Include verb/command data in payload |
| `headers` | object | {} | Custom HTTP headers |
| `raw` | boolean | false | Enable RAW webhook (DATA extension only) |
| `rawUrl` | string | "" | RAW webhook endpoint URL |
| `rawMethod` | string | "POST" | RAW webhook HTTP method |
| `rawTimeout` | number | 10000 | RAW webhook timeout in milliseconds |
| `rawWaitForResponse` | boolean | false | Wait for RAW webhook response |
| `rawIgnoreErrors` | boolean | true | Continue if RAW webhook fails |
| `rawBase64` | boolean | false | Base64 encode email content |
| `rawAuthType` | string | "none" | RAW webhook authentication type |
| `rawAuthValue` | string | "" | RAW webhook authentication credentials |
| `rawHeaders` | object | {} | RAW webhook custom HTTP headers |

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
    authType: "bearer",
    authValue: "your-api-token-here",
## RAW Webhook (DATA Extension Only)

The DATA extension supports a special **RAW webhook** mode that posts the complete email message as `text/plain` instead of JSON. This is useful for:
- Email archiving systems
- Content analysis services
- Spam/virus scanners
- Email forwarding services

### RAW Webhook Behavior

Unlike normal webhooks that are called **before** extension processing:
- RAW webhooks are called **after** successful DATA/BDAT processing
- The email file must be successfully saved to disk first
- RAW webhooks do not block or interrupt email acceptance
- Typically configured with `rawWaitForResponse: false` for performance

### RAW Webhook Configuration

```json5
{
  "data": {
    // Normal webhook configuration (called BEFORE DATA processing)
    enabled: false,
    url: "http://localhost:8080/webhooks/data",
    
    // RAW webhook configuration (called AFTER successful DATA processing)
    raw: true,
    rawUrl: "http://localhost:8080/webhooks/data/raw",
    rawMethod: "POST",
    rawTimeout: 10000,
    rawWaitForResponse: false,
    rawIgnoreErrors: true,
    rawBase64: false, // Set true to base64 encode content
    rawAuthType: "bearer",
    rawAuthValue: "your-api-token",
    rawHeaders: {
      "X-Email-Processor": "robin-smtp"
    }
  }
}
```

### RAW Webhook Request

**Without Base64 Encoding** (`rawBase64: false`):
```
POST /webhooks/data/raw HTTP/1.1
Host: localhost:8080
Content-Type: text/plain; charset=utf-8
Authorization: Bearer your-api-token
X-Email-Processor: robin-smtp

From: sender@example.com
To: recipient@example.com
Subject: Test Email

This is the raw email content...
```

**With Base64 Encoding** (`rawBase64: true`):
```
POST /webhooks/data/raw HTTP/1.1
Host: localhost:8080
Content-Type: text/plain; charset=utf-8
Content-Transfer-Encoding: base64
Authorization: Bearer your-api-token

RnJvbTogc2VuZGVyQGV4YW1wbGUuY29tClRvOiByZWNpcGllbnRAZXhhbXBsZS5jb20...
```

### RAW Webhook Response

The RAW webhook response does **not** affect email acceptance. The email has already been accepted before the RAW webhook is called. However, the response can be used for:
- Logging/tracking purposes
- Triggering subsequent actions
- Error reporting (if `rawIgnoreErrors: false`)

    includeSession: true,
    includeEnvelope: true,
    includeVerb: true,
    headers: {
      "X-Service": "recipient-validator"
    }
  }
}
```

## Webhook Request

### Request Payload

The webhook receives a JSON payload with the following structure:

```json
{
  "session": {
    "uid": "123e4567-e89b-12d3-a456-426614174000",
    "direction": "INBOUND",
    "addr": "192.168.1.100",
    "friendAddr": "192.168.1.200",
    "authenticated": false,
    "tls": true,
    // ... additional session fields
  },
  "envelopes": [
    {
      "mail": "sender@example.com",
      "rcpts": ["recipient@example.com"],
      // ... additional envelope fields
    }
  ],
  "verb": {
    "command": "RCPT TO:<recipient@example.com>",
    "key": "rcpt",
    "verb": "RCPT"
  }
}
```

### Request Headers

Standard headers sent with each request:
- `Content-Type: application/json`
- `Accept: application/json`
- `Authorization: Basic <credentials>` (if authType is "basic")
- `Authorization: Bearer <token>` (if authType is "bearer")
- Any custom headers specified in the configuration

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

#### Allow with custom message
```json
{
  "smtpResponse": "250 2.1.5 Recipient OK - Verified by external system"
}
```

#### Reject recipient
```json
{
  "smtpResponse": "550 5.7.1 Recipient not authorized"
}
```

#### Temporary failure
```json
{
  "smtpResponse": "451 4.7.1 Greylisting in effect, try again later"
}
```

#### Continue normal processing
Return HTTP 200 with no body or empty JSON `{}` to allow normal extension processing.

## Supported Extensions

The following SMTP extensions support webhooks:

- **ehlo** / **helo** / **lhlo** - Initial connection greeting
- **starttls** - TLS negotiation
- **auth** - Authentication
- **mail** - MAIL FROM (sender)
- **rcpt** - RCPT TO (recipient)
- **data** - Message data transfer
- **bdat** - Binary data transfer (CHUNKING)
- **rset** - Reset transaction
- **help** - Help command
- **quit** - Close connection

### 6. Email Archiving

Archive all incoming emails using RAW webhook:

```json5
{
  "data": {
    enabled: false, // Don't need pre-processing webhook.
    raw: true,
    rawUrl: "http://archive.example.com/store",
    rawWaitForResponse: false,
    rawIgnoreErrors: true,
    rawAuthType: "bearer",
    rawAuthValue: "archive-service-token"
  }
}
```

### 7. Virus Scanning

Send email content to virus scanner:

```json5
{
  "data": {
    enabled: false,
    raw: true,
    rawUrl: "http://antivirus.example.com/scan",
    rawWaitForResponse: true, // Wait for scan result
    rawIgnoreErrors: false, // Log if scan fails
    rawTimeout: 30000, // Allow time for scanning
    rawBase64: true // Some scanners prefer base64
  }
}
```

### 8. Email Forwarding

Forward emails to external system:

```json5
{
  "data": {
    enabled: false,
    raw: true,
    rawUrl: "http://forward.example.com/relay",
    rawWaitForResponse: false,
    rawIgnoreErrors: true,
    rawHeaders: {
      "X-Original-Recipient": "user@example.com"
    }
  }
}
```

## Use Cases

### 1. Recipient Validation

Validate recipients against an external database:

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

Check sender against policy rules:

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

Log SMTP events asynchronously (fire-and-forget):

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

Integrate with external authentication systems:

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

Implement greylisting via webhook:

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
{
  "smtpResponse": "451 4.7.1 Greylisted, please try again in 5 minutes"
}
```

## Security Considerations

1. **Authentication**: Always use authentication (basic or bearer) for production webhooks
2. **HTTPS**: Use HTTPS URLs for webhook endpoints to encrypt data in transit
3. **Timeouts**: Set appropriate timeouts to prevent hanging connections
4. **Error Handling**: Consider using `ignoreErrors: true` for non-critical webhooks

## Performance Tips

1. **Async Processing**: Use `waitForResponse: false` for logging/analytics webhooks
2. **Timeouts**: Keep timeouts low (< 5 seconds) to avoid blocking SMTP connections
3. **Selective Data**: Disable `includeSession`, `includeEnvelope`, or `includeVerb` if not needed
4. **Caching**: Implement caching in webhook endpoints for frequently accessed data
5. **Connection Pooling**: Use HTTP connection pooling on webhook endpoints

## Troubleshooting

### Webhook not being called

1. Check `enabled: true` in configuration
2. Verify webhook URL is accessible
3. Check logs for errors
4. Ensure extension name matches (lowercase: "rcpt", not "RCPT")

### Webhook timing out

1. Increase `timeout` value
2. Optimize webhook endpoint response time
3. Consider using `waitForResponse: false` if response not needed

### Getting "451 Internal server error"

1. Check webhook endpoint is returning 200 status
2. Set `ignoreErrors: true` to continue despite errors
3. Check webhook logs for application errors

### Custom SMTP response not working

1. Ensure response JSON contains `smtpResponse` field
2. Verify SMTP response format (e.g., "250 OK", not just "OK")
3. Check webhook is returning 200 status code
