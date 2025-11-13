![Robin MTA Server and Tester](doc/img/robin.jpg)

By **Vlad Marian** *<transilvlad@gmail.com>*


Overview
--------
Robin MTA Server and Tester is a development, debug and testing tool for MTA architects.

However, as the name suggests it can also be used as a lightweight MTA server with Dovecot SASL AUTH and mailbox integration.

It is powered by a highly customizable SMTP client designed to emulate the behaviour of popular email clients.

The lightweight server is ideal for a simple configurable catch server for testing or a fully fledged MTA using Dovecot mailboxes or web hooks.
Provides Prometheus and Graphite metrics with Prometheus remote write built in plus a multitude or other handy endpoints.

The testing support is done via JSON files called test cases.
Cases are client configuration files ran as Junit tests leveraging CI/CD integrations.

This project can be compiled into a runnable JAR or built into a Docker container as a stand alone MTA or combined with Dovecot.

A CLI interface is implemented with support for client, server and MTA-STS client execution.
Robin makes use of the single responsibility principle whenever possible providing stand-alone tools and libraries most notably an MTA-STS implementation.

Use this to run end-to-end tests manually or in automation.
This helps identify bugs early before leaving the development and staging environments.

Or set up the MTA server and configure it with for your mailbox hosting, API infrastructure or bespoke needs.
Best do both since Robin is both an MTA and MTA tester :)

Contributions
-------------
Contributions of any kind (bug fixes, new features...) are welcome!
This is a development tool and as such it may not be perfect and may be lacking in some areas.

Certain future functionalities are marked with TODO comments throughout the code.
This however does not mean they will be given priority or ever be done.

Any merge request made should align to existing coding style and naming convention.
Before submitting a merge request please run a comprehensive code quality analysis (IntelliJ, SonarQube).

Read more [here](contributing.md).


Disclosure
----------
This project makes use of sample password as needed for testing and demonstration purposes.

- notMyPassword - It's not my password. It can't be as password length and complexity not met.
- 1234 - Sample used in some unit tests.
- giveHerTheRing - Another sample used in unit tests and documentation. (Tony Stark / Pepper Potts Easter egg)
- avengers - Test keystore password that contains a single entry issued to Tony Stark. (Another Easter egg)

**These passwords are not in use within production environments.**


Documentation
-------------
- [Introduction](doc/introduction.md)
- [CLI usage](doc/cli.md)

### Java SMTP/ESMTP/LMTP Client
- [Client usage](doc/client.md)

