Dovecot SASL & User Lookup Integration
=====================================

Overview
--------
Robin integrates with Dovecot using two separate UNIX domain sockets:
- Authentication (SASL): `DovecotSaslAuthNative` -> `/run/dovecot/auth-client`
- User existence lookup: `DovecotUserLookupNative` -> `/run/dovecot/auth-userdb`

The separation allows lightweight recipient validation (RCPT) without exposing passwords while keeping full SASL AUTH logic independent.

Requirements
------------
- Java 16+ (UNIX domain sockets)
- Running Dovecot with `auth-client` and `auth-userdb` listeners
- Proper filesystem permissions on the sockets

Configuration (`dovecot.json5`)
-------------------------------
```
{
  auth: true,
  authClientSocket: "/run/dovecot/auth-client",
  authUserdbSocket: "/run/dovecot/auth-userdb",
  saveToDovecotLda: true,
  ldaBinary: "/usr/libexec/dovecot/dovecot-lda",
  outboundMailbox: "Sent"
}
```

User Lookup Example
-------------------
```java
import com.mimecast.robin.sasl.DovecotUserLookupNative;
import java.nio.file.Paths;
try (DovecotUserLookupNative lookup = new DovecotUserLookupNative(Paths.get("/run/dovecot/auth-userdb"))) {
    if (lookup.validate("user@example.com", "smtp")) {
        System.out.println("User exists");
    } else {
        System.out.println("Unknown user");
    }
}
```

Authentication Examples
-----------------------
PLAIN (single step):
```java
import com.mimecast.robin.sasl.DovecotSaslAuthNative;
import java.nio.file.Paths;
try (DovecotSaslAuthNative auth = new DovecotSaslAuthNative(Paths.get("/run/dovecot/auth-client"))) {
    boolean ok = auth.authenticate("PLAIN", true, "user@example.com", "secret", "smtp", "127.0.0.1", "203.0.113.5");
    System.out.println(ok ? "Auth success" : "Auth failed");
}
```

LOGIN (multi-step handled internally):
```java
try (DovecotSaslAuthNative auth = new DovecotSaslAuthNative(Paths.get("/run/dovecot/auth-client"))) {
    boolean ok = auth.authenticate("LOGIN", true, "user@example.com", "secret", "smtp", "127.0.0.1", "203.0.113.5");
    System.out.println(ok ? "Login success" : "Login failed");
}
```

Advanced (explicit IDs):
```java
long pid = ProcessHandle.current().pid();
long req = 42;
try (DovecotSaslAuthNative auth = new DovecotSaslAuthNative(Paths.get("/run/dovecot/auth-client"))) {
    auth.authenticate("PLAIN", true, "user@example.com", "secret", "smtp", "127.0.0.1", "203.0.113.5", pid, req);
}
```

Complete Flow
-------------
```java
import com.mimecast.robin.sasl.*;
import java.nio.file.Paths;

// 1. Validate recipient/user exists (RCPT stage)
try (DovecotUserLookupNative lookup = new DovecotUserLookupNative(Paths.get("/run/dovecot/auth-userdb"))) {
    if (!lookup.validate("user@example.com", "smtp")) {
        System.out.println("Unknown user");
        return;
    }
}
// 2. Authenticate (AUTH stage)
try (DovecotSaslAuthNative auth = new DovecotSaslAuthNative(Paths.get("/run/dovecot/auth-client"))) {
    if (auth.authenticate("PLAIN", true, "user@example.com", "userPassword", "smtp", "192.168.1.10", "203.0.113.50")) {
        System.out.println("Authentication successful");
    } else {
        System.out.println("Authentication failed");
    }
}
```

Mechanisms
----------
- PLAIN: Base64 of `\0username\0password` sent in initial AUTH line.
- LOGIN: Sends AUTH without `resp`. Dovecot replies with two `CONT` prompts; library handles each and sends username/password automatically.

Error Handling
--------------
Both classes return `false` on failure.
Common causes:
- Missing or incorrect socket path / permissions
- Dovecot service not running
- Invalid credentials (AUTH)
- Unknown user (lookup)
- Unsecured channel when policy requires TLS

Implementation Notes
--------------------
- Use try-with-resources (both implement `AutoCloseable`).
- Request IDs auto-increment per instance.
- UTF-8 protocol lines; log output at DEBUG shows traffic.
- LOGIN flow encapsulated in a single `authenticate()` call.

Integration in Robin
--------------------
- RCPT uses `DovecotUserLookupNative` for mailbox validation.
- AUTH uses `DovecotSaslAuthNative` for SASL mechanisms.
- Config differentiates sockets: `authClientSocket` and `authUserdbSocket`.

Migration From Previous Version
-------------------------------
- Old single `authSocket` property replaced by two distinct sockets.
- User validation removed from `DovecotSaslAuthNative`; now in `DovecotUserLookupNative`.
- Update configuration and any custom code accordingly.
