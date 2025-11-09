Server
======
Rudimentary debug server.
It supports user authentication and EHLO / LMTP LHLO scenarios.
Outside configured scenarios everything is accepted.

Configuration
-------------
Server core configuration lives in `server.json5`.

External files (auto‑loaded if present in same directory):
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

      // Metrics endpoint port.
      metricsPort: 8080,

      // Metrics endpoint authentication (optional, leave empty to disable).
      metricsUsername: "",
      metricsPassword: "",

      // API endpoint port.
      apiPort: 8090,

      // API endpoint authentication (optional, leave empty to disable).
      apiUsername: "",
      apiPassword: "",

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

      // Users allowed to authorize to the server.
      // This feature should be used for testing only.
      // This is disabled by default for security reasons.
      usersEnabled: false // See users.json5 for user definitions.
    }

**Metrics Authentication**: Configure `metricsUsername` and `metricsPassword` to enable HTTP Basic Authentication for the metrics endpoint. 
When both values are non-empty, all endpoints except `/health` will require authentication.
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

    [
      {
        name: "tony@example.com",
        pass: "giveHerTheRing"
      }
    ]

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
      // Queue file to use for persisting messages that could not be relayed.
      queueFile: "/usr/local/robin/relayQueue.db",

      // Queue cron initial run delay (in seconds).
      queueInitialDelay: 10,

      // Queue cron processing interval (in seconds).
      queueInterval: 30,

      // Maximum number of messages to attempt to relay per cron tick.
      maxDequeuePerTick: 10,

      // Concurrency scale for parallel access.
      // Increase this value to improve performance on high throughput systems.
      // Must be the sum of all listeners max pool sizes (optionally plus 2 for the dequeue cron and queue-list endpoint).
      concurrencyScale: 32
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
