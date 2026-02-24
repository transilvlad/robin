Monitoring Endpoints
====================

This document outlines the monitoring and service endpoints provided by the application.
These endpoints are served by a lightweight HTTP server and provide insights into the application's performance and state.

<img src="img/endpoint-service.jpg" alt="Service Endpoints Diagram" style="max-width: 1200px;"/>

Authentication
--------------

The service endpoints support HTTP authentication for securing access to sensitive configuration, metrics and diagnostic information.

To enable authentication, configure the `service` object in `server.json5`.
Make use of magic to load secrets, see [Secrets, magic and Local Secrets File](secrets.md).

**Do NOT commit real secrets into the repository!!!**

### Configuration Format

```json5
{
  service: {
    port: 8080,
    
    // Authentication type: none, basic, bearer
    authType: "basic",
    
    // Authentication value
    // For basic: "username:password"
    // For bearer: "token"
    authValue: "{$serviceUsername}:{$servicePassword}",
    
    // IP addresses or CIDR blocks allowed without authentication
    allowList: [
      "127.0.0.1",
      "::1",
      "192.168.1.0/24"
    ]
  }
}
```

### Authentication Types

- **none**: No authentication required (default if `authValue` is empty).
- **basic**: HTTP Basic Authentication using username:password format.
- **bearer**: HTTP Bearer Token Authentication using a token string.

### IP Allow List

The `allowList` parameter accepts IP addresses or CIDR blocks that can access endpoints without authentication:

- IPv4 addresses: `"192.168.1.10"`
- IPv6 addresses: `"::1"`
- IPv4 CIDR blocks: `"192.168.1.0/24"`, `"10.0.0.0/8"`
- IPv6 CIDR blocks: `"2001:db8::/32"`

When both authentication and an allow list are configured:
1. If the request comes from an IP in the allow list, access is granted without checking credentials.
2. Otherwise, authentication is required.

### Examples

**Basic Authentication:**
```json5
{
  service: {
    port: 8080,
    authType: "basic",
    authValue: "admin:secretPassword123"
  }
}
```

**Bearer Token Authentication:**
```json5
{
  service: {
    port: 8080,
    authType: "bearer",
    authValue: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
  }
}
```

**IP Allow List Only (Local Access):**
```json5
{
  service: {
    port: 8080,
    authType: "none",
    allowList: ["127.0.0.1", "::1"]
  }
}
```

**Combined Authentication and Allow List:**
```json5
{
  service: {
    port: 8080,
    authType: "bearer",
    authValue: "{$serviceToken}",
    allowList: ["10.0.0.0/8"]
  }
}
```

When authentication is enabled, all endpoints except `/health` require valid credentials or an allowed IP address.

Endpoints
---------

The service endpoints are provided by two classes:

1. **ServiceEndpoint** - Base class providing standard endpoints at root level
2. **RobinMetricsEndpoint** - Robin's implementation that overrides and reorganizes endpoints into logical groups

ServiceEndpoint Base Paths
--------------------------
When using the base `ServiceEndpoint` directly, endpoints are available at:

- **/** - Provides a simple discovery mechanism by listing all available endpoints.
    - **Content-Type**: `text/html; charset=utf-8`

- **/metrics** - Metrics UI that fetches data from the `/graphite` endpoint and renders it as a series of charts using Chart.js.
    - **Content-Type**: `text/html; charset=utf-8`

- **/metrics/graphite** - Exposes metrics in the Graphite format. This format is suitable for consumption by Graphite servers and other compatible monitoring tools.
    - **Content-Type**: `text/plain`
    - **Example**:
        ```
        jvm_memory_used 55941680 1678886400
        jvm_memory_max 2147483648 1678886400
        process_cpu_usage 0.015625 1678886400
        ```

