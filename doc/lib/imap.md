# IMAP helper (ImapClient)

This document describes `ImapClient`, a small Jakarta Mail based helper included in the project under `com.mimecast.robin.imap`.

Purpose
-------
`ImapClient` is a lightweight convenience wrapper used by tests and utilities to connect to an IMAP server,
open a folder (defaults to `INBOX`) and fetch messages. It is intentionally small and focused for testing and automation use-cases.

Dependencies
------------
The project already includes Jakarta Mail dependencies. If you want to use `ImapClient` in another project,
add the following Maven dependency (example):

```xml
<dependency>
  <groupId>com.sun.mail</groupId>
  <artifactId>jakarta.mail</artifactId>
  <version>${jakarta.version}</version>
</dependency>
```

(The project itself also includes `javax.mail:javax.mail-api` for compatibility.)

Quick usage
-----------
The class implements `AutoCloseable` and is intended to be used in a try-with-resources block.

### Available methods

| Method | Description |
|--------|-------------|
| `List<Message> fetchEmails()` | Returns all messages in the configured folder (read-only). Returns an empty list on error. |
| `Message fetchEmailByMessageId(String messageId)` | Finds a message by Message-ID using IMAP SEARCH with linear scan fallback. |
| `Message searchByMessageId(String messageId)` | Server-side IMAP SEARCH by Message-ID header. More efficient for large mailboxes. |
| `boolean deleteMessage(Message message)` | Deletes a single message from the mailbox. |
| `int purgeFolder()` | Deletes all messages in the configured folder. Returns count or -1 on error. |
| `List<MimePart> extractMimeParts(Message message)` | Extracts MIME parts using the built-in EmailParser. Returns raw MimePart objects. |
| `List<PartDescriptor> extractParts(Message message)` | Extracts MIME parts as PartDescriptor records with headers, body, hashes, and size. |

### Basic example

```java
import com.mimecast.robin.imap.ImapClient;
import jakarta.mail.Message;

try (ImapClient client = new ImapClient("imap.example.com", 993, "user@example.com", "secret")) {
    // Fetch all messages
    List<Message> messages = client.fetchEmails();
    for (Message m : messages) {
        System.out.println("Subject: " + m.getSubject());
    }

    // Find an email by Message-ID (uses SEARCH then falls back to linear scan)
    Message found = client.fetchEmailByMessageId("<abc@example.com>");
    if (found != null) {
        System.out.println("Found: " + found.getSubject());
    }
}
```

### Message deletion example

```java
try (ImapClient client = new ImapClient("imap.example.com", 993, "user@example.com", "secret")) {
    Message found = client.fetchEmailByMessageId("<abc@example.com>");
    if (found != null) {
        // Delete this specific message
        boolean deleted = client.deleteMessage(found);
        System.out.println("Deleted: " + deleted);
    }

    // Or purge all messages in the folder
    int count = client.purgeFolder();
    System.out.println("Purged " + count + " messages");
}
```

### MIME part extraction example

```java
import com.mimecast.robin.imap.ImapClient.PartDescriptor;

try (ImapClient client = new ImapClient("imap.example.com", 993, "user@example.com", "secret")) {
    Message found = client.fetchEmailByMessageId("<abc@example.com>");
    if (found != null) {
        List<PartDescriptor> parts = client.extractParts(found);
        for (PartDescriptor part : parts) {
            String contentType = part.headers().get("Content-Type");
            System.out.println("Part: " + contentType);
            System.out.println("Size: " + part.size() + " bytes");
            System.out.println("SHA-256: " + part.hashes().get("sha256"));
            System.out.println("Body: " + part.body().substring(0, Math.min(100, part.body().length())));
        }
    }
}
```

Notes and tips
--------------
- Port 993 enables SSL for Jakarta Mail via the `mail.imap.ssl.enable` property.
  If you use a different port or STARTTLS, adjust the configuration accordingly.
- `fetchEmails()` opens the folder in read-only mode and returns a list converted from `Message[]`.
  If no messages are present an empty list is returned.
- `fetchEmailByMessageId(...)` first attempts an efficient server-side IMAP SEARCH, then falls back to
  a linear scan if the search returns no results. This handles servers with limited search support.
- `searchByMessageId(...)` performs only the server-side IMAP SEARCH without fallback.
- `deleteMessage(...)` reopens the folder in READ_WRITE mode if needed before marking and expunging.
- `purgeFolder()` deletes all messages in the configured folder - use with caution in production.
- `extractParts(...)` and `extractMimeParts(...)` use the built-in `EmailParser` to parse MIME structures,
  supporting Base64/Quoted-Printable decoding and SHA-1/SHA-256/MD5 hash calculation.

Limitations
-----------
- `ImapClient` is not a fully featured IMAP client: it doesn't support folder management or complex
  authentication mechanisms (e.g. OAuth2) out-of-the-box. It's intended for test and automation workflows.

IMAP External Assertions
------------------------
The `ImapExternalClient` uses `ImapClient` internally to verify email delivery as part of test assertions.
See `doc/testing/case-smtp.md` for configuration details.

### Assertion configuration example

```json5
{
  type: "imap",
  host: "imap.example.com",
  port: 993,
  user: "test@example.com",
  pass: "secret",
  folder: "INBOX",
  wait: 5,      // Initial wait before first check (seconds)
  delay: 10,    // Delay between retries (seconds)
  retry: 5,     // Maximum retry attempts
  delete: true, // Delete verified message after successful assertion
  // purge: true, // Alternative: delete ALL messages in folder (takes precedence over delete)
  matches: {
    // Message header patterns (regex)
    headers: [
      ["subject", "Test Email"],
      ["message-id", "<{$msgid}>"]
    ],
    // MIME part patterns (optional)
    parts: [
      {
        // Filter to specific part by its headers (optional)
        headers: [
          ["content-type", "text/plain"]
        ],
        // Assert against decoded body content (regex)
        body: [
          ["Hello, World"],
          ["Lorem ipsum"]
        ],
        // Assert against content hashes (optional, Base64-encoded)
        hashes: {
          sha256: "expected-base64-sha256-hash",
          md5: "expected-base64-md5-hash"
        }
      },
      {
        headers: [
          ["content-type", "text/html"]
        ],
        body: [
          ["<html>"],
          ["</html>"]
        ]
      }
    ]
  }
}
```

### Cleanup options

- `delete: true` - Deletes only the verified message after successful assertion. Useful for test cleanup.
- `purge: true` - Deletes all messages in the folder after successful assertion. If both are set, `purge` takes precedence.
- Cleanup only runs on successful verification. Failed assertions preserve messages for debugging.

_Contributions are welcome via pull requests._
