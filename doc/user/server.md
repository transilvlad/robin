Server
======
Rudimentary debug server.
It supports user authentication and EHLO / LMTP LHLO scenarios.
Outside configured scenarios everything is accepted.

Configuration
-------------
Server core configuration lives in `server.json5`.

Configuration Reload
--------------------
Configuration can be reloaded without restarting the server via the service API.

**Config Viewer UI**: Access the configuration viewer at `http://localhost:8080/config` to:
- View current `properties.json5` and `server.json5` configurations in formatted JSON
- Trigger immediate configuration reload with a button click
- See real-time reload status and error messages

**Reload Endpoint**: Trigger configuration reload via API:

    POST http://localhost:8080/config/reload

This endpoint triggers immediate reload of `properties.json5` and `server.json5` configurations.
The reload operation uses the existing single-threaded scheduler ensuring thread-safe, serialized updates.
Authentication is required if service endpoint authentication is enabled.

**Terminal Examples**:

Using `curl` (no authentication):
```bash
curl -X POST http://localhost:8080/config/reload
```

Using `curl` with authentication:
```bash
curl -X POST http://localhost:8080/config/reload -u username:password
```

Using PowerShell:
```powershell
Invoke-WebRequest -Uri http://localhost:8080/config/reload -Method POST
```

Using PowerShell with authentication:
```powershell
$credentials = Get-Credential
Invoke-WebRequest -Uri http://localhost:8080/config/reload -Method POST -Credential $credentials
```

Success response:
```json
{"status":"OK", "message":"Configuration reloaded successfully"}
```

Error response:
```json
{"status":"ERROR", "message":"Failed to reload configuration: <error details>"}
```

External files (auto‑loaded if present in same directory):
- `properties.json5` Global application properties.
- `storage.json5` Email storage options.
- `users.json5` Local test users (disabled when Dovecot auth enabled).
- `scenarios.json5` SMTP behavior scenarios.
- `relay.json5` Automatic relay settings.
- `queue.json5` Persistent relay / retry queue.
- `prometheus.json5` Prometheus remote write metrics settings.
- `dovecot.json5` Socket auth & LDA integration replacing static users.
- `webhooks.json5` Per-command HTTP callbacks with optional response override.
- `vault.json5` HashiCorp Vault integration settings for secrets management.
- `clamav.json5` ClamAV integration for virus scanning.
- `rspamd.json5` Rspamd integration for spam/phishing detection.
- See [Scan Results](../features/scanners.md) for aggregating and accessing scanner results.
- `blocklist.json5` IP blocklist configuration.
- `blackhole.json5` Blackhole mode configuration.
- `proxy.json5` Proxy mode configuration.
- `bots.json5` Email infrastructure analysis bots configuration. See [Bots](../features/bots.md) for details.

