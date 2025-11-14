Queue Persistence
=================

Robin supports multiple queue persistence backends for storing messages that could not be relayed. The queue backend is configured in `cfg/queue.json5`.

## Supported Backends

### MapDB (Default for Production)
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
MariaDB provides a robust SQL-based queue with transaction support and LONGBLOB storage for serialized objects.

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
PostgreSQL provides enterprise-grade queue persistence with ACID compliance and BYTEA storage for serialized objects.

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

### InMemory (Default for Tests)
An in-memory queue database is used when all persistence backends are disabled. This is the default for test environments and provides no persistence across restarts.

**Configuration:**
```json5
{
  queueMapDB: { enabled: false },
  queueMariaDB: { enabled: false },
  queuePgSQL: { enabled: false }
}
```

## Backend Selection Priority

The queue backend is selected based on which backend is enabled, in the following priority order:

1. **MapDB** - if `queueMapDB.enabled` is `true` (default for production)
2. **MariaDB** - if `queueMariaDB.enabled` is `true` (and MapDB is disabled)
3. **PostgreSQL** - if `queuePgSQL.enabled` is `true` (and MapDB and MariaDB are disabled)
4. **InMemory** - fallback when all backends are disabled (default for tests)

**Note:** Only one backend should be enabled at a time. If multiple backends are enabled, the selection follows the priority order above.

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

### Example 1: MapDB (Production Default)
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

### Example 4: InMemory (Test Configuration)
```json5
{
  queueInitialDelay: 10,
  queueInterval: 30,
  maxDequeuePerTick: 10,
  concurrencyScale: 32,

  queueMapDB: { enabled: false },
  queueMariaDB: { enabled: false },
  queuePgSQL: { enabled: false }
}
```

## Implementation Details

### Architecture
All queue backends implement the `QueueDatabase` interface, providing:
- FIFO queue operations (enqueue, dequeue, peek)
- Size and empty checks
- Snapshot for read-only inspection
- Item removal by index or UID
- Clear all items

### SQL Backends
MariaDB and PostgreSQL backends extend `AbstractSQLQueueDatabase`, which provides:
- Automatic table creation on initialization
- PreparedStatement usage to prevent SQL injection
- Java object serialization for queue items
- Connection management and error handling

### Selection Logic
The `QueueFactory` class handles backend selection based on configuration. Production code uses `PersistentQueue.getInstance()` which delegates to the factory for backend selection. Test code with all backends disabled automatically uses the in-memory implementation.

**Note:** The InMemory backend is automatically selected when all persistence backends are disabled, making it ideal for unit tests and development environments where persistence is not needed.
