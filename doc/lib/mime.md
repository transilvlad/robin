MIME Email Parsing and Building Library
======================================

Overview
--------
The EmailParser and EmailBuilder classes form a comprehensive MIME email processing library for Java applications.
These reusable components handle parsing and construction of RFC 2822 compliant email messages with full support for multipart structures,
various content encodings, and attachment handling.

**EmailParser** - Extracts headers, body content, and attachments from existing email files
**EmailBuilder** - Constructs complete RFC 2822 email messages programmatically

This library can be integrated into any Java application requiring email processing: MTA implementations, email clients, testing frameworks,
forensic tools, or notification systems.

Requirements
------------
- Java 16 or higher.
- Apache Commons Codec (for Base64 and Quoted-Printable encoding/decoding).
- Apache Commons Lang3 (for string utilities).
- Apache Log4j2 (for logging).

EmailParser - Parsing Email Messages
====================================

The EmailParser class parses RFC 2822 formatted email messages and extracts all headers, body content,
and attachments with automatic decoding and integrity verification.

**Resource Management**: EmailParser implements `AutoCloseable` and automatically cleans up temporary files 
created for MIME parts. Always use try-with-resources or manually call `close()` after processing.

### Supported Features

- **Multi-line Header Folding** - Properly unfolds RFC 2822 folded headers.
- **Multipart Messages** - Parses multipart/mixed, multipart/related, multipart/alternative.
- **Nested Structures** - Handles deeply nested multipart hierarchies.
- **Content Encodings** - Automatically decodes Base64, Quoted-Printable, and plain text.
- **Embedded Messages** - Supports message/rfc822 embedded email messages.
- **Content Hashing** - Calculates SHA-1, SHA-256, and MD5 hashes for integrity verification.
- **Attachment Detection** - Classifies and identifies attachments by MIME type.
- **Automatic Cleanup** - Temporary part files are automatically deleted when parser is closed.

### Content and structure contracts

The result is a flat, wire-order list, not a MIME tree or a display-body selection.
Multipart wrappers have headers but no body stream; do not call `getBytes()` on them.
Nested `message/rfc822` members include a file-backed container **before** their
existing flattened descendants. The container retains the decoded enclosed message,
including its original headers, and its own attachment/CID metadata. Account for
these containers when matching parts by index or selecting files for scanning.
Top-level encapsulated messages may remain opaque. Expansion is limited to ten
nesting levels; deeper structures remain opaque.

Untyped roots and ordinary children default to `text/plain`; untyped direct
`multipart/digest` children default to `message/rfc822`. Empty implicit trailing
digest entries are not attachments, while explicit empty attachment metadata is
retained. Effective defaults appear in part metadata, not fabricated wire headers.
Full parsing moves root Content-* headers into root part metadata; headers-only
parsing retains them in the message header collection.

The first empty physical line terminates headers, even after a semicolon.
Folding is accepted before that separator, not repaired across it. Multipart
preambles/epilogues are ignored. Delimiters start at column zero and may have
trailing ASCII spaces/tabs; indented boundary-like lines remain body content.
Media types are compared case-insensitively without changing boundary or
filename values. A multipart lacking its boundary parameter is retained opaquely
with a warning rather than inferring a parameter from its body.

Header framing accepts LF and CRLF, including unambiguous mixed line endings.
Standalone CR, ASCII controls other than folding TAB and line endings, and DEL
in a header section throw `HeaderFraming.InvalidHeaderException` (an `IOException`).
UTF-8 bytes are not interpreted as ASCII controls. Body bytes are opaque to this
check; validation does not normalize or repair raw messages. Unambiguous
header-only input ending at EOF remains supported.

`hasValidHeaders()` is true only after a successful parse; it is not full RFC
semantic validation. Headers exposed after a failed or incomplete parse are
diagnostic evidence only, not an authorized identity. Duplicate fields remain
ordered and available through `getAll()`.

`getBytes()`, size, and hashes describe transfer-decoded bytes, independently of
text conversion. The parser retains boundary-adjacent line endings in part bytes.
Quoted-printable accepts both CRLF and LF soft breaks while preserving unrelated
hard line breaks. Invalid quoted-printable produces a warning and retains the
complete encoded payload; this is recovery, not successful transfer decoding.

`TextMimePart.getContent()` honors a supported declared charset when strict
conversion succeeds. Missing, unsupported, or unusable declarations use charset
detection (with a warning for unusable declarations), then the platform default
if detection has no result. A usable declaration wins even when another charset
would produce more plausible text. This does not change `getBytes()` or hashes.

