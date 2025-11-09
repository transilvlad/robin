Received Header Builder Library
================================

Overview
--------
The ReceivedHeader class provides a builder for creating RFC 5321/5322 compliant email Received headers.
It automatically extracts connection and session information from the Connection instance to build properly formatted
multi-line headers with support for TLS details.

Received headers are essential components of email infrastructure, tracking the path an email takes through
various MTAs (Mail Transfer Agents). Each MTA adds its own Received header to document when and how the message
was received.

Features
--------
- **Automatic Information Extraction** - Pulls HELO/EHLO, IP addresses, hostnames, and protocol details from Connection.
- **TLS Support** - Automatically detects and includes TLS protocol version, cipher suite, and bit strength.
- **Builder Pattern** - Allows manual customization of all header components via setter methods.
- **RFC Compliant** - Generates properly formatted multi-line headers with CRLF line endings.
- **Protocol Detection** - Automatically determines protocol (SMTP/ESMTP/ESMTPS) based on session state.
- **Flexible** - Handles missing optional fields gracefully.

Use Cases
---------
- MTA implementations adding trace headers to emails.
- Email routing and tracking systems.
- SMTP server implementations.
- Email forensics and analysis tools.
- Compliance and audit logging.

Basic Usage
-----------

### Create a Received Header

```java
import com.mimecast.robin.mime.headers.ReceivedHeader;
import com.mimecast.robin.smtp.connection.Connection;

// Assuming you have a Connection instance from an SMTP session.
Connection connection = ...; 

ReceivedHeader header = new ReceivedHeader(connection);
header.setRecipientAddress("user@example.com");

// Get the formatted header string.
String headerString = header.toString();
```

### Add to Email Headers

```java
import com.mimecast.robin.mime.headers.MimeHeaders;

MimeHeaders headers = new MimeHeaders();
headers.put(new ReceivedHeader(connection));

// Continue building email with other headers...
```

Header Components
-----------------

A Received header includes the following components:

1. **From** - HELO/EHLO domain, hostname, and IP address of the sending server.
2. **By** - Hostname of the receiving server (your server).
3. **With** - Protocol used (SMTP, ESMTP, ESMTPS) with optional TLS details.
4. **ID** - Unique message identifier assigned by the receiving server.
5. **For** - Envelope recipient address (optional).
6. **Date/Time** - RFC 5322 formatted timestamp when the message was received.

Output Format
-------------

### Basic Header (without TLS)

```
Received: from mail-relay.sender.com (mail-relay.sender.com [198.51.100.25])
    by my.server.hostname (RobinMTA)
    with ESMTP
    id 1a2b3c4d-5678-90ef-abcd-1234567890ab
    for <user@my-company.com>;
    Sun, 9 Nov 2025 12:09:29 +0000
```

### Header with TLS

```
Received: from mail-relay.sender.com (mail-relay.sender.com [198.51.100.25])
    by my.server.hostname (RobinMTA)
    with ESMTPS (version=TLSv1.3 cipher=TLS_AES_256_GCM_SHA384 bits=256/256)
    id 1a2b3c4d-5678-90ef-abcd-1234567890ab
    for <user@my-company.com>;
    Sun, 9 Nov 2025 12:09:52 +0000
```

Automatic Information Extraction
---------------------------------

When constructed with a Connection instance, ReceivedHeader automatically extracts:

- **HELO/EHLO domain** - From session's EHLO or HELO command.
- **Remote hostname** - Reverse DNS of the connecting IP.
- **Remote IP address** - Socket's remote address.
- **Local hostname** - Your server's hostname.
- **Message ID** - Session's unique identifier (UUID).
- **Protocol** - Determined by EHLO/HELO and TLS status.
- **TLS details** - Protocol version and cipher suite if TLS is negotiated.

Builder Methods
---------------

All builder methods return `this` for method chaining:

### setFromDomain(String fromDomain)

Sets the HELO/EHLO domain name sent by the remote server.

```java
header.setFromDomain("mail.sender.com");
```

### setFromHostname(String fromHostname)

Sets the remote server's hostname (reverse DNS).

