Prometheus Remote Write
=======================

Overview
--------
Robin can push its Micrometer metrics to a Prometheus-compatible backend using the Prometheus Remote Write protocol.
This is useful when Robin runs where scraping is not ideal (firewalled, ephemeral, or short-lived instances) or when you prefer a push-based flow.

- Push job: MetricsCron (started automatically during server startup)
- Registry source: Micrometer registries created by MetricsEndpoint
- Protocol: Prometheus Remote Write (protobuf) with Snappy framed compression (configurable)
- Transport: HTTP POST with appropriate headers

Where to configure
------------------
Create a file named `prometheus.json5` next to your `server.json5` (for example in `cfg/`).

- Typical location:
  - `cfg/prometheus.json5` (for default/container deployments)

Quick start
-----------
1. Create `cfg/prometheus.json5`:

```
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

  // Tip: Variables below are supported via Magic replacement.

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
  tenantHeaderValue: "",
}
```

2. Start Robin normally. The push cron will begin after the metrics endpoint initializes.

3. Verify on your backend that series are received (check tenant/headers if using a multi-tenant setup).

Configuration reference
-----------------------
All properties are optional unless specified. Shown here with defaults when omitted.

- `enabled`: boolean (default false)
  - Enables the periodic remote write push.

- `remoteWriteUrl`: string (required when enabled)
  - The HTTP endpoint that accepts Prometheus Remote Write payloads.

- `intervalSeconds`: number (default 15)
  - Period between pushes.

- `timeoutSeconds`: number (default 10)
  - HTTP call timeout for a single push.

- `compress`: boolean (default true)
  - When true, payload is Snappy framed compressed and header `Content-Encoding: snappy` is added.
  - When false, payload is sent uncompressed and the `Content-Encoding` header is omitted.
  - Many receivers expect snappy; disable only if your receiver explicitly supports uncompressed Remote Write.

- `labels`: object<string,string> (default empty)
  - Static labels appended to every time series; `job` and `instance` are commonly used.

- `headers`: object<string,string> (default empty)
  - Additional HTTP headers to include (for example `X-Scope-OrgID`).

- `bearerToken`: string (default empty)
  - If set, adds `Authorization: Bearer <token>`.

- `basicAuthUser`: string (default empty)
- `basicAuthPassword`: string (default empty)
  - If set (and bearerToken is empty), adds `Authorization: Basic <base64(user:pass)>`.

- `tenantHeaderName`: string (default empty)
- `tenantHeaderValue`: string (default empty)
  - Convenience pair for a single tenant header. Equivalent to adding it under `headers`.

- `include`: array<string> (default empty)
  - Regex filters for metric names. If non-empty, only metrics matching at least one include pattern are sent.

- `exclude`: array<string> (default empty)
  - Regex filters to exclude metrics by name. Applied after includes.

Notes on metrics mapping
------------------------
- Metric names are derived from Micrometer ids with dots replaced by underscores: `a.b.c` -> `a_b_c`.
- Each Micrometer measurement (statistic) becomes a separate sample labeled with `stat` (e.g., `count`, `sum`, `value`).
- Timestamps are recorded in milliseconds since epoch.

Troubleshooting
---------------
- Nothing is pushed
  - Ensure `enabled: true` and `remoteWriteUrl` is set.
  - Check include/exclude filters. Filters are applied to normalized names (underscores, not dots).

- 415 Unsupported Media Type or compression errors
  - If your backend doesn’t support `Content-Encoding: snappy`, set `compress: false`.
  - If it requires snappy, keep `compress: true` (default).

- 401/403, 404
  - Configure auth and check paths as per your backend.

Security
--------
- Prefer `bearerToken` over Basic Auth when possible.
- Do not commit secrets into source control; use environment magic or a secret management approach if applicable in your environment.
- Be mindful of multi-tenant isolation; set the correct tenant header where required.

Operational notes
-----------------
- The cron is designed to be resilient: on errors it logs the issue and tries again at the next interval.
- Push is skipped if there are zero series after filtering.
- Remote write is independent of the `/prometheus` scrape endpoint; you can use both.


Library Usage
=============

`MetricsCron` can be used as a standalone library in other Java applications to push Micrometer metrics to a Prometheus-compatible backend.

Basic Usage
-----------

To integrate `MetricsCron` into your application:

