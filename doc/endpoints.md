Monitoring Endpoints
====================

This document outlines the monitoring and metrics endpoints provided by the application.
These endpoints are served by a lightweight HTTP server and provide insights into the application's performance and state.

All endpoints are available under the port configured in `server.json5` - `metricsPort` parameter.


Endpoints
---------
The following endpoints are available:

- **/** - Provides a simple discovery mechanism by listing all available endpoints.
    - **Content-Type**: `text/plain`

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
