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
- `webhooks.json5` Per-command HTTP callbacks with optional response override.
- `dovecot.json5` Socket auth & LDA integration replacing static users.

Minimal `server.json5` example (core listeners & feature flags):

    {
      hostname: "example.com",
      bind: "::",
      smtpPort: 25,
      securePort: 465,
      submissionPort: 587,
      smtpConfig: {
        backlog: 25,
        minimumPoolSize: 1,
        maximumPoolSize: 10,
        threadKeepAliveTime: 60,
        transactionsLimit: 305,
        errorLimit: 3
      },
      secureConfig: {
        backlog: 25,
        minimumPoolSize: 1,
        maximumPoolSize: 10,
        threadKeepAliveTime: 60,
        transactionsLimit: 305,
        errorLimit: 3
      },
      submissionConfig: {
        backlog: 25,
        minimumPoolSize: 1,
        maximumPoolSize: 10,
        threadKeepAliveTime: 60,
        transactionsLimit: 305,
        errorLimit: 3
      },
      auth: true,
      starttls: true,
      chunking: true,
      keystore: "/usr/local/robin/keystore.jks",
      keystorepassword: "avengers",
      truststore: "/usr/local/robin/truststore.jks",
      truststorepassword: "avengers",
      metricsPort: 8080,
      apiPort: 8090,
      usersEnabled: false
    }

Below are concise examples for each auxiliary config file.

`storage.json5` – Local message persistence & cleanup:

    {
      enabled: true,
      path: "/usr/local/robin/store",
      clean: false,
      patterns: ["^([0-9]{8}\\.)"]
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
      "*": {},
      "reject.com": {
        ehlo: "501 Not talking to you"
      },
      "failtls.com": {
        starttls: {
          response: "220 You will fail",
          protocols: ["TLSv1.0"],
          ciphers: ["TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA"]
        }
      }
    }

`relay.json5` – Inbound/outbound relay & LMTP / MX behaviour:

    {
      enabled: false,
      outboundEnabled: true,
      outboundMxEnabled: true,
      disableRelayHeader: true,
      host: "localhost",
      port: 24,
      protocol: "LMTP",
      tls: false,
      mailbox: "INBOX",
      outbox: "Sent",
      bounce: true
    }

`queue.json5` – Persistence & retry scheduling for failed outbound deliveries:

    {
      queueFile: "/usr/local/robin/relayQueue.db",
      queueInitialDelay: 10,
      queueInterval: 30,
      maxDequeuePerTick: 10,
      concurrencyScale: 32
    }

`prometheus.json5` – Remote write metrics push (disabled by default):

    {
      enabled: false,
      remoteWriteUrl: "",
      intervalSeconds: 15,
      timeoutSeconds: 10,
      compress: true,
      include: ["^jvm_.*", "^process_.*", "^system_.*"],
      exclude: [],
      labels: {
        job: "robin",
        instance: "{$hostname}"
      }
    }

`dovecot.json5` – Delegated auth / local delivery agent integration:

    {
      auth: true,
      authSocket: "/run/dovecot/auth-userdb",
      saveToDovecotLda: true,
      ldaBinary: "/usr/libexec/dovecot/dovecot-lda",
      outboundMailbox: "Sent"
    }

`webhooks.json5` – Optional HTTP hooks per SMTP extension (showing one example only):

    {
      "mail": {
        enabled: false,
        url: "http://localhost:8000/webhooks/mail",
        method: "POST",
        timeout: 5000,
        waitForResponse: true,
        ignoreErrors: false,
        authType: "none",
        includeSession: true,
        includeEnvelope: true,
        includeVerb: true,
        headers: {}
      }
    } /* other verbs: ehlo, starttls, auth, rcpt, data, bdat, raw, rset, help, quit, lhlo */

`routes.json5` – Static domains routing (used by outbound relay when MX disabled or for overrides):

    [
      {
        name: "local",
        mx: ["127.0.0.1"],
        port: 25
      },
      {
        name: "com",
        mx: ["example.com"],
        port: 25
      },
      {
        name: "net",
        mx: ["example.net"],
        port: 465,
        auth: true,
        user: "tony@example.com",
        pass: "giveHerTheRing"
      }
    ]