### Server
- [Server configuration](doc/server.md)
- [Queue Persistence](#queue-persistence) - Configurable queue backends (MapDB, MariaDB, PostgreSQL).
- [HashiCorp Vault](doc/vault.md)
- [SMTP webhooks](doc/webhooks.md)
- [Endpoints](doc/endpoints.md) - JVM metrics implementation.
- [Prometheus Remote Write](doc/prometheus.md) - Prometheus Remote Write implementation.
- [ClamAV Integration](doc/clamav.md) - ClamAV virus scanning integration.
- [Rspamd Integration](doc/rspamd.md) - Rspamd spam/phishing detection integration.

### Secrets
- [Secrets, magic and Local Secrets File](doc/secrets.md)

### Testing cases
- [E/SMTP Cases](doc/case-smtp.md)
- [HTTP/S Cases](doc/case-http.md)
- [Magic](doc/magic.md)
- [MIME](doc/mime.md)

### Server Library
- [Plugins](doc/plugins.md)
- [Flowchart](doc/flowchart.md)

### Libraries
- [MTA-STS](doc/lib/mta-sts/readme.md) - MTA-STS compliant MX resolver implementation (former MTA-STS library).
- [Dovecot SASL](doc/lib/dovecot-sasl.md) - Dovecot SASL authentication implementation.
- [IMAP helper](doc/lib/imap.md) - Lightweight Jakarta Mail IMAP client.
- [MIME Parsing and Building](doc/lib/mime.md) - Lightweight RFC 2822 email parsing and composition.
- [MIME Header Wrangler](doc/lib/header-wrangler.md) - MIME header content injector for tagging and adding headers.
- [Received Header Builder](doc/lib/received-header.md) - RFC-compliant Received header builder for email tracing.
- [HTTP Request Client](doc/lib/request.md) - Lightweight HTTP request client.

### Miscellaneous
- [Contributing](contributing.md)
- [Code of conduct](code_of_conduct.md)

_Robin is designed with single responsibility principle in mind and thus can provide reusable components for various tasks._


Queue Persistence
-----------------

Robin supports multiple queue persistence backends for storing messages that could not be relayed. The queue backend is configured in `cfg/queue.json5`.

### Supported Backends

#### MapDB (Default)
MapDB is a lightweight, file-based embedded database. It is the default backend and requires no external dependencies.

**Configuration:**
```json5
{
  queueFile: "/usr/local/robin/relayQueue.db",
  queueMapDB: {
    concurrencyScale: 32
  }
}
```

#### MariaDB
MariaDB provides a robust SQL-based queue with transaction support.

**Configuration:**
```json5
{
  queueMariaDB: {
    jdbcUrl: "jdbc:mariadb://localhost:3306/robin",
    username: "robin",
    password: "your_password_here",
    tableName: "queue"
  }
}
```

**Database Setup:**
The queue table will be automatically created on initialization with the following structure:
```sql
CREATE TABLE IF NOT EXISTS queue (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  data LONGBLOB NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
```

#### PostgreSQL
PostgreSQL provides enterprise-grade queue persistence with ACID compliance.

**Configuration:**
```json5
{
  queuePgSQL: {
    jdbcUrl: "jdbc:postgresql://localhost:5432/robin",
    username: "robin",
    password: "your_password_here",
    tableName: "queue"
  }
}
```

**Database Setup:**
The queue table will be automatically created on initialization with the following structure:
```sql
CREATE TABLE IF NOT EXISTS queue (
  id BIGSERIAL PRIMARY KEY,
  data BYTEA NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### Backend Selection Priority

The queue backend is selected in the following priority order:
1. **MapDB** - if `queueMapDB` configuration exists
2. **MariaDB** - if `queueMariaDB` configuration exists (and `queueMapDB` does not)
3. **PostgreSQL** - if `queuePgSQL` configuration exists (and `queueMapDB` and `queueMariaDB` do not)
4. **MapDB** - fallback default if no backend is configured

### Configuration Options

All backends share these common queue configuration options in `queue.json5`:

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `queueFile` | String | `/usr/local/robin/relayQueue.db` | File path for MapDB backend |
| `queueInitialDelay` | Integer | 10 | Initial delay before queue processing starts (seconds) |
| `queueInterval` | Integer | 30 | Interval between queue processing cycles (seconds) |
| `maxDequeuePerTick` | Integer | 10 | Maximum messages to process per cycle |
| `concurrencyScale` | Integer | 32 | Thread pool size for concurrent queue operations |

### Backend-Specific Options

#### MapDB Options
| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `queueMapDB.concurrencyScale` | Integer | 32 | MapDB-specific concurrency configuration |

#### MariaDB Options
| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `queueMariaDB.jdbcUrl` | String | `jdbc:mariadb://localhost:3306/robin` | JDBC connection URL |
| `queueMariaDB.username` | String | `robin` | Database username |
| `queueMariaDB.password` | String | _(empty)_ | Database password |
| `queueMariaDB.tableName` | String | `queue` | Table name for queue storage |

#### PostgreSQL Options
| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `queuePgSQL.jdbcUrl` | String | `jdbc:postgresql://localhost:5432/robin` | JDBC connection URL |
| `queuePgSQL.username` | String | `robin` | Database username |
| `queuePgSQL.password` | String | _(empty)_ | Database password |
| `queuePgSQL.tableName` | String | `queue` | Table name for queue storage |

### Example: Full queue.json5 Configuration

```json5
{
  // Queue file for MapDB backend
  queueFile: "/usr/local/robin/relayQueue.db",

  // Queue processing settings
  queueInitialDelay: 10,
  queueInterval: 30,
  maxDequeuePerTick: 10,
  concurrencyScale: 32,

  // Use PostgreSQL backend
  queuePgSQL: {
    jdbcUrl: "jdbc:postgresql://db.example.com:5432/robin_queue",
    username: "robin_user",
    password: "secure_password",
    tableName: "relay_queue"
  }
}
```

**Note:** Only configure one backend section (`queueMapDB`, `queueMariaDB`, or `queuePgSQL`) to avoid confusion, though the selection priority will handle multiple configurations gracefully.
