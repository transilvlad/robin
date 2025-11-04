MIME Header Wrangler Library
============================

Overview
--------
The HeaderWrangler class provides a powerful and flexible way to manipulate MIME email headers by injecting tags into header values and appending new headers.
This library is designed to process raw email bytes and can be used for spam filtering, email classification, or any scenario requiring header modification.

Features
--------
- **Header Tagging** - Prepend tags like `[SPAM]` or `[SUSPICIOUS]` to any header value.
- **Header Injection** - Add new custom headers after existing headers, right before the email body.
- **Encoding-Aware** - Automatically handles RFC 2047 encoded headers, preserving encoding integrity.
- **Multi-line Support** - Properly processes folded headers that span multiple lines.
- **RFC 5322 Compliant** - Ensures proper header folding for long values.
- **Byte-Level Processing** - Works directly with email byte arrays for maximum compatibility.

Use Cases
---------
- Email spam and phishing detection tagging.
- Adding tracking or classification headers.
- Email routing and filtering systems.
- MTA (Mail Transfer Agent) implementations.
- Email forensics and analysis tools.

Basic Usage
-----------

### Add Tag to Subject Header

```java
import com.mimecast.robin.mime.headers.HeaderWrangler;
import com.mimecast.robin.mime.headers.HeaderTag;
import java.nio.charset.StandardCharsets;

byte[] emailBytes = ...; // Your email content as bytes.

HeaderWrangler wrangler = new HeaderWrangler();
wrangler.addHeaderTag(new HeaderTag("Subject", "[SPAM]"));

byte[] result = wrangler.process(emailBytes);
```

### Add Custom Headers

```java
import com.mimecast.robin.mime.headers.MimeHeader;

HeaderWrangler wrangler = new HeaderWrangler();
wrangler.addHeader(new MimeHeader("X-Spam-Score", "5.0"));
wrangler.addHeader(new MimeHeader("X-Spam-Status", "Yes"));

byte[] result = wrangler.process(emailBytes);
```

### Combine Tagging and Header Addition

```java
HeaderWrangler wrangler = new HeaderWrangler();

// Tag the subject.
wrangler.addHeaderTag(new HeaderTag("Subject", "[SPAM]"));

// Add custom headers.
wrangler.addHeader(new MimeHeader("X-Spam-Score", "8.5"));
wrangler.addHeader(new MimeHeader("X-Spam-Flag", "YES"));

byte[] result = wrangler.process(emailBytes);
```

Header Tagging
--------------

Header tagging prepends a tag string to the value of a specified header. This is commonly used to mark emails as spam, suspicious, or requiring special handling.

### Simple Text Headers

For unencoded headers, tags are simply prepended:

```java
// Original: Subject: Important Message
// Result:   Subject: [SPAM] Important Message

wrangler.addHeaderTag(new HeaderTag("Subject", "[SPAM]"));
```

### Encoded Headers (RFC 2047)

For encoded headers, the tag is intelligently inserted:

```java
// Original: Subject: =?UTF-8?B?VGVzdCBFbWFpbA==?=
// Result:   Subject: [SPAM] =?UTF-8?B?VGVzdCBFbWFpbA==?=

wrangler.addHeaderTag(new HeaderTag("Subject", "[SPAM]"));
```

The tag will be placed before the encoded word, maintaining proper encoding structure.

### Multi-line Headers

Headers that span multiple lines are properly handled:

```java
// Original: Subject: This is a very long subject
//             that spans multiple lines
// Result:   Subject: [TAG] This is a very long subject
//             that spans multiple lines

wrangler.addHeaderTag(new HeaderTag("Subject", "[TAG]"));
```

### Multiple Header Tags

You can tag multiple different headers in a single pass:

```java
wrangler.addHeaderTag(new HeaderTag("Subject", "[SPAM]"));
wrangler.addHeaderTag(new HeaderTag("X-Original-Sender", "[SUSPICIOUS]"));
```

Header tags are case-insensitive, so `"Subject"` matches `"SUBJECT"` or `"subject"`.

Header Addition
---------------

New headers are added after all existing headers, right before the email body (before the first blank line or MIME boundary).

### Single Header

```java
wrangler.addHeader(new MimeHeader("X-Spam-Score", "5.0"));
```

### Multiple Headers

```java
wrangler.addHeader(new MimeHeader("X-Spam-Score", "5.0"));
wrangler.addHeader(new MimeHeader("X-Spam-Status", "Yes"));
wrangler.addHeader(new MimeHeader("X-Processed-By", "HeaderWrangler"));
```

Headers are added in the order they are registered.

Complete Examples
-----------------

### Spam Detection Example