- **/metrics/prometheus** - Exposes metrics in the Prometheus exposition format, suitable for consumption by Prometheus servers.
    - **Content-Type**: `text/plain`
    - **Example**:
        ```
        # HELP jvm_memory_used_bytes The amount of used memory
        # TYPE jvm_memory_used_bytes gauge
        jvm_memory_used_bytes{area="heap",id="G1 Eden Space",} 2.490368E7
        jvm_memory_used_bytes{area="heap",id="G1 Old Gen",} 5.594168E7
        # HELP process_cpu_usage The "recent cpu usage" for the Java Virtual Machine process
        # TYPE process_cpu_usage gauge
        process_cpu_usage 0.015625
        ```

- **`/env`** - Exposes the system environment variables. This is useful for diagnosing configuration issues related to the environment the application is running in.
    - **Content-Type**: `text/plain; charset=utf-8`
    - **Example**:
        ```
        PATH=/usr/local/bin:/usr/bin:/bin
        HOME=/home/user
        JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
        ```

- **`/sysprops`** - Exposes the Java system properties (`-D` flags, etc.). This helps verify JVM-level settings.
    - **Content-Type**: `text/plain; charset=utf-8`
    - **Example**:
        ```
        java.version=11.0.12
        java.vendor=Oracle Corporation
        os.name=Linux
        user.dir=/app
        ```

- **/threads** - Provides a standard Java thread dump, which is useful for diagnosing deadlocks, contention, or other threading-related issues. The format is similar to what `jstack` would produce.
    - **Content-Type**: `text/plain; charset=utf-8`

- **`/heapdump`** - Triggers a heap dump programmatically and saves it to a file. This is an advanced diagnostic tool for memory leak analysis.
    - **Content-Type**: `text/plain`

- **/health** - Returns the application's health status (always available, no authentication required).
    - **Content-Type**: `application/json; charset=utf-8`
    - **Example**:
        ```json
        {"status":"UP", "uptime":"0d 0h 5m 32s"}
        ```

RobinMetricsEndpoint Reorganized Paths
--------------------------------------
When using Robin's `RobinMetricsEndpoint`, endpoints are reorganized into logical groups:

**Configuration Endpoints:**

- **/config** - Configuration viewer UI showing current `properties.json5` and `server.json5` configurations in formatted JSON with reload button.
    - **Content-Type**: `text/html; charset=utf-8`

- **/config/reload** - Triggers immediate reload of configuration files via POST request (thread-safe, uses single-threaded scheduler).
    - **Method**: `POST`
    - **Content-Type**: `application/json; charset=utf-8`
    - **Success Response**:
        ```json
        {"status":"OK", "message":"Configuration reloaded successfully"}
        ```
    - **Error Response**:
        ```json
        {"status":"ERROR", "message":"Failed to reload configuration: <error details>"}
        ```

**System Endpoints** (grouped under `/system/`):

- **/system/env** - Exposes the system environment variables.
    - **Content-Type**: `text/plain; charset=utf-8`

- **/system/props** - Exposes the Java system properties.
    - **Content-Type**: `text/plain; charset=utf-8`

- **/system/threads** - Provides a Java thread dump.
    - **Content-Type**: `text/plain; charset=utf-8`

- **/system/heapdump** - Triggers the creation of a heap dump file.
    - **Content-Type**: `text/plain`

**Metrics Endpoints** (grouped under `/metrics/`):

- **/metrics** - Metrics UI that fetches data from the `/metrics/graphite` endpoint and renders charts using Chart.js.
    - **Content-Type**: `text/html; charset=utf-8`

- **/metrics/graphite** - Exposes metrics in Graphite format.
    - **Content-Type**: `text/plain`

- **/metrics/prometheus** - Exposes metrics in Prometheus exposition format.
    - **Content-Type**: `text/plain`

**Core Endpoints:**

- **/** - Provides a simple discovery mechanism by listing all available endpoints.
    - **Content-Type**: `text/html; charset=utf-8`

- **/health** - Returns the application's health status with Robin-specific statistics (always available, no authentication required).
    - **Content-Type**: `application/json; charset=utf-8`
    - **Example**:
        ```json
        {"status":"UP", "uptime":"0d 0h 5m 32s", "listeners":[...], "queue":{...}, "scheduler":{...}, "metricsCron":{...}}
        ```
        ```
        Heap dump created at: heapdump-1678886400000.hprof
        ```

