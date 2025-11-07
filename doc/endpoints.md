Monitoring Endpoints
====================

This document outlines the monitoring and metrics endpoints provided by the application.
These endpoints are served by a lightweight HTTP server and provide insights into the application's performance and state.

All endpoints are available under the port configured in `server.json5` - `metricsPort` parameter.

<img src="img/endpoint-metrics.jpg" alt="Metrics Endpoints Diagram" style="max-width: 1200px;"/>

Authentication
--------------

The metrics endpoints support HTTP authentication for securing access to sensitive metrics and diagnostic information.

To enable authentication, configure the `metrics` object in `server.json5`.
Make use of magic to load secrets, see [Secrets, magic and Local Secrets File](secrets.md).

**Do NOT commit real secrets into the repository!!!**

### Configuration Format

```json5
{
  metrics: {
    port: 8080,
    
    // Authentication type: none, basic, bearer
    authType: "basic",
    
    // Authentication value
    // For basic: "username:password"
    // For bearer: "token"
    authValue: "{$metricsUsername}:{$metricsPassword}",
    
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
  metrics: {
    port: 8080,
    authType: "basic",
    authValue: "admin:secretPassword123"
  }
}
```

**Bearer Token Authentication:**
```json5
{
  metrics: {
    port: 8080,
    authType: "bearer",
    authValue: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
  }
}
```

**IP Allow List Only (Local Access):**
```json5
{
  metrics: {
    port: 8080,
    authType: "none",
    allowList: ["127.0.0.1", "::1"]
  }
}
```

**Combined Authentication and Allow List:**
```json5
{
  metrics: {
    port: 8080,
    authType: "bearer",
    authValue: "{$metricsToken}",
    allowList: ["10.0.0.0/8"]
  }
}
```

When authentication is enabled, all endpoints except `/health` require valid credentials or an allowed IP address.

Endpoints
---------
The following endpoints are available:

- **/** - Provides a simple discovery mechanism by listing all available endpoints.
    - **Content-Type**: `text/html; charset=utf-8`

- **/metrics** - This UI fetches data from the `/metrics` endpoint and renders it as a series of charts. It is built using the Chart.js library for visualization.
    - **Content-Type**: `text/html; charset=utf-8`

- **/graphite** - Exposes metrics in the Graphite format. This format is suitable for consumption by Graphite servers and other compatible monitoring tools.
    - **Content-Type**: `text/plain`
    - **Example**:
        ```
        jvm_memory_used 55941680 1678886400
        jvm_memory_max 2147483648 1678886400
        process_cpu_usage 0.015625 1678886400
        ```

- **/prometheus** - Exposes metrics in the Prometheus exposition format, suitable for consumption by Prometheus servers.
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
    - **Example**:
        ```
        "main" #1 prio=5 state=RUNNABLE
           at java.base@11.0.12/java.lang.Thread.dumpThreads(Native Method)
           at java.base@11.0.12/java.lang.Thread.getAllStackTraces(Thread.java:1610)
           ...

        "Reference Handler" #2 prio=10 state=RUNNABLE
           at java.base@11.0.12/java.lang.ref.Reference.waitForReferencePendingList(Native Method)
           at java.base@11.0.12/java.lang.ref.Reference.processPendingReferences(Reference.java:241)
           ...
        ```

- **`/heapdump`** - Triggers a heap dump programmatically and saves it to a file in the application's working directory. This is an advanced diagnostic tool for memory leak analysis.
    - **Content-Type**: `text/plain`
    - **Example**:
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

Client Submission Endpoint
--------------------------

<img src="img/endpoint-client.jpg" alt="Metrics Endpoints Diagram" style="max-width: 1200px;"/>

All client endpoints are available under the port configured in `server.json5` - `api` object.

Authentication
--------------

The client API endpoints support HTTP authentication for securing access to submission and queue management operations.

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

When authentication is enabled, all endpoints except `/client/health` require valid credentials or an allowed IP address.

Endpoints
---------

- **/** - Provides a simple discovery mechanism by listing all available client endpoints.
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

- **`POST /client/queue`** — Queues a client case for later delivery via the relay queue.
  - Accepts the same inputs as `/client/send`
  - Optional query parameters:
    - `protocol` - Override relay protocol (default: ESMTP)
    - `mailbox` - Override target mailbox (default: from relay config)
  - Response: `application/json; charset=utf-8`
  - Returns a confirmation with queue size and the filtered Session object
  - HTTP status: `202 Accepted`

- **`GET /client/queue-list`** — Lists all items currently in the relay queue.
  - Response: `text/html; charset=utf-8`
  - Returns an HTML table showing queued sessions with details:
    - Session UID
    - Enqueue date
    - Protocol
    - Retry count and last retry time
    - Envelope count
    - Recipients (first 5 shown)
    - Files (first 5 shown)

- **`GET /client/health`** — Simple health check endpoint.
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

`MetricsEndpoint` can be used as a standalone library in other Java applications to expose metrics and monitoring endpoints.

Basic Usage
-----------

To integrate `MetricsEndpoint` into your application:

```java
import com.mimecast.robin.endpoints.MetricsEndpoint;

// In your application initialization method:
MetricsEndpoint metricsEndpoint = new MetricsEndpoint();
metricsEndpoint.start(8080); // Start on port 8080.
```

The endpoint will expose all standard monitoring endpoints (`/metrics`, `/prometheus`, `/graphite`, `/health`, etc.) on the specified port.

Extending MetricsEndpoint
--------------------------

To create a custom metrics endpoint with application-specific statistics, extend `MetricsEndpoint`:

```java
import com.mimecast.robin.endpoints.MetricsEndpoint;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.time.Duration;

public class CustomMetricsEndpoint extends MetricsEndpoint {
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

`MetricsEndpoint` provides the following protected members for extension:

- `protected HttpServer server` - The underlying HTTP server instance for context creation.
- `protected final long startTime` - Application start time in milliseconds.
- `protected void handleHealth(HttpExchange exchange)` - Override to customize the `/health` endpoint.
- `protected void createContexts()` - Override to customize the HTTP context creation.
- `protected void sendResponse(HttpExchange exchange, int code, String contentType, String response)` - Send HTTP responses.
- `protected void sendError(HttpExchange exchange, int code, String message)` - Send HTTP error responses.
