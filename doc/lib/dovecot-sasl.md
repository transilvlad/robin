Dovecot SASL Authentication Library
====================================

Overview
--------
The `DovecotSaslAuthNative` class is a reusable Java library for authenticating against Dovecot SASL authentication sockets using native Java UNIX Domain Socket support (JDK 16+).

This library can be integrated into any Java application that needs to authenticate users against a Dovecot authentication service, making it ideal for MTA implementations, email services, or any application requiring SASL authentication.

Requirements
------------
- Java 16 or higher (for native UNIX Domain Socket support)
- Access to a Dovecot authentication socket (typically at `/var/run/dovecot/auth-userdb`)

Usage
-----

### Basic Setup

```java
import com.mimecast.robin.sasl.DovecotSaslAuthNative;
import java.nio.file.Paths;

// Initialize with the path to your Dovecot auth socket.
try (DovecotSaslAuthNative auth = new DovecotSaslAuthNative(
        Paths.get("/var/run/dovecot/auth-userdb"))) {
    // Use for authentication.
} catch (Exception e) {
    // Handle initialization errors.
}
```

### User Validation

To validate if a user exists in the Dovecot database:

```java
boolean exists = auth.validate("username", "smtp");
if (exists) {
    System.out.println("User exists");
}
```

**Parameters:**
- `username` - The username to validate
- `service` - The service name (e.g., "smtp", "imap", "pop3")

**Returns:** `true` if the user exists, `false` otherwise

### User Authentication

To authenticate a user with their password:

```java
boolean authenticated = auth.authenticate(
    "PLAIN",              // Authentication mechanism.
    true,                 // Connection secured (TLS/SSL).
    "username",           // Username.
    "password",           // Password.
    "smtp",               // Service name.
    "192.168.1.100",      // Local IP (server address).
    "203.0.113.50"        // Remote IP (client address).
);

if (authenticated) {
    System.out.println("Authentication successful");
}
```

**Parameters:**
- `mechanism` - Authentication mechanism (e.g., "PLAIN", "LOGIN", "CRAM-MD5")
- `secured` - Boolean indicating if the connection is secured
- `username` - The username to authenticate
- `password` - The password for the user
- `service` - The service name (e.g., "smtp", "imap")
- `localIp` - IP address of the server
- `remoteIp` - IP address of the connecting client
- `processId` - Process ID of the client application
- `requestId` - Unique request ID for this authentication

**Returns:** `true` if authentication succeeds, `false` otherwise

### Advanced Authentication

For more control over process and request IDs:

```java
long processId = ProcessHandle.current().pid();
long requestId = 12345;

boolean authenticated = auth.authenticate(
    "PLAIN",
    true,
    "username",
    "password",
    "smtp",
    "192.168.1.100",
    "203.0.113.50",
    processId,
    requestId
);
```

Complete Example
----------------

```java
import com.mimecast.robin.sasl.DovecotSaslAuthNative;
import java.nio.file.Paths;

public class DovecotAuthExample {
    public static void main(String[] args) {
        try (DovecotSaslAuthNative auth = new DovecotSaslAuthNative(
                Paths.get("/var/run/dovecot/auth-userdb"))) {
            
            // Validate user exists
            if (auth.validate("user@example.com", "smtp")) {
                System.out.println("User exists, attempting authentication...");
                
                // Authenticate user
                if (auth.authenticate(
                        "PLAIN",
                        true,
                        "user@example.com",
                        "userPassword",
                        "smtp",
                        "192.168.1.10",
                        "203.0.113.50")) {
                    System.out.println("Authentication successful!");
                } else {
                    System.out.println("Authentication failed");
                }
            } else {
                System.out.println("User does not exist");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

Error Handling
--------------

The library will return `false` for authentication/validation failures and log detailed error messages. Common issues include:

- **Socket not initialized**: The Dovecot auth socket path is inaccessible or incorrect.
- **Connection refused**: Dovecot authentication service is not running.
- **Invalid credentials**: The username or password is incorrect.
- **Service not available**: The specified service is not configured in Dovecot.

Check the application logs for detailed error messages and socket communication logs when debugging authentication issues.

Implementation Notes
--------------------

- The class implements `AutoCloseable` and should be used with try-with-resources for proper resource cleanup.
- Request IDs are managed internally using an `AtomicLong` counter to ensure uniqueness.
- Socket communication uses UTF-8 encoding.
- Passwords are Base64-encoded for PLAIN mechanism authentication.
- The class is thread-safe for multiple authentication attempts within the same session.

Integration with Robin
----------------------

This library is used internally by Robin MTA Server for authenticating SMTP clients against Dovecot mailbox stores.
The same class can be used as a standalone library in other applications requiring Dovecot SASL authentication.