- **`/health`** - Provides a health check of the application, including its status, uptime, SMTP listener details (with thread pool stats), and queue/scheduler information.
    - **Content-Type**: `application/json; charset=utf-8`
    - **Example**:
        ```json
        {
          "status": "UP",
          "uptime": "4d 2h 7m 5s",
          "listeners": [
            {
              "port": 25,
              "threadPool": {
                "core": 2,
                "max": 50,
                "size": 8,
                "largest": 10,
                "active": 6,
                "queue": 0,
                "taskCount": 12345,
                "completed": 12200,
                "keepAliveSeconds": 60
              }
            },
            {
              "port": 587,
              "threadPool": {
                "core": 2,
                "max": 50,
                "size": 3,
                "largest": 5,
                "active": 2,
                "queue": 0,
                "taskCount": 2345,
                "completed": 2300,
                "keepAliveSeconds": 60
              }
            }
          ],
          "queue": {
            "size": 7,
            "retryHistogram": {
              "0": 4,
              "1": 2,
              "2": 1
            }
          },
          "scheduler": {
            "config": {
              "totalRetries": 10,
              "firstWaitMinutes": 5,
              "growthFactor": 2.00
            },
            "cron": {
              "initialDelaySeconds": 10,
              "periodSeconds": 30,
              "lastExecutionEpochSeconds": 1697577600,
              "nextExecutionEpochSeconds": 1697577630
            }
          }
        }
        ```

API Endpoint
-------------

<img src="img/endpoint-client.jpg" alt="API Endpoints Diagram" style="max-width: 1200px;"/>

All API endpoints are available under the port configured in `server.json5` - `api` object.

Authentication
--------------

The API endpoints support HTTP authentication for securing access to submission and queue management operations.

To enable authentication, configure the `api` object in `server.json5`.
Make use of magic to load secrets, see [Secrets, magic and Local Secrets File](secrets.md).

**Do NOT commit real secrets into the repository!!!**

### Configuration Format

```json5
{
  api: {
    port: 8090,
    
    // Authentication type: none, basic, bearer
    authType: "basic",
    
    // Authentication value
    // For basic: "username:password"
    // For bearer: "token"
    authValue: "{$apiUsername}:{$apiPassword}",
    
    // IP addresses or CIDR blocks allowed without authentication
    allowList: [
      "127.0.0.1",
      "::1",
      "192.168.1.0/24"
    ]
  }
}
```

### Authentication Types

- **none**: No authentication required (default if `authValue` is empty).
- **basic**: HTTP Basic Authentication using username:password format.
- **bearer**: HTTP Bearer Token Authentication using a token string.

### IP Allow List

The `allowList` parameter accepts IP addresses or CIDR blocks that can access endpoints without authentication:

- IPv4 addresses: `"192.168.1.10"`
- IPv6 addresses: `"::1"`
- IPv4 CIDR blocks: `"192.168.1.0/24"`, `"10.0.0.0/8"`
- IPv6 CIDR blocks: `"2001:db8::/32"`

When both authentication and an allow list are configured:
1. If the request comes from an IP in the allow list, access is granted without checking credentials.
2. Otherwise, authentication is required.

### Examples

**Basic Authentication Request:**

```bash
curl -u admin:secretPassword -X POST \
  -H "Content-Type: application/json" \
  -d @testcase.json5 \
  http://localhost:8090/client/send
```

**Bearer Token Authentication Request:**

```bash
curl -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -X POST \
  -H "Content-Type: application/json" \
  -d @testcase.json5 \
  http://localhost:8090/client/send
```

When authentication is enabled, all endpoints except `/health` require valid credentials or an allowed IP address.

Endpoints
---------

