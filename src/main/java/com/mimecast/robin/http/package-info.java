/**
 * A simple HTTP client for making requests.
 *
 * <p>HTTP/S cases leverage an HTTP client instead of an SMTP one to make API calls and assert using the external assertions.
 * <br>The purpose if this is to test any API endpoints your MTA might have.
 *
 * <p>This provided some basic functionalities but can leverage the external assertion implementations.
 *
 * <p>Response headers are set as magic variables.
 *
 * @see com.mimecast.robin.http.HttpClient
 */
package com.mimecast.robin.http;