Example `server.json5` (core listeners & feature flags):

    {
      // Hostname to declare in welcome message.
      hostname: "example.com",

      // Interface the server will bind too (default: ::).
      bind: "::",

      // Port the server will listen too (default: 25, 0 to disable).
      smtpPort: 25,

      // Port for secure SMTP via SSL/TLS (default: 465, 0 to disable).
      securePort: 465,

      // Port for mail submission (default: 587, 0 to disable).
      submissionPort: 587,

      // SMTP port configuration
      smtpConfig: {
        // Number of connections to be allowed in the backlog (default: 25).
        backlog: 25,

        // Minimum number of threads in the pool.
        minimumPoolSize: 1,

        // Maximum number of threads in the pool.
        maximumPoolSize: 10,

        // Time (in seconds) to keep idle threads alive.
        threadKeepAliveTime: 60,

        // Maximum number of SMTP transactions to process over a connection.
        transactionsLimit: 305,

        // Maximum number of recipients (emails) allowed per envelope (default: 100).
        recipientsLimit: 100,

        // Maximum number of envelopes (emails) allowed per connection (default: 100).
        envelopeLimit: 100,

        // Maximum size of a message in megabytes (default: 10242400).
        messageSizeLimit: 10242400, // 10 MB

        // Number of SMTP errors to allow before terminating connection (default: 3).
        errorLimit: 3
      },

      // Secure SMTP port configuration
      secureConfig: {
        backlog: 25,
        minimumPoolSize: 1,
        maximumPoolSize: 10,
        threadKeepAliveTime: 60,
        transactionsLimit: 305,
        recipientsLimit: 100,
        envelopeLimit: 100,
        messageSizeLimit: 10242400, // 10 MB
        errorLimit: 3
      },

      // Submission port configuration
      submissionConfig: {
        backlog: 25,
        minimumPoolSize: 1,
        maximumPoolSize: 10,
        threadKeepAliveTime: 60,
        transactionsLimit: 305,
        recipientsLimit: 100,
        envelopeLimit: 100,
        messageSizeLimit: 10242400, // 10 MB
        errorLimit: 3
      },

      // Advertise AUTH support (default: true).
      auth: true,

      // Advertise STARTTLS support (default: true).
      starttls: true,

      // Advertise CHUNKING support (default: true).
      chunking: true,

      // Java keystore (default: /usr/local/keystore.jks).
      keystore: "/usr/local/robin/keystore.jks",

      // Keystore password or path to password file.
      keystorepassword: "avengers",

      // Java truststore (default: /usr/local/truststore.jks).
      truststore: "/usr/local/robin/truststore.jks",

      // Truststore password or path to password file.
      truststorepassword: "avengers",

      // Service endpoint configuration.
      service: {
        // Port for service endpoint.
        port: 8080,
        
        // Authentication type: none, basic, bearer.
        authType: "none",
        
        // Authentication value.
        // For basic: "username:password"
        // For bearer: "token"
        authValue: "",
        
        // IP addresses or CIDR blocks allowed without authentication.
        allowList: []
      },
        
      // API endpoint configuration.
      api: {
        // Port for API endpoint.
        port: 8090,
        
        // Authentication type: none, basic, bearer.
        authType: "none",
        
        // Authentication value.
        // For basic: "username:password"
        // For bearer: "token"
        authValue: "",
        
        // IP addresses or CIDR blocks allowed without authentication.
        allowList: []
      },

      // RBL (Realtime Blackhole List) configuration.
      rbl: {
        // Enable or disable RBL checking (default: false).
        enabled: true,

        // Reject messages from blacklisted IPs (default: false).
        // If false, checks will be made and result saved in session.
        // Handy when using webhooks to decide on rejection.
        rejectEnabled: true,

        // List of RBL providers to check against.
        providers: [
          "zen.spamhaus.org",
          "bl.spamcop.net",
          "dnsbl.sorbs.net"
        ],

        // Maximum time in seconds to wait for RBL responses (default: 5).
        timeoutSeconds: 5
      },

      // Chaos headers for testing exception scenarios.
      // WARNING: Do NOT enable in production. This feature allows bypassing normal processing
      // and is intended strictly for development and testing purposes.
      chaosHeaders: false,

      // Enable XCLIENT extension for development and testing only.
      // WARNING: Do NOT enable in production. XCLIENT allows clients to forge sender information
      // and is intended strictly for development and testing purposes.
      xclientEnabled: false
    }

**Service Authentication**: Configure `serviceUsername` and `servicePassword` to enable HTTP Basic Authentication for the service endpoint. 
When both values are non-empty, all endpoints (including `/config` and `/config/reload`) except `/health` will require authentication.
Leave empty to disable authentication.


**API Authentication**: Configure `apiUsername` and `apiPassword` to enable HTTP Basic Authentication for the API endpoint.
When both values are non-empty, all endpoints except `/health` will require authentication.
Leave empty to disable authentication.

Below are concise examples for each auxiliary config file.

`storage.json5` – Local message persistence & cleanup:

    {
      // Enable email storage.
      enabled: true,

      // AutoDelete files at connection/session end.
      // If enabled, files are deleted when the SMTP/IMAP session ends,
      // however queued items are copied and deleted after successful delivery or bounce.
      autoDelete: true,

      // Enable local mailbox storage.
      // If enabled, emails are copied to recipient-specific mailbox folders.
      localMailbox: false,

      // Disable rename by magic header feature.
      disableRenameHeader: true,

      // Path to storage folder.
      path: "/usr/local/robin/store",

      // Folder for inbound emails. Leave empty to disable. Default: new
      inboundFolder: "new",

      // Folder for outbound emails. Leave empty to disable. Default: .Sent/new
      outboundFolder: ".Sent/new",

      // Auto clean storage on service start.
      clean: false,

      // Auto clean delete matching filenames only.
      patterns: [
        "^([0-9]{8}\\.)"
      ]
    }

`users.json5` – Static test users (ignored if `dovecot.auth` is true):

    {
      // Enable or disable user list for authentication.
      // This feature should be used for testing only.
      // This is disabled by default for security reasons.
      // User list is ignored if Dovecot authentication is enabled.
      listEnabled: false,

      // List of users allowed to authenticate to the server.
      list: [
        {
          name: "tony@example.com",
          pass: "stark"
        }
      ]
    }