`MimeHeaders.getFileName()` returns a supplied name or null, preferring
Content-Disposition over Content-Type and plain parameters over extended ones.
Single-segment RFC 2231 values are decoded; numbered continuations are not supported.

### Part ownership

`getParts()` is borrowed from the parser: consume file-backed parts before closing
it. `FileMimePart` reads are repeatable, including beyond 8 KiB. Ordinary file/path
constructors borrow their source file and never delete it on close.
`FileMimePart.temporary(file)` transfers file ownership; closing it deletes the file.
Reads after close fail. A failed deletion is reported and may be retried by closing
again. Successful deletion or an already absent path ends ownership; later closes
do not delete a replacement file created at the same path.

To keep parsed parts after closing the parser, explicitly transfer ownership:

```java
List<MimePart> parts;
try (EmailParser parser = new EmailParser("message.eml")) {
    parser.parse();
    parts = parser.detachParts();
}
try {
    // Read parts; multipart wrappers have no payload.
} finally {
    IOException failure = null;
    for (MimePart part : parts) {
        if (part instanceof FileMimePart filePart) {
            try {
                filePart.close();
            } catch (IOException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
    }
    if (failure != null) throw failure;
}
```

`detachParts()` requires a completed full parse and an open ownership scope.
It clears the parser's list; subsequent calls return an empty list. The caller
must close every returned file part, including enclosed-message containers.
If cleanup can fail, ensure the caller still attempts to close all remaining
parts rather than stopping at the first failure.

### Basic Usage

#### Parse Email from File

EmailParser implements `AutoCloseable` to ensure proper cleanup of temporary files created for MIME parts.
When parsing completes, temporary attachment files are automatically deleted via the `close()` method.

```java
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.headers.MimeHeaders;
import com.mimecast.robin.mime.parts.MimePart;
import com.mimecast.robin.mime.parts.FileMimePart;
import java.io.IOException;
import java.util.List;

try (EmailParser parser = new EmailParser("/path/to/email.eml")) {
    // Parse the complete email (headers and body).
    parser.parse();
    
    // Extract parsed data
    MimeHeaders headers = parser.getHeaders();
    List<MimePart> parts = parser.getParts();
    
    // Process parts here - temporary files are available until close()
    
} catch (IOException e) {
    e.printStackTrace();
}
// Temporary part files are automatically cleaned up here
```

**Note**: If you don't use try-with-resources, you must manually call `parser.close()` to clean up temporary files.

#### Parse Headers Only

For quick header extraction without reading the entire message body:

```java
try (EmailParser parser = new EmailParser("/path/to/email.eml")) {
    parser.parse(true);  // true = headers only.
    
    MimeHeaders headers = parser.getHeaders();
}
```

#### Custom Buffer Size

For very complex multipart messages with large boundaries, use a larger buffer:

```java
// Default buffer size is 1024 bytes.
try (EmailParser parser = new EmailParser("/path/to/email.eml", 4096)) {
    parser.parse();
}
```

#### Parse from Stream

For parsing email content from network streams or memory buffers:

```java
import com.mimecast.robin.smtp.io.LineInputStream;
import java.io.ByteArrayInputStream;

byte[] emailContent = ...;  // Your email content.
LineInputStream stream = new LineInputStream(
    new ByteArrayInputStream(emailContent), 
    1024
);
try (EmailParser parser = new EmailParser(stream)) {
    parser.parse();
}
```

### Working with Parsed Headers

```java
import com.mimecast.robin.mime.headers.MimeHeader;
import java.util.Optional;

MimeHeaders headers = parser.getHeaders();

// Get a single header value.
Optional<MimeHeader> subject = headers.get("Subject");
if (subject.isPresent()) {
    System.out.println("Subject: " + subject.get().getValue());
}

// Get header parameter (e.g., charset from Content-Type).
Optional<MimeHeader> contentType = headers.get("Content-Type");
if (contentType.isPresent()) {
    String charset = contentType.get().getParameter("charset");
    String boundary = contentType.get().getParameter("boundary");
}

// Iterate all headers.
headers.get().forEach(header -> {
    System.out.println(header.getName() + ": " + header.getValue());
});

// Find headers starting with prefix (case-insensitive).
headers.startsWith("x-custom-").forEach(header -> {
    System.out.println("Custom header: " + header.getValue());
});
```