```java
import com.mimecast.robin.mime.headers.HeaderWrangler;
import com.mimecast.robin.mime.headers.HeaderTag;
import com.mimecast.robin.mime.headers.MimeHeader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class SpamDetectionExample {
    public static void main(String[] args) throws IOException {
        // Read email from file.
        byte[] emailBytes = Files.readAllBytes(Paths.get("email.eml"));
        
        // Configure wrangler.
        HeaderWrangler wrangler = new HeaderWrangler();
        
        // Tag subject as spam.
        wrangler.addHeaderTag(new HeaderTag("Subject", "[SPAM]"));
        
        // Add spam detection headers.
        wrangler.addHeader(new MimeHeader("X-Spam-Score", "8.5"));
        wrangler.addHeader(new MimeHeader("X-Spam-Flag", "YES"));
        wrangler.addHeader(new MimeHeader("X-Spam-Level", "********"));
        
        // Process email.
        byte[] result = wrangler.process(emailBytes);
        
        // Save modified email.
        Files.write(Paths.get("email-tagged.eml"), result);
    }
}
```

### Email Classification Example

```java
public class EmailClassificationExample {
    public static byte[] classifyEmail(byte[] emailBytes, String category, double confidence) 
            throws IOException {
        HeaderWrangler wrangler = new HeaderWrangler();
        
        // Tag subject with category.
        wrangler.addHeaderTag(new HeaderTag("Subject", "[" + category.toUpperCase() + "]"));
        
        // Add classification metadata.
        wrangler.addHeader(new MimeHeader("X-Classification", category));
        wrangler.addHeader(new MimeHeader("X-Confidence", String.valueOf(confidence)));
        wrangler.addHeader(new MimeHeader("X-Classifier", "ML-v2.0"));
        
        return wrangler.process(emailBytes);
    }
}
```

Technical Details
-----------------

### RFC 2047 Encoded Words

The HeaderWrangler properly handles RFC 2047 encoded-word format:

```
=?charset?encoding?encoded-text?=
```

Where:
- `charset` is the character set (e.g., UTF-8, ISO-8859-1).
- `encoding` is either `B` (Base64) or `Q` (Quoted-Printable).
- `encoded-text` is the encoded content.

When a tag is added to an encoded header, the tag (if ASCII) is placed before the encoded word.

### Header Folding

Long header values are automatically folded to comply with RFC 5322, which recommends lines should be no more than 78 characters.
Continuation lines start with whitespace (space or tab).

### MIME Boundary Detection

The wrangler detects the end of headers by looking for:
1. The first blank line (empty line).
2. A line starting with `--` (MIME boundary marker).

New headers are inserted before this boundary.

### Performance Considerations

- **Memory Usage** - The entire email is loaded into memory for processing.
- **Encoding Overhead** - Encoded headers may require additional processing.
- **Large Emails** - Suitable for typical email sizes; for very large emails, consider streaming approaches.

Troubleshooting
---------------

### Tag Not Appearing

**Problem**: Tag is not added to the header value.

**Solution**: Verify the header name matches (case-insensitive). Check that the header exists in the email.

### Encoding Issues

**Problem**: Special characters in tags appear incorrectly.

**Solution**: Ensure your email bytes are properly encoded as UTF-8. The HeaderWrangler uses UTF-8 for processing.

### Headers Not Added

**Problem**: New headers don't appear in the output.

**Solution**: Verify the email has proper header/body separation (blank line). Check for MIME boundaries that might interfere.

### Multi-line Headers Broken

**Problem**: Folded headers are not processed correctly.

**Solution**: Ensure continuation lines start with whitespace (space or tab). The wrangler follows RFC 5322 folding rules.

Dependencies
------------

- Java 16 or higher.
- Apache Commons Codec (for Base64 and Quoted-Printable encoding).
- JavaMail API (for RFC 2047 encoding utilities).
- Apache Log4j2 (for logging).

Testing
-------

Comprehensive unit tests are provided in `HeaderWranglerTest.java`, covering:

- Simple text header tagging.
- Encoded header tagging.
- Custom header addition.
- Multi-line header handling.
- MIME boundary detection.
- Case-insensitive header matching.
- Multiple tags and headers.

Run tests with:

```bash
mvn test -Dtest=HeaderWranglerTest
```

Integration with Robin
----------------------

The HeaderWrangler can be integrated into Robin's email processing pipeline for:

- Spam filtering integration with Rspamd.
- Virus scanning results from ClamAV.
- Custom email routing rules.
- Email forensics and debugging.

Example integration with Robin's SMTP server:

```java
// In email processing pipeline
HeaderWrangler wrangler = new HeaderWrangler();

if (isSpam(email)) {
    wrangler.addHeaderTag(new HeaderTag("Subject", "[SPAM]"));
    wrangler.addHeader(new MimeHeader("X-Spam-Flag", "YES"));
}

byte[] processedEmail = wrangler.process(originalEmail);
```

See Also
--------

- [MIME Email Parsing and Building](mime.md) - Complete MIME library documentation.
- [RFC 5322](https://www.rfc-editor.org/rfc/rfc5322) - Internet Message Format.
- [RFC 2047](https://www.rfc-editor.org/rfc/rfc2047) - MIME Part Three: Message Header Extensions.
- [RFC 2046](https://www.rfc-editor.org/rfc/rfc2046) - MIME Part Two: Media Types.
