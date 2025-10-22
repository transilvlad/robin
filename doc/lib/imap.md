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
The class implements `AutoCloseable` and is intended to be used in a try-with-resources block. The helper exposes two convenience methods:

- `List<Message> fetchEmails()` - returns all messages in the configured folder (read-only). Returns an empty list in case of error.
- `Message fetchEmailByMessageId(String messageId)` - linear search by the `Message-ID` header. Returns the `Message` or `null` if not found.

Example:

```java
import com.mimecast.robin.imap.ImapClient;
import jakarta.mail.Message;

try (ImapClient client = new ImapClient("imap.example.com", 993, "user@example.com", "secret")) {
    // Fetch all messages
    List<Message> messages = client.fetchEmails();
    for (Message m : messages) {
        System.out.println("Subject: " + m.getSubject());
    }

    // Find an email by Message-ID
    Message found = client.fetchEmailByMessageId("<abc@example.com>");
    if (found != null) {
        System.out.println("Found: " + found.getSubject());
    }
}
```

Notes and tips
--------------
- Port 993 enables SSL for Jakarta Mail via the `mail.imap.ssl.enable` property.
  If you use a different port or STARTTLS, adjust the configuration accordingly.
- `fetchEmails()` opens the folder in read-only mode and returns a list converted from `Message[]`.
If no messages are present an empty list is returned.
- `fetchEmailByMessageId(...)` performs a simple linear scan and compares the first `Message-ID` header entry with `String.contains()`.
This is deliberately forgiving with or without angle brackets.
- To read the body of a `Message`, you might need to handle `Multipart` and different content types — Jakarta Mail examples are widely available.

Limitations
-----------
- `ImapClient` is not a fully featured IMAP client: it doesn't support advanced search, folder management, or complex authentication mechanisms (e.g. OAuth2) out-of-the-box.
It's intended for simple test and automation workflows.

Feedback and improvements
-------------------------
If you need additional features (e.g. search by header, folder listing, or message deletion),
consider extending the class or adding helper utilities next to it.

_Contributions are welcome via pull requests._