### Working with Parsed Parts

```java
import com.mimecast.robin.mime.parts.MimePart;
import com.mimecast.robin.mime.parts.TextMimePart;
import com.mimecast.robin.mime.parts.MultipartMimePart;

List<MimePart> parts = parser.getParts();

for (MimePart part : parts) {
    // Get part metadata.
    long size = part.getSize();
    if (part instanceof MultipartMimePart) {
        continue; // Structural wrapper, not payload.
    }
    byte[] content = part.getBytes();
    
    // Get content type.
    MimeHeader contentType = part.getHeader("Content-Type");
    if (contentType != null) {
        String mimeType = contentType.getCleanValue();
        System.out.println("MIME Type: " + mimeType);
    }
    
    // Get content hashes (for integrity verification).
    String sha1 = part.getHash(HashType.SHA_1);
    String sha256 = part.getHash(HashType.SHA_256);
    String md5 = part.getHash(HashType.MD_5);
    
    // Check if this is a text part.
    if (part instanceof TextMimePart) {
        TextMimePart textPart = (TextMimePart) part;
        String text = textPart.getContent();
        System.out.println("Text: " + text);
    }
    
    // Get part headers.
    part.getHeaders().get().forEach(h -> 
        System.out.println(h.getName() + ": " + h.getValue())
    );
}
```

### Complete Parsing Example

```java
import com.mimecast.robin.mime.EmailParser;
import com.mimecast.robin.mime.headers.MimeHeader;
import com.mimecast.robin.mime.parts.MimePart;
import java.io.IOException;
import java.util.Optional;

public class EmailParsingExample {
    public static void main(String[] args) {
        try (EmailParser parser = new EmailParser("email.eml")) {
            parser.parse();
            
            // Display headers.
            System.out.println("=== EMAIL HEADERS ===");
            Optional<MimeHeader> from = parser.getHeaders().get("From");
            Optional<MimeHeader> to = parser.getHeaders().get("To");
            Optional<MimeHeader> subject = parser.getHeaders().get("Subject");
            Optional<MimeHeader> date = parser.getHeaders().get("Date");
            
            from.ifPresent(h -> System.out.println("From: " + h.getValue()));
            to.ifPresent(h -> System.out.println("To: " + h.getValue()));
            subject.ifPresent(h -> System.out.println("Subject: " + h.getValue()));
            date.ifPresent(h -> System.out.println("Date: " + h.getValue()));
            
            // Display message structure.
            System.out.println("\n=== MESSAGE PARTS ===");
            System.out.println("Total parts: " + parser.getParts().size());
            
            for (int i = 0; i < parser.getParts().size(); i++) {
                MimePart part = parser.getParts().get(i);
                MimeHeader ct = part.getHeader("Content-Type");
                
                System.out.println("\nPart " + (i + 1) + ":");
                System.out.println("  Size: " + part.getSize() + " bytes");
                if (ct != null) {
                    System.out.println("  Type: " + ct.getCleanValue());
                }
                System.out.println("  SHA-256: " + part.getHash(HashType.SHA_256));
            }
            
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
```

EmailBuilder - Building Email Messages
=====================================

The EmailBuilder class constructs complete RFC 2822 compliant email messages programmatically with fluent API support for method chaining.

### Features

- **Fluent API** - Method chaining for convenient message construction.
- **Automatic Headers** - Generates Date, Message-ID, From, To, Subject if not provided.
- **Part Categorization** - Automatically organizes parts into mixed, related, and alternative.
- **Header Encoding** - RFC 2047 encoded-word encoding for non-ASCII headers.
- **Multipart Nesting** - Proper RFC 2046 hierarchy: mixed > related > alternative.
- **Magic Token Replacement** - Variable substitution in headers and content.

### Part Categories

The builder categorizes MIME parts into three types that are nested according to RFC standards:

1. **Alternative** - Multiple representations of same content (text/plain, text/html).
2. **Related** - Inline content referenced by other parts (images, stylesheets).
3. **Mixed** - Attachments and unrelated content.

The resulting message structure: `multipart/mixed > multipart/related > multipart/alternative`

### Basic Usage

#### Create a Simple Email