```java
header.setFromHostname("mail-relay.sender.com");
```

### setFromIpAddress(String fromIpAddress)

Sets the remote server's IP address.

```java
header.setFromIpAddress("198.51.100.25");
```

### setByHostname(String byHostname)

Sets your server's hostname.

```java
header.setByHostname("my.server.hostname");
```

### setProtocol(String protocol)

Sets the protocol used (SMTP, ESMTP, ESMTPS, ESMTPA, etc.).

```java
header.setProtocol("ESMTPS");
```

### setMessageId(String messageId)

Sets the local message identifier.

```java
header.setMessageId("custom-message-id-123");
```

### setRecipientAddress(String recipientAddress)

Sets the envelope recipient address.

```java
header.setRecipientAddress("user@example.com");
```

Complete Examples
-----------------

### Server-Side SMTP Receiver

```java
import com.mimecast.robin.mime.headers.ReceivedHeader;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.session.Session;

public class SMTPServerExample {
    public void processIncomingEmail(Connection connection) {
        // Create Received header from connection.
        ReceivedHeader header = new ReceivedHeader(connection);
        
        // Set recipient from envelope.
        String recipient = connection.getSession()
                                    .getEnvelopes()
                                    .get(0)
                                    .getRcpt()
                                    .get(0);
        header.setRecipientAddress(recipient);
        
        // Add to email headers.
        String receivedHeader = header.toString();
        
        // Prepend to email (Received headers are added at the top).
        // ... add to email stream ...
    }
}
```

### Custom Server Implementation

```java
public class CustomMTAExample {
    public String buildReceivedHeader(Connection connection) {
        ReceivedHeader header = new ReceivedHeader(connection);
        
        // Override server hostname if needed.
        header.setByHostname("custom.mail.server.com");
        
        // Set recipient.
        header.setRecipientAddress("admin@example.com");
        
        return header.toString();
    }
}
```

### Method Chaining Example

```java
ReceivedHeader header = new ReceivedHeader(connection)
    .setFromDomain("sender.example.com")
    .setByHostname("receiver.example.com")
    .setRecipientAddress("user@receiver.example.com");

String result = header.toString();
```

### Building Without Connection

```java
// Create with null connection for manual setup.
ReceivedHeader header = new ReceivedHeader(null);

header.setFromDomain("mail.sender.com")
      .setFromHostname("mail-relay.sender.com")
      .setFromIpAddress("192.0.2.1")
      .setByHostname("my.server.com")
      .setProtocol("ESMTP")
      .setMessageId(UUID.randomUUID().toString())
      .setRecipientAddress("user@example.com");
```

TLS Details
-----------

When the connection has TLS enabled, ReceivedHeader automatically extracts and includes:

- **Protocol Version** - e.g., TLSv1.2, TLSv1.3.
- **Cipher Suite** - e.g., TLS_AES_256_GCM_SHA384.
- **Bit Strength** - Extracted from cipher name (128, 192, 256 bits).

### TLS Detail Format

```
with ESMTPS (version=TLSv1.3 cipher=TLS_AES_256_GCM_SHA384 bits=256/256)
```

The bit strength is automatically determined from common cipher naming patterns:
- Ciphers containing "256" → bits=256/256
- Ciphers containing "128" → bits=128/128
- Ciphers containing "192" → bits=192/192
- Unknown patterns → bits field omitted

Technical Details
-----------------

### RFC Compliance

The ReceivedHeader class generates headers compliant with:

- **RFC 5321** - Simple Mail Transfer Protocol (SMTP).
- **RFC 5322** - Internet Message Format.

Headers are formatted with proper multi-line structure and CRLF (`\r\n`) line endings as required by these standards.

### Header Folding

The generated header uses multiple lines with 4-space indentation for continuation lines:

```
Received: from domain.com (host.com [192.0.2.1])
    by receiver.com (RobinMTA)
    with ESMTP
    id message-id;
    Date
```

This improves readability while maintaining RFC compliance.

### Protocol Detection

The protocol is automatically determined based on:

1. If TLS is active → ESMTPS
2. If EHLO was used → ESMTP
3. If HELO was used → SMTP
4. Can be manually overridden with `setProtocol()`