`scenarios.json5` – Conditional SMTP verb responses keyed by EHLO/LHLO value:

    {
      // Default scenario to use if no others match.
      "*": {
        rcpt: [
          // Custom response for addresses matching value regex.
          {
            value: "friday\\-[0-9]+@example\\.com",
            response: "252 I think I know this user"
          }
        ]
      },

      // How to reject mail at different commands.
      "reject.com": {
        // Custom response for EHLO.
        ehlo: "501 Not talking to you",

        // Custom response for MAIL.
        mail: "451 I'm not listening to you",

        // Custom response for given recipients.
        rcpt: [
          {
            value: "ultron@reject\\.com",
            response: "501 Heart not found"
          }
        ],

        // Custom response for DATA.
        data: "554 Your data is corrupted"
      },

      // How to configure TLS for failure using a deprecated version and weak cipher.
      "failtls.com" : {
        // Custom response for STARTTLS.
        // STARTTLS also supports a list of protocols and ciphers to use handshake.
        starttls: {
          response: "220 You will fail",
          protocols: ["TLSv1.0"],
          ciphers: ["TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA"]
        }
      }
    }

`relay.json5` – Inbound/outbound relay & LMTP / MX behaviour:

    {
      // Enable inbound relay.
      enabled: false,

      // Enable outbound relay.
      outboundEnabled: true,

      // Enable outbound MX relay.
      // When enabled, the server will perform MX lookups for recipient domains instead of using inbound relay host.
      outboundMxEnabled: true,

      // Disable relay by magic header feature.
      disableRelayHeader: true,

      // Server to forward mail to.
      host: "localhost",

      // Port of SMTP server to forward mail to.
      port: 24,

      // Protocol (Default: ESMTP - Options: SMTP, LMTP, ESMTP, DOVECOT-LDA).
      protocol: "LMTP",

      // Use secure TLS connection to forward mail.
      tls: false,

      // Bounce email if relay fails.
      bounce: true
    }

`queue.json5` – Persistence & retry scheduling for failed outbound deliveries:

    {
      // Queue cron initial run delay (in seconds).
      queueInitialDelay: 10,

      // Queue cron processing interval (in seconds).
      queueInterval: 30,

      // Maximum number of messages to attempt to relay per cron tick.
      maxDequeuePerTick: 10,

      // MapDB backend configuration.
      queueMapDB: {
        enabled: true,
        queueFile: "/usr/local/robin/relayQueue.db",
        // Concurrency scale for parallel access.
        // Increase this value to improve performance on high throughput systems.
        // Must be the sum of all listeners max pool sizes (optionally plus 2 for the dequeue cron and queue/list endpoint).
        concurrencyScale: 32
      }
    }

`prometheus.json5` – Remote write metrics push (disabled by default):

    {
      // Enable/disable Prometheus remote write push.
      enabled: false,

      // Your remote write endpoint (Prometheus Agent, VictoriaMetrics, Mimir/Thanos Receive, etc.).
      // Example (Prometheus Agent default): "http://localhost:9201/api/v1/write".
      remoteWriteUrl: "",

      // Push interval and HTTP timeout (seconds).
      intervalSeconds: 15,
      timeoutSeconds: 10,

      // Compress payload with Snappy framed (recommended by most receivers). Set to false to disable.
      compress: true,

      // Include/exclude filters (regex); metric names use '_' instead of '.'.
      include: ["^jvm_.*", "^process_.*", "^system_.*"],
      exclude: [],

      // Static labels added to every series.
      labels: {
        job: "robin",
        instance: "{$hostname}"
      },

      // Optional extra headers to include with the request.
      headers: {},

      // Authentication (choose one)
      bearerToken: "",
      basicAuthUser: "",
      basicAuthPassword: "",

      // Optional multi-tenancy header
      tenantHeaderName: "",
      tenantHeaderValue: ""
    }

`dovecot.json5` – Delegated auth / local delivery agent integration:

    {
      // Enablement.
      auth: false,

      // Path to Dovecot authentication client SASL socket.
      authClientSocket: "/run/dovecot/auth-client",

      // Path to Dovecot user database lookup socket.
      authUserdbSocket: "/run/dovecot/auth-userdb",

      // Save a copy of each email to Dovecot LDA.
      saveToDovecotLda: true,

      // Path to Dovecot LDA binary.
      ldaBinary: "/usr/libexec/dovecot/dovecot-lda",

      // Folder for inbound email delivery via Dovecot LDA.
      // Dovecot handles folder structure internally (e.g., adds "." prefix and "/new" suffix).
      inboxFolder: "INBOX",

      // Folder for outbound email delivery via Dovecot LDA.
      // Dovecot handles folder structure internally (e.g., adds "." prefix and "/new" suffix).
      sentFolder: "Sent"
    }