```java
import com.mimecast.robin.mime.EmailBuilder;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

// Create session and envelope.
Session session = new Session();
MessageEnvelope envelope = new MessageEnvelope();

// Set sender and recipients.
envelope.setMail("sender@example.com");
envelope.addRcpt("recipient@example.com");

// Create builder.
EmailBuilder builder = new EmailBuilder(session, envelope);

// Add headers.
builder.addHeader("Subject", "Test Email")
       .addHeader("From", "sender@example.com");

// Generate output.
ByteArrayOutputStream output = new ByteArrayOutputStream();
builder.writeTo(output);

// Get the complete RFC 2822 message.
String message = output.toString();
System.out.println(message);
```

#### Add Custom Headers

Headers with non-ASCII characters are automatically encoded using RFC 2047:

```java
builder.addHeader("Subject", "Test with Unicode: 日本語")
       .addHeader("X-Custom-Header", "Custom Value")
       .addHeader("X-Priority", "1");
```

#### Build MIME from Configuration

If using MessageEnvelope with MIME configuration:

```java
builder.buildMime();  // Processes all MIME parts from envelope.

builder.writeTo(output);
```

#### Add MIME Parts

```java
import com.mimecast.robin.mime.parts.TextMimePart;
import com.mimecast.robin.mime.parts.FileMimePart;
import com.mimecast.robin.mime.headers.MimeHeader;

// Add text part (automatically categorized as alternative).
TextMimePart textPart = new TextMimePart("Hello, World!".getBytes());
textPart.addHeader("Content-Type", "text/plain; charset=UTF-8");
builder.addPart(textPart);

// Add HTML part (automatically categorized as alternative).
TextMimePart htmlPart = new TextMimePart("<html><body>Hello, World!</body></html>".getBytes());
htmlPart.addHeader("Content-Type", "text/html; charset=UTF-8");
builder.addPart(htmlPart);

// Add attachment (automatically categorized as mixed).
FileMimePart attachment = new FileMimePart();
attachment.addHeader("Content-Type", "application/pdf");
attachment.addHeader("Content-Disposition", "attachment; filename=\"document.pdf\"");
builder.addPart(attachment);

// Add inline image (automatically categorized as related with Content-ID).
FileMimePart image = new FileMimePart();
image.addHeader("Content-Type", "image/png");
image.addHeader("Content-ID", "<image1@example.com>");
image.addHeader("Content-Disposition", "inline");
builder.addPart(image);
```

### Complete Building Example

```java
import com.mimecast.robin.mime.EmailBuilder;
import com.mimecast.robin.mime.parts.TextMimePart;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class EmailBuildingExample {
    public static void main(String[] args) throws IOException {
        // Setup
        Session session = new Session();
        MessageEnvelope envelope = new MessageEnvelope();
        envelope.setMail("sender@example.com");
        envelope.addRcpt("recipient@example.com");
        
        // Build email.
        EmailBuilder builder = new EmailBuilder(session, envelope);
        
        // Add headers.
        builder.addHeader("Subject", "Meeting Confirmation")
               .addHeader("From", "sender@example.com")
               .addHeader("To", "recipient@example.com")
               .addHeader("X-Priority", "1")
               .addHeader("X-Custom-Tracking", "MSG-12345");
        
        // Add text version.
        TextMimePart textPart = new TextMimePart(
            "Dear Recipient,\n\nThis is the text version of the email.\n\nBest regards,\nSender"
            .getBytes()
        );
        textPart.addHeader("Content-Type", "text/plain; charset=UTF-8");
        textPart.addHeader("Content-Transfer-Encoding", "8bit");
        builder.addPart(textPart);
        
        // Add HTML version.
        TextMimePart htmlPart = new TextMimePart(
            "<html><body><p>Dear Recipient,</p>" +
            "<p>This is the HTML version of the email.</p>" +
            "<p>Best regards,<br>Sender</p></body></html>"
            .getBytes()
        );
        htmlPart.addHeader("Content-Type", "text/html; charset=UTF-8");
        htmlPart.addHeader("Content-Transfer-Encoding", "8bit");
        builder.addPart(htmlPart);
        
        // Write to output.
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        builder.writeTo(output);
        
        // Display generated message.
        String message = output.toString();
        System.out.println(message);
    }
}
```

Error Handling
--------------

### Parsing Errors

```java
try (EmailParser parser = new EmailParser("/path/to/email.eml")) {
    parser.parse();
    // Process parsed data here
} catch (FileNotFoundException e) {
    System.err.println("Email file not found: " + e.getMessage());
} catch (IOException e) {
    System.err.println("Error reading email: " + e.getMessage());
}
```

### Building Errors

