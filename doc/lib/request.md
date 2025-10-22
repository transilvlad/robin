HTTP Request client
===================

This document describes `HttpRequest`, a lightweight programmatic container
used across Robin to assemble HTTP requests prior to execution by the project's
HTTP client implementations.

Overview
--------

`HttpRequest` stores these pieces:

- url: the request URL (immutable after construction).
- method: HTTP method (defaults to GET).
- headers: map of header-name -> value.
- params: map of parameter-name -> value (typically used for form-encoded POST).
- files: map of param -> (filePath, mimeType) pairs used for multipart uploads.
- content: textual payload represented as (contentString, mimeType).
- object: binary payload represented as (byte[], mimeType).

Programmatic usage
------------------

Below are typical usage examples using the project's classes.

### Simple GET request

```java
import com.mimecast.robin.http.HttpRequest;
import com.mimecast.robin.http.HttpMethod;

HttpRequest request = new HttpRequest("https://api.example.com/resource");
request.addHeader("Accept", "application/json");
// Use your HTTP client to execute the request. For example, if you have an
// HttpClient instance:
// HttpResponse response = httpClient.execute(request);
```

### POST form parameters

```java
HttpRequest request = new HttpRequest("https://api.example.com/submit", HttpMethod.POST);
request.addHeader("Accept", "application/json");
request.addParam("name", "Alice");
request.addParam("email", "alice@example.com");
// Execute with your HttpClient instance.
```

### POST with JSON content

```java
HttpRequest request = new HttpRequest("https://api.example.com/items", HttpMethod.POST);
String json = "{\"title\":\"Hello\",\"body\":\"World\"}";
request.addHeader("Accept", "application/json");
request.addContent(json, "application/json");
// Execute request.
```

### Multipart upload (file)

```java
HttpRequest request = new HttpRequest("https://api.example.com/upload", HttpMethod.POST);
request.addFile("file", "/path/to/file.txt", "text/plain");
request.addParam("description", "Sample upload");
// Execute request.
```

### Binary object

```java
byte[] data = ...; // build or read bytes
HttpRequest request = new HttpRequest("https://api.example.com/data", HttpMethod.POST);
request.addObject(data, "application/octet-stream");
// Execute request.
```
