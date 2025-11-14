Queue Persistence
=================

Robin supports multiple queue persistence backends for storing messages that could not be relayed. The queue backend is configured in `cfg/queue.json5`.

## Supported Backends

### MapDB (Default)
MapDB is a lightweight, file-based embedded database. It is the default backend and requires no external dependencies.

**Configuration:**
```json5
{
  queueMapDB: {
    enabled: true,
    queueFile: "/usr/local/robin/relayQueue.db",
    concurrencyScale: 32
  }
}
```

### MariaDB
MariaDB provides a robust SQL-based queue with transaction support.

**Configuration:**
```json5
{
  queueMariaDB: {
    enabled: true,
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

### PostgreSQL
PostgreSQL provides enterprise-grade queue persistence with ACID compliance.

**Configuration:**
```json5
{
  queuePgSQL: {
    enabled: true,
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

## Backend Selection Priority

The queue backend is selected in the following priority order when no file parameter is provided:
1. **MapDB** - if `queueMapDB.enabled` is `true`
2. **MariaDB** - if `queueMariaDB.enabled` is `true` (and `queueMapDB.enabled` is `false`)
3. **PostgreSQL** - if `queuePgSQL.enabled` is `true` (and `queueMapDB.enabled` and `queueMariaDB.enabled` are `false`)
4. **MapDB** - fallback default if no backend is enabled

**Note:** If a file parameter is explicitly provided (used in tests), MapDB will be used with that file regardless of configuration.

## Configuration Options

All backends share these common queue configuration options in `queue.json5`:

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `queueInitialDelay` | Integer | 10 | Initial delay before queue processing starts (seconds) |
| `queueInterval` | Integer | 30 | Interval between queue processing cycles (seconds) |
| `maxDequeuePerTick` | Integer | 10 | Maximum messages to process per cycle |
| `concurrencyScale` | Integer | 32 | Thread pool size for concurrent queue operations |

## Backend-Specific Options

### MapDB Options
| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `queueMapDB.enabled` | Boolean | `true` | Enable MapDB backend |
| `queueMapDB.queueFile` | String | `/usr/local/robin/relayQueue.db` | File path for MapDB backend |
| `queueMapDB.concurrencyScale` | Integer | 32 | MapDB-specific concurrency configuration |

### MariaDB Options
| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `queueMariaDB.enabled` | Boolean | `false` | Enable MariaDB backend |
| `queueMariaDB.jdbcUrl` | String | `jdbc:mariadb://localhost:3306/robin` | JDBC connection URL |
| `queueMariaDB.username` | String | `robin` | Database username |
| `queueMariaDB.password` | String | _(empty)_ | Database password |
| `queueMariaDB.tableName` | String | `queue` | Table name for queue storage |

### PostgreSQL Options
| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `queuePgSQL.enabled` | Boolean | `false` | Enable PostgreSQL backend |
| `queuePgSQL.jdbcUrl` | String | `jdbc:postgresql://localhost:5432/robin` | JDBC connection URL |
| `queuePgSQL.username` | String | `robin` | Database username |
| `queuePgSQL.password` | String | _(empty)_ | Database password |
| `queuePgSQL.tableName` | String | `queue` | Table name for queue storage |

## Example Configurations

### Example 1: MapDB (Default)
```json5
{
  queueInitialDelay: 10,
  queueInterval: 30,
  maxDequeuePerTick: 10,
  concurrencyScale: 32,

  queueMapDB: {
    enabled: true,
    queueFile: "/usr/local/robin/relayQueue.db",
    concurrencyScale: 32
  }
}
```

### Example 2: PostgreSQL Backend
```json5
{
  queueInitialDelay: 10,
  queueInterval: 30,
  maxDequeuePerTick: 10,
  concurrencyScale: 32,

  queueMapDB: {
    enabled: false
  },

  queuePgSQL: {
    enabled: true,
    jdbcUrl: "jdbc:postgresql://db.example.com:5432/robin_queue",
    username: "robin_user",
    password: "secure_password",
    tableName: "relay_queue"
  }
}
```

### Example 3: MariaDB Backend
```json5
{
  queueInitialDelay: 10,
  queueInterval: 30,
  maxDequeuePerTick: 10,
  concurrencyScale: 32,

  queueMapDB: {
    enabled: false
  },

  queueMariaDB: {
    enabled: true,
    jdbcUrl: "jdbc:mariadb://db.example.com:3306/robin",
    username: "robin_user",
    password: "secure_password",
    tableName: "relay_queue"
  }
}
```

**Note:** Only enable one backend at a time. If multiple backends are enabled, the selection priority will be: MapDB → MariaDB → PostgreSQL.