`webhooks.json5` – Optional HTTP hooks per SMTP extension (showing one example only):

    {
      // EHLO/HELO/LHLO - Initial connection command.
      "ehlo": {
        // Enable webhook for this extension.
        enabled: false,

        // Webhook endpoint URL.
        url: "http://localhost:8000/webhooks/ehlo",

        // HTTP method (GET, POST, PUT, PATCH, DELETE).
        method: "POST",

        // Timeout in milliseconds.
        timeout: 5000,

        // Wait for webhook response before processing extension.
        waitForResponse: true,

        // Ignore errors and continue processing if webhook fails.
        ignoreErrors: false,

        // Authentication type: none, basic, bearer.
        authType: "none",

        // Authentication value (username:password for basic, token for bearer).
        authValue: "",

        // Include session data in payload.
        includeSession: true,

        // Include envelope data in payload.
        includeEnvelope: true,

        // Include verb data in payload.
        includeVerb: true,

        // Custom HTTP headers.
        headers: {
          "X-Custom-Header": "value"
        }
      }
    } /* other verbs: starttls, auth, rcpt, data, bdat, mail, rset, raw, help, quit, lhlo */

`vault.json5` – HashiCorp Vault integration for secrets management:

    {
      // Enable or disable Vault integration (default: false).
      enabled: false,

      // Vault server address (e.g., "https://vault.example.com:8200").
      address: "https://vault.example.com:8200",

      // Vault authentication token or path to token file.
      token: "",

      // Vault namespace (optional, for Vault Enterprise).
      namespace: "",

      // Skip TLS certificate verification (use only in development).
      skipTlsVerification: false,

      // Connection timeout in seconds (default: 30).
      connectTimeout: 30,

      // Read timeout in seconds (default: 30).
      readTimeout: 30,

      // Write timeout in seconds (default: 30).
      writeTimeout: 30
    }

`clamav.json5` – ClamAV integration for virus scanning:

    {
      // ClamAV server settings
      clamav: {

        // Enable/disable ClamAV scanning.
        enabled: false,

        // Scan email attachments individually for better results.
        scanAttachments: false,

        // ClamAV server host.
        host: "localhost",

        // ClamAV server port.
        port: 3310,

        // Connection timeout in seconds.
        timeout: 5,

        // Action to take when a virus is found.
        // "reject" - Reject the email with a 5xx error.
        // "discard" - Accept the email and discard it.
        onVirus: "reject"
      }
    }

`blocklist.json5` – IP blocklist configuration:

    {
      // Enable IP blocklist checking.
      enabled: false,

      // List of IP addresses or CIDR ranges to block.
      blockedIps: [
        "192.168.1.0/24",
        "10.0.0.5"
      ],

      // Action to take when blocked IP connects.
      // "reject" - Reject connection.
      // "log" - Log and allow.
      action: "reject"
    }

`blackhole.json5` – Blackhole mode configuration:

    {
      // Enable blackhole mode.
      // In this mode, the server accepts all mail but discards it.
      enabled: false,

      // Log discarded messages.
      logDiscarded: true,

      // Response to give clients (appears as success to the client).
      response: "250 Message accepted and will be processed"
    }


`proxy.json5` – Proxy mode configuration:

    {
      // Enable proxy mode.
      enabled: false,

      // List of proxy rules.
      // Only the FIRST matching rule will proxy the email.
      // Connections are REUSED across multiple envelopes for performance.
      rules: [
        {
          // Matching patterns (regex)
          rcpt: ".*@proxy-destination\\.example\\.com",

          // Proxy destination (hosts is an array for failover support)
          hosts: ["relay.example.com", "backup-relay.example.com"],
          port: 25,
          protocol: "esmtp",  // smtp, esmtp, or lmtp
          tls: false,

          // Direction filtering (optional, default: both)
          direction: "both",  // both, inbound, or outbound

          // Authentication (optional)
          authUsername: "user",
          authPassword: "pass",
          authMechanism: "PLAIN",

          // Action for non-matching recipients
          action: "none"  // accept, reject, or none
        }
      ]
    }

See [Proxy Documentation](../features/proxy.md) for detailed configuration and examples.

XCLIENT Extension
-----------------

The XCLIENT extension allows SMTP clients to override connection attributes such as the client hostname, IP address, and HELO/EHLO name. This is primarily used by mail proxies and filters that want to preserve the original client information when forwarding mail to another MTA.