- **/** - Provides a simple discovery mechanism by listing all available API endpoints.
    - **Content-Type**: `text/html; charset=utf-8`

- **`POST /client/send`** — Executes a client case and returns the final SMTP session as JSON.
  - Accepts either:
    - A query parameter `path` with an absolute/relative path to a JSON/JSON5 case file, e.g. `?path=src/test/resources/case.json5`
    - A raw JSON/JSON5 payload in the request body describing the case
  - For body mode, set `Content-Type: application/json`
  - Response: `application/json; charset=utf-8`
  - The JSON returned is the serialized Session filtered to exclude:
    - `Session.magic`
    - `Session.savedResults`
    - `MessageEnvelope.stream`
    - `MessageEnvelope.bytes`
  - Error responses:
    - `400` for invalid/missing input
    - `500` on execution errors (e.g., assertion failures or runtime exceptions)
  - Raw MIME upload mode is also supported when `Content-Type` is one of:
    - `multipart/form-data`
    - `message/rfc822`
    - `application/octet-stream`
  - Raw upload requires query params:
    - `mail`
    - `rcpt` (comma-separated)

- **`POST /client/queue`** — Queues a client case for later delivery via the relay queue.
  - Accepts the same inputs as `/client/send`
  - Optional query parameters:
    - `protocol` - Override relay protocol (default: ESMTP)
    - `mailbox` - Override target folder for Dovecot LDA delivery (default: from dovecot config inboxFolder/sentFolder)
  - Response: `application/json; charset=utf-8`
  - Returns a confirmation with queue size and the filtered Session object
  - HTTP status: `202 Accepted`

- **`GET /client/queue/list`** — Lists all items currently in the relay queue.
  - Response: `text/html; charset=utf-8`
  - Returns an HTML table showing queued sessions with details:
    - Session UID
    - Enqueue date
    - Protocol
    - Retry count and last retry time
    - Envelope count
    - Recipients (first 5 shown)
    - Files (first 5 shown)

- **`GET /logs`** — Searches log files for lines matching a query string.
  - Query parameters:
    - `query` or `q` - Search term to find in log files (required)
  - Response: `text/plain; charset=utf-8`
  - Searches the current and previous log4j2 log files
  - The log file location is automatically determined from the log4j2 configuration
  - Returns matching lines as plain text
  - If no query parameter is provided, returns usage information
  - Example:

- **`GET /users`** — Returns users from the active authentication backend.
  - Response: `application/json; charset=utf-8`
  - Backend selection:
    - If `dovecot.authSql.enabled=true`, users are fetched using `dovecot.authSql.usersQuery`
    - Else if `users.listEnabled=true`, users are fetched from `users.json5`
  - Returns backend source, count, and list of user identifiers

- **`GET /users/{username}/exists`** — Checks if a specific user exists.
  - Response: `application/json; charset=utf-8`
  - Returns backend source, username, and boolean `exists`

- **`POST /users/authenticate`** — Validates posted credentials.
  - Request body: JSON
    - `username` (required)
    - `password` (required)
  - Response: `application/json; charset=utf-8`
  - Returns backend source, username, and boolean `authenticated`

- **`GET /store[/path]`** — Browse local message storage directory.
  - Path parameter: relative path within the configured storage directory
  - Response content varies based on the requested path:
    - **Directory listings**: `text/html; charset=utf-8` - Interactive HTML page with clickable links
    - **Individual .eml files**: `text/plain; charset=utf-8` - Raw email content
    - **If `Accept: application/json` is sent**:
      - Directories return JSON (`path`, `items`)
      - `.eml` files return JSON (`path`, `name`, `size`, `content`)
  - Only `.eml` files are served; other file types return 404
  - Empty directories (containing no `.eml` files recursively) are not shown
  - Directory traversal protection prevents access outside the storage path
  - Uses the storage path configured in `storage.json5` (defaults to `/tmp/store`)
  - HTML listings use the same styling as other API endpoints
  - Examples:
    - `/store/` - List storage root directory
    - `/store/example.com/` - List subdirectory
    - `/store/example.com/user/new/renamed.eml` - View email content
    ```bash
    curl "http://localhost:8090/logs?query=ERROR"
    curl "http://localhost:8090/logs?q=session"
    ```

- **`GET /health`** — Simple health check endpoint.
  - Response: `application/json; charset=utf-8`
  - Returns: `{"status":"UP"}`
  - This endpoint is always accessible without authentication

Examples
--------

- Execute from a case file path:
  - Bash/Linux/macOS:
    ```
    curl -X POST "http://localhost:8090/client/send?path=/home/user/cases/sample.json5"
    ```
  - Windows CMD:
    ```
    curl -X POST "http://localhost:8090/client/send?path=D:/work/robin/src/test/resources/case.json5"
    ```
  - PowerShell:
    ```powershell
    Invoke-RestMethod -Method Post -Uri 'http://localhost:8090/client/send?path=D:/work/robin/src/test/resources/case.json5'
    ```

- Execute from a JSON body (minimal example):
  - Bash/Linux/macOS:
    ```
    # Note: Use single quotes to avoid Bash history expansion (e.g., '!') and simplify quoting.
    curl -X POST \
        -H "Content-Type: application/json" \
        -d '{"mx":["127.0.0.1"],"port":25,"envelopes":[{"mail":"tony@example.com","rcpt":["pepper@example.com"],"subject":"Urgent","message":"Send Rescue!"}]}' \
        "http://localhost:8090/client/send"
    ```
  - Windows CMD:
    ```bat
    set "DATA={\"mx\":[\"127.0.0.1\"],\"port\":25,\"envelopes\":[{\"mail\":\"tony@example.com\",\"rcpt\":[\"pepper@example.com\"],\"subject\":\"Urgent\",\"message\":\"Send Rescue!\"}]}"
    curl -X POST -H "Content-Type: application/json" -d %DATA% "http://localhost:8090/client/send"
    ```
  - PowerShell:
    ```powershell
    $body = '{"mx":["127.0.0.1"],"port":25,"envelopes":[{"mail":"tony@example.com","rcpt":["pepper@example.com"],"subject":"Urgent","message":"Send Rescue!"}]}'
    Invoke-RestMethod -Method Post -Uri 'http://localhost:8090/client/send' -ContentType 'application/json' -Body $body
    ```


Library Usage
=============

`ServiceEndpoint` can be used as a standalone library in other Java applications to expose configmetrics and monitoring endpoints.

Basic Usage
-----------

To integrate `ServiceEndpoint` into your application:

```java
import com.mimecast.robin.endpoints.ServiceEndpoint;

// In your application initialization method:
ServiceEndpoint serviceEndpoint = new ServiceEndpoint();
serviceEndpoint.

        start(8080); // Start on port 8080.
```

The endpoint will expose all standard service endpoints (`/config`, `/config/reload`, `/metrics`, `/metrics/prometheus`, `/metrics/graphite`, `/system/env`, `/system/sysprops`, `/system/threads`, `/system/heapdump`, `/health`, etc.) on the specified port when using ServiceEndpoint directly.

Extending ServiceEndpoint
-------------------------

To create a custom service endpoint with application-specific statistics, extend `ServiceEndpoint`:

```java
import com.mimecast.robin.endpoints.ServiceEndpoint;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.time.Duration;

public class CustomServiceEndpoint extends ServiceEndpoint {
    @Override
    protected void handleHealth(HttpExchange exchange) throws IOException {
        // Call parent implementation or create custom response.
        Duration uptime = Duration.ofMillis(System.currentTimeMillis() - startTime);
        String customStats = ""; // Your custom JSON metrics here.
        String response = String.format(
                "{\"status\":\"UP\", \"uptime\":\"%s\", \"customData\":%s}",
                uptime, customStats);

        sendResponse(exchange, 200, "application/json; charset=utf-8", response);
    }
}
```

Protected Methods and Fields
----------------------------

`ServiceEndpoint` provides the following protected members for extension:

- `protected HttpServer server` - The underlying HTTP server instance for context creation.
- `protected final long startTime` - Application start time in milliseconds.
- `protected void handleHealth(HttpExchange exchange)` - Override to customize the `/health` endpoint.
- `protected void createContexts()` - Override to customize the HTTP context creation.
- `protected void sendResponse(HttpExchange exchange, int code, String contentType, String response)` - Send HTTP responses.
- `protected void sendError(HttpExchange exchange, int code, String message)` - Send HTTP error responses.