```java
import com.mimecast.robin.metrics.MetricsCron;
import com.mimecast.robin.config.BasicConfig;
import java.util.Map;

// Create a BasicConfig instance with Prometheus remote write settings
Map<String, Object> prometheusConfig = new HashMap<>();
prometheusConfig.put("enabled", true);
prometheusConfig.put("remoteWriteUrl", "http://localhost:9201/api/v1/write");
prometheusConfig.put("intervalSeconds", 15);
prometheusConfig.put("timeoutSeconds", 10);
prometheusConfig.put("compress", true);
prometheusConfig.put("labels", Map.of(
    "job", "my-app",
    "instance", "host.example.com"
));

BasicConfig config = new BasicConfig(prometheusConfig);

// Start the metrics push cron
MetricsCron.run(config);
```

The cron will begin pushing metrics immediately after initialization (with the first push delayed by `intervalSeconds` to allow the Prometheus registry to initialize).

Configuration via BasicConfig
------------------------------

`MetricsCron.run()` accepts a `BasicConfig` instance that provides access to the prometheus configuration properties.
The configuration is read using these methods:

- `config.getBooleanProperty(key, defaultValue)` - For booleans (enabled, compress)
- `config.getLongProperty(key, defaultValue)` - For numeric values (intervalSeconds, timeoutSeconds)
- `config.getStringProperty(key, defaultValue)` - For strings (remoteWriteUrl, bearerToken, etc.)
- `config.getMapProperty(key)` - For object maps (labels, headers)
- `config.getListProperty(key)` - For arrays (include, exclude filter patterns)

Example with filters and authentication:

```java
Map<String, Object> prometheusConfig = new HashMap<>();
prometheusConfig.put("enabled", true);
prometheusConfig.put("remoteWriteUrl", "https://metrics.example.com/write");
prometheusConfig.put("intervalSeconds", 30);
prometheusConfig.put("timeoutSeconds", 15);
prometheusConfig.put("compress", true);

// Include only JVM and process metrics
prometheusConfig.put("include", List.of(
    "^jvm_.*",
    "^process_.*"
));
prometheusConfig.put("exclude", List.of(
    ".*_total$"
));

// Static labels
prometheusConfig.put("labels", Map.of(
    "job", "my-app",
    "environment", "production",
    "region", "us-east-1"
));

// Authentication
prometheusConfig.put("bearerToken", "my-secret-token");

// Optional extra headers
prometheusConfig.put("headers", Map.of(
    "X-Custom-Header", "value"
));

// Multi-tenant backend support
prometheusConfig.put("tenantHeaderName", "X-Scope-OrgID");
prometheusConfig.put("tenantHeaderValue", "org-123");

BasicConfig config = new BasicConfig(prometheusConfig);
MetricsCron.run(config);
```

Important Notes
---------------

- **Registry Initialization**: Ensure your Micrometer `PrometheusMeterRegistry` is initialized and available via `MetricsRegistry.getPrometheusRegistry()` before calling `MetricsCron.run()`.
  - If using `MetricsEndpoint`, it automatically initializes the registry.
  - For other setups, register meters with your registry before starting the cron.

- **Thread Safety**: `MetricsCron.run()` is synchronized and safe to call multiple times; subsequent calls are ignored if already running.

- **Shutdown**: Shutdown hooks are automatically registered; the scheduler will gracefully shutdown when the JVM terminates.

- **Magic Replacement**: String values in configuration (labels, headers, bearerToken, etc.) support Magic variable replacement (e.g., `{$hostname}`, `{$timestamp}`).
                         Magic variables are ideal for injecting dynamic values without hardcoding. Set a custom session factory provide your own magic variables.

- **Metric Name Normalization**: Metric names use underscores instead of dots in the include/exclude filters.
  - Example: filter `^jvm_memory_.*` matches Micrometer metric `jvm.memory.used`.

Example: Integration with MetricsEndpoint
-----------------------------------------

If you're already using `MetricsEndpoint` in your application, you can add Prometheus remote write without additional registry setup:

```java
import com.mimecast.robin.endpoints.MetricsEndpoint;
import com.mimecast.robin.metrics.MetricsCron;
import com.mimecast.robin.config.BasicConfig;

// Start metrics endpoint.
MetricsEndpoint metricsEndpoint = new MetricsEndpoint();
metricsEndpoint.start(8090);

// Configure and start Prometheus remote write.
Map<String, Object> prometheusConfig = Map.of(
    "enabled", true,
    "remoteWriteUrl", "http://localhost:9201/api/v1/write",
    "intervalSeconds", 15,
    "labels", Map.of("job", "my-app")
);
MetricsCron.run(new BasicConfig(prometheusConfig));
// Now metrics are both scraped at /prometheus and pushed to the remote backend.
```

Integration with Configuration File
-----------------------------------

If you're loading configuration from JSON5 files, parse the prometheus config and pass it to `MetricsCron`:

```java
// Start the cron.
MetricsCron.run(new BasicConfig("path_to_prometheus_config.json5"));
```
