Dovecot SASL & User Lookup Integration
=====================================

Overview
--------
Robin integrates with Dovecot using two separate UNIX domain sockets:
- Authentication (SASL): `DovecotSaslAuthNative` -> `/run/dovecot/auth-client`
- User existence lookup: `DovecotUserLookupNative` -> `/run/dovecot/auth-userdb`

The separation allows lightweight recipient validation (RCPT) without exposing passwords while keeping full SASL AUTH logic independent.

Mailbox Delivery Backends
-------------------------
Robin supports two mailbox delivery backends:
- **LDA (Local Delivery Agent):** Requires Robin and Dovecot in the same container, uses UNIX socket and binary. Recommended for local setups.
- **LMTP (Local Mail Transfer Protocol):** Default backend, uses a configurable LMTP server list, works with SQL auth and does not require Robin and Dovecot in the same container. Recommended for distributed and SQL-backed setups.

Backend selection is automatic: the system checks which backend is enabled (`saveLda.enabled` or `saveLmtp.enabled`). LMTP takes precedence if both are enabled. Backend-specific options are grouped under `saveLda` and `saveLmtp` config objects. Shared options (inline save, failure behaviour, max retry count) are top-level.

Requirements
------------
- Java 21+ (UNIX domain sockets)
- Running Dovecot with `auth-client` and `auth-userdb` listeners
- Proper filesystem permissions on the sockets
- For LMTP, a reachable LMTP server (default: localhost:24)

Configuration (`dovecot.json5`)
-------------------------------
```json5
{
  auth: true,
  authSocket: {
    enabled: false,
    client: "/run/dovecot/auth-client",
    userdb: "/run/dovecot/auth-userdb"
  },
  authSql: {
    enabled: true,
    jdbcUrl: "jdbc:postgresql://robin-postgres:5432/robin",
    user: "robin",
    password: "robin",
    passwordQuery: "SELECT password FROM users WHERE email = ?",
    userQuery: "SELECT home, uid, gid, maildir AS mail FROM users WHERE email = ?"
  },
  saveLmtp: {
    enabled: true,
    servers: ["127.0.0.1"],
    port: 24,
    tls: false
  },
  saveLda: {
    enabled: false,
    ldaBinary: "/usr/libexec/dovecot/dovecot-lda",
    inboxFolder: "INBOX",
    sentFolder: "Sent"
  },
  // Shared mailbox delivery options for both backends.
  inlineSaveMaxAttempts: 2,
  inlineSaveRetryDelay: 3,
  failureBehaviour: "retry",
  maxRetryCount: 10
}
```

Note: The `authSocket` and `authSql` objects now use an `enabled` flag for backend selection. The legacy `authBackend` key is deprecated and should not be used. Only one authentication backend should be enabled at a time for clarity and security.

Operational Requirements
-----------------------
- **LDA backend:** Robin and Dovecot must run in the same container or host, with access to the UNIX socket and LDA binary.
- **LMTP backend:** Robin can deliver to any LMTP server in the configured list, does not require local Dovecot, and is recommended for SQL-backed and distributed setups.

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

If you want to read the socket from runtime config, use the typed config API:
```java
Path userdb = Paths.get(com.mimecast.robin.main.Config.getServer().getDovecot().getAuthSocket().getUserdb());
try (DovecotUserLookupNative lookup = new DovecotUserLookupNative(userdb)) { ... }
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

Using runtime-config socket path:
```java
Path client = Paths.get(com.mimecast.robin.main.Config.getServer().getDovecot().getAuthSocket().getClient());
try (DovecotSaslAuthNative auth = new DovecotSaslAuthNative(client)) { ... }
```

Complete Flow
-------------
```java
import com.mimecast.robin.sasl.*;
import java.nio.file.Paths;

// 1. Validate recipient/user exists (RCPT stage)
Path userdb = Paths.get(com.mimecast.robin.main.Config.getServer().getDovecot().getAuthSocket().getUserdb());
try (DovecotUserLookupNative lookup = new DovecotUserLookupNative(userdb)) {
    if (!lookup.validate("user@example.com", "smtp")) {
        System.out.println("Unknown user");
        return;
    }
}
// 2. Authenticate (AUTH stage)
Path client = Paths.get(com.mimecast.robin.main.Config.getServer().getDovecot().getAuthSocket().getClient());
try (DovecotSaslAuthNative auth = new DovecotSaslAuthNative(client)) {
    if (auth.authenticate("PLAIN", true, "user@example.com", "userPassword", "smtp", "192.168.1.10", "203.0.113.50")) {
        System.out.println("Authentication successful");
    } else {
        System.out.println("Authentication failed");
    }
}
```

y rSQL-based Auth & User Lookup (Robin)
------------------------------------

Robin supports using an SQL backend as an alternative to Dovecot UNIX domain sockets for
both user existence lookups (RCPT) and authentication (AUTH). This is controlled via
`cfg/dovecot.json5` using the `authBackend` key which can be set to either `dovecot` or `sql`.

Configuration example (`cfg/dovecot.json5`):
```
{
  authBackend: "sql",
  authSocket: {
    client: "/run/dovecot/auth-client",
    userdb: "/run/dovecot/auth-userdb"
  },
  authSql: {
    jdbcUrl: "jdbc:postgresql://robin-postgres:5432/robin",
    user: "robin",
    password: "robin",
    passwordQuery: "SELECT password FROM users WHERE email = ?",
    userQuery: "SELECT home, uid, gid, maildir AS mail FROM users WHERE email = ?"
  }
}
```

Java usage examples
-------------------

SqlUserLookup (RCPT / userdb lookup):
```java
import com.mimecast.robin.sasl.SqlUserLookup;
SqlUserLookup lookup = new SqlUserLookup("jdbc:postgresql://robin-postgres:5432/robin", "robin", "robin", "SELECT home, uid, gid, maildir AS mail FROM users WHERE email = ?");
Optional<SqlUserLookup.UserRecord> r = lookup.lookup("tony@example.com");
if (r.isPresent()) System.out.println("Found: " + r.get().email);
lookup.close();
```

SqlAuthProvider (AUTH):
```java
import com.mimecast.robin.sasl.SqlAuthProvider;
SqlAuthProvider auth = new SqlAuthProvider("jdbc:postgresql://robin-postgres:5432/robin", "robin", "robin", null);
boolean ok = auth.authenticate("tony@example.com", "stark");
System.out.println(ok ? "Auth OK" : "Auth Failed");
auth.close();
```

Notes
-----
- SqlAuthProvider uses Postgres' `crypt()` function (pgcrypto) to verify passwords server-side.
- Ensure `pgcrypto` extension is installed and Postgres image is glibc-backed (e.g., `postgres:15`) for SHA512-CRYPT support.
- SQL backend provides a consolidated storage model and simplifies environments where Dovecot is not used.
