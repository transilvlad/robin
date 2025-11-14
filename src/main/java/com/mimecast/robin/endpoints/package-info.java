/**
 * HTTP endpoints for service monitoring and metrics.
 *
 * <p>This package contains the HTTP endpoints for service monitoring and metrics.
 * <br>They are served by a lightweight HTTP server and provide insights into the application's performance and state.
 *
 * <p>All endpoints are available under the port configured in {@code server.json5} - {@code servicePort} parameter.
 *
 * <p>The service endpoints support HTTP authentication for securing access to sensitive configuration, metrics and diagnostic information.
 * <br>To enable authentication, configure the {@code service} object in {@code server.json5}.
 *
 * @see com.mimecast.robin.main.Server
 */
package com.mimecast.robin.endpoints;

