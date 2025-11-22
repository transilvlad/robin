/**
 * A simple HTTP client for making requests.
 *
 * <p>HTTP/S cases leverage an HTTP client instead of an SMTP one to make API calls and assert using the external assertions.
 * <br>The purpose of this is to test any API endpoints your MTA might have.
 *
 * <p>The {@link com.mimecast.robin.http.HttpRequest} is a lightweight programmatic container
 * <br>used to assemble HTTP requests prior to execution by the HTTP client implementations.
 * <br>It stores the URL, HTTP method, headers, parameters, files, content, and binary objects.
 * <br>Supports GET, POST (form parameters, JSON content, multipart uploads), and binary payloads.
 *
 * <p>The {@link com.mimecast.robin.http.HttpClient} executes HTTP requests and returns responses.
 * <br>This provides basic functionalities but can leverage the external assertion implementations.
 *
 * <p>Response headers are set as magic variables for use in test cases.
 *
 * @see com.mimecast.robin.http.HttpRequest
 * @see com.mimecast.robin.http.HttpClient
 */
package com.mimecast.robin.http;