### Missing Fields

All fields except the date/time are optional. If a field is not set:

- **From domain** - Header starts with next available component.
- **Hostname/IP** - Parentheses omitted if both missing.
- **Protocol** - "with" clause omitted.
- **Message ID** - "id" clause omitted.
- **Recipient** - "for" clause omitted.

The semicolon before the date is always included when at least one other field is present.

Integration
-----------

### With MimeHeader

ReceivedHeader extends `MimeHeader`, so it can be used anywhere a MimeHeader is expected:

```java
import com.mimecast.robin.mime.headers.MimeHeaders;

MimeHeaders headers = new MimeHeaders();
headers.put(new ReceivedHeader(connection));

// Access as MimeHeader.
Optional<MimeHeader> received = headers.get("Received");
```

### With HeaderWrangler

```java
import com.mimecast.robin.mime.headers.HeaderWrangler;

HeaderWrangler wrangler = new HeaderWrangler();
wrangler.addHeader(new ReceivedHeader(connection));

// Process email with Received header added.
wrangler.process(inputStream, outputStream);
```

### In Email Building

```java
import com.mimecast.robin.mime.EmailBuilder;

EmailBuilder builder = new EmailBuilder(session, envelope);

// Add Received header before other headers.
ReceivedHeader received = new ReceivedHeader(connection);
received.setRecipientAddress(envelope.getRcpt().get(0));

builder.addHeader("Received", received.toString());
builder.addHeader("Subject", "Email Subject");
// ... continue building ...
```

Performance Considerations
--------------------------

- **Lightweight** - ReceivedHeader only extracts string values from the session.
- **No I/O** - All data comes from in-memory session objects.
- **String Building** - Uses StringBuilder for efficient string concatenation.
- **One-Time Extraction** - Information extracted once during construction.

Troubleshooting
---------------

### Header Not Formatted Correctly

**Problem**: Header doesn't match expected format.

**Solution**: Verify Connection and Session contain the expected data. Use builder methods to override extracted values if needed.

### TLS Details Missing

**Problem**: TLS details not included even though connection is encrypted.

**Solution**: Ensure `session.setStartTls(true)` is called and the socket is an SSLSocket. Check that `getProtocol()` and `getCipherSuite()` return non-empty values.

### Missing Date/Time

**Problem**: Date field is missing or incorrect.

**Solution**: The date comes from `session.getDate()` which is set during Session construction. Ensure the Session object is properly initialized.

### Bit Strength Not Detected

**Problem**: Cipher suite is shown but bits field is missing.

**Solution**: The cipher name doesn't match common patterns (128, 192, 256). This is expected for non-standard cipher names. The header is still valid without the bits field.

### Wrong Protocol Shown

**Problem**: Shows ESMTP instead of ESMTPS.

**Solution**: Ensure `session.setStartTls(true)` is called after TLS negotiation completes. Or manually set protocol: `header.setProtocol("ESMTPS")`.

Dependencies
------------

- Java 21 or higher.
- Apache Commons Lang3 (for StringUtils).
- Apache Log4j2 (indirect, through Session and Connection classes).

Testing
-------

Comprehensive unit tests are provided in `ReceivedHeaderTest.java`, covering:

- Basic header generation without TLS.
- Header generation with TLS details.
- HELO vs EHLO handling.
- Optional field handling (missing recipient, IP, etc.).
- Custom values via setter methods.
- Edge cases (null connection, minimal information).
- Bit strength extraction (128, 192, 256 bits).
- Special characters in recipient addresses.
- CRLF line ending verification.
- MimeHeader inheritance.

Run tests with:

```bash
mvn test -Dtest=ReceivedHeaderTest
```

See Also
--------

- [MIME Header Wrangler Library](header-wrangler.md) - Header manipulation and tagging.
- [MIME Email Parsing and Building](mime.md) - Complete MIME library documentation.
- [RFC 5321](https://www.rfc-editor.org/rfc/rfc5321) - Simple Mail Transfer Protocol.
- [RFC 5322](https://www.rfc-editor.org/rfc/rfc5322) - Internet Message Format.