```java
try {
    EmailBuilder builder = new EmailBuilder(session, envelope);
    builder.addHeader("Subject", "Test");
    
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    builder.writeTo(output);
} catch (IOException e) {
    System.err.println("Error writing email: " + e.getMessage());
}
```

### Encoding Issues

The EmailBuilder handles encoding issues gracefully:

- If RFC 2047 encoding fails, headers are added with folded newlines.
- Quoted-Printable decoding errors are logged and content is used as-is.
- Base64 decoding errors result in appropriate error logging.

### Performance Considerations

- **Large Files** - EmailParser reads files line-by-line, suitable for large messages.
- **Memory** - Parts are loaded entirely into memory; for very large attachments, consider streaming implementations.
- **Temporary Files** - FileMimePart instances create temporary files on disk that are automatically deleted when the parser is closed.
- **Buffer Size** - Adjust pushback buffer size for optimal performance with your message types.
- **Hashing** - Cryptographic hashing adds slight overhead but provides integrity verification.

Advanced Usage
--------------

### Processing Multipart Messages

```java
for (MimePart part : parser.getParts()) {
    MimeHeader contentType = part.getHeader("Content-Type");
    
    if (contentType != null && contentType.getValue().startsWith("multipart/")) {
        System.out.println("Multipart structure: " + contentType.getValue());
        // Handle nested multipart recursively.
    }
}
```

### Extracting and Saving Attachments

```java
import java.io.FileOutputStream;

for (MimePart part : parser.getParts()) {
    MimeHeader cd = part.getHeader("Content-Disposition");
    
    if (cd != null && cd.getCleanValue().startsWith("attachment")) {
        String filename = cd.getParameter("filename");
        if (filename != null) {
            FileOutputStream fos = new FileOutputStream(filename);
            fos.write(part.getBytes());
            fos.close();
            System.out.println("Saved: " + filename);
        }
    }
}
```

### Content Integrity Verification

```java
for (MimePart part : parser.getParts()) {
    String sha256 = part.getHash(HashType.SHA_256);
    System.out.println("Part SHA-256: " + sha256);
    
    // Compare with known value.
    if (sha256.equals(expectedHash)) {
        System.out.println("Content verified!");
    }
}
```

### Composing Complex Multipart Messages

```java
// Build message with text, HTML, and inline images.
EmailBuilder builder = new EmailBuilder(session, envelope);

// Text alternative.
TextMimePart text = new TextMimePart(textContent.getBytes());
text.addHeader("Content-Type", "text/plain; charset=UTF-8");
builder.addPart(text);

// HTML alternative with embedded images.
TextMimePart html = new TextMimePart(htmlContent.getBytes());
html.addHeader("Content-Type", "text/html; charset=UTF-8");
builder.addPart(html);

// Inline logo (related part).
FileMimePart logo = new FileMimePart();
logo.addHeader("Content-Type", "image/png");
logo.addHeader("Content-ID", "<logo@company.com>");
logo.addHeader("Content-Disposition", "inline");
builder.addPart(logo);

// PDF attachment (mixed part).
FileMimePart pdf = new FileMimePart();
pdf.addHeader("Content-Type", "application/pdf");
pdf.addHeader("Content-Disposition", "attachment; filename=\"report.pdf\"");
builder.addPart(pdf);

// Generate final message.
ByteArrayOutputStream output = new ByteArrayOutputStream();
builder.writeTo(output);
```

Troubleshooting
---------------

### Parser Issues

**Problem**: "NoSuchAlgorithmException" for hash algorithms.
- **Cause**: Missing security provider.
- **Solution**: Ensure Java security providers are properly configured.

**Problem**: Boundary not found, parts not parsed.
- **Cause**: Incorrect boundary in Content-Type header.
- **Solution**: Check email file integrity; try parsing with headers only first.

**Problem**: Encoding issues with non-ASCII characters.
- **Cause**: Character set mismatch.
- **Solution**: Verify Content-Type charset parameter; ensure proper Java file encoding.

### Builder Issues

**Problem**: "UnsupportedEncodingException" when adding headers.
- **Cause**: Unsupported character encoding.
- **Solution**: Use UTF-8 charset in headers; builder will encode automatically.

**Problem**: Message structure is incorrect.
- **Cause**: Parts not categorized correctly.
- **Solution**: Manually assign parts to correct category or use Content-ID/Content-Disposition headers.

**Problem**: Large headers cause parsing failures.
- **Cause**: Default buffer too small.
- **Solution**: Use custom buffer size constructor for EmailParser.