**WARNING:** This feature is intended for development and testing purposes only. Do NOT enable in production environments as XCLIENT allows clients to forge sender information, which can be a significant security risk if exposed without proper access controls.

### Configuration

Enable XCLIENT in `server.json5`:

    {
      // Enable XCLIENT extension for development and testing only.
      // WARNING: Do NOT enable in production.
      xclientEnabled: true
    }

### Security Considerations

- XCLIENT should **only** be enabled in controlled development/testing environments
- Never enable XCLIENT on production mail servers accessible from the internet
- If you must use XCLIENT in production, implement strict IP-based access controls
- Consider using proxy mode or relay configurations as safer alternatives

### Usage

When enabled, clients can use the XCLIENT command to override connection attributes:

    XCLIENT NAME=mail.example.com ADDR=192.0.2.1 HELO=client.example.com

See the [Postfix XCLIENT documentation](http://www.postfix.org/XCLIENT_README.html) for the complete protocol specification.

Chaos Headers
-------------

The chaos headers feature allows testing exception scenarios by forcing storage processors to return predefined results.

**WARNING:** This feature is intended for testing purposes only. Do NOT enable in production environments as it allows forcing specific return values without normal validation.

### Configuration

Enable chaos headers in `server.json5`:

    {
      // Chaos headers for testing exception scenarios.
      // WARNING: Do NOT enable in production.
      chaosHeaders: true
    }

### Usage

Add `X-Robin-Chaos` headers to test emails with the format:

    X-Robin-Chaos: ClassName; param1=value1; param2=value2

Where:
- `ClassName` - The implementation class where the action occurs.
- Parameters define the forced behavior.

Multiple chaos headers can be present in the same email to test different scenarios.

### Examples

**Force any storage processor to return a specific value:**

    X-Robin-Chaos: LocalStorageClient; processor=AVStorageProcessor; return=true
    X-Robin-Chaos: LocalStorageClient; processor=SpamStorageProcessor; return=false
    X-Robin-Chaos: LocalStorageClient; processor=LocalStorageProcessor; return=true

This forces the specified storage processor to return the predefined value without performing its normal processing logic. The `processor` parameter should match the processor class name (e.g., `AVStorageProcessor`, `SpamStorageProcessor`, `LocalStorageProcessor`, `DovecotStorageProcessor`). The `return` parameter specifies the forced return value (`true` to continue, `false` to fail).

**Important:** The processor is still called and can perform initialization or logging, but it returns the forced value immediately before executing its main logic.

**Simulate Dovecot LDA storage failure:**

    X-Robin-Chaos: DovecotLdaClient; recipient=tony@example.com; exitCode=1; message="storage full"

This simulates a storage failure for the specified recipient with exit code `1` and error message `storage full`.

**Test multiple scenarios:**

    X-Robin-Chaos: LocalStorageClient; processor=AVStorageProcessor; return=true
    X-Robin-Chaos: DovecotLdaClient; recipient=user1@example.com; exitCode=0; message="success"
    X-Robin-Chaos: DovecotLdaClient; recipient=user2@example.com; exitCode=1; message="quota exceeded"

See [magic.md](../features/magic.md) for complete chaos headers documentation.

Mailbox Delivery Backends
------------------------
Robin supports two mailbox delivery backends for saving emails:
- **LDA (Local Delivery Agent):** Requires Robin and Dovecot in the same container, uses UNIX socket and binary. Recommended for local setups.
- **LMTP (Local Mail Transfer Protocol):** Default backend, uses a configurable LMTP server list, works with SQL auth and does not require Robin and Dovecot in the same container. Recommended for distributed and SQL-backed setups.

Backend selection is automatic: the system checks which backend is enabled (`saveLda.enabled` or `saveLmtp.enabled`). LMTP takes precedence if both are enabled. Backend-specific options are grouped under `saveLda` and `saveLmtp` config objects. Shared options (inline save, failure behaviour, max retry count) are top-level.

Example dovecot.json5 configuration:
```json5
{
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
  inlineSaveMaxAttempts: 2,
  inlineSaveRetryDelay: 3,
  failureBehaviour: "retry",
  maxRetryCount: 10
}
```

Operational Requirements
-----------------------
- **LDA backend:** Robin and Dovecot must run in the same container or host, with access to the UNIX socket and LDA binary.
- **LMTP backend:** Robin can deliver to any LMTP server in the configured list, does not require local Dovecot, and is recommended for SQL-backed and distributed setups.

