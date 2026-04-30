# Robin API Reference

Robin provides two separate HTTP APIs running on different ports:

1. **API Endpoint** (default port 8090) - Client operations, queue management, user management, and storage operations
2. **Service Endpoint** (default port 8080) - Monitoring, metrics, diagnostics, and configuration management

Both APIs support HTTP authentication (Basic/Bearer) and IP allow lists. See the [endpoints documentation](../features/endpoints.md) for configuration details.

---

# API Endpoint (Port 8090)

The API endpoint provides client operations, queue management, user management, and storage access.

## 1) Authentication

### 1.1 API authentication

If Robin API requires auth, send one of:
- Basic: `Authorization: Basic <base64(user:pass)>`
- Bearer: `Authorization: Bearer <token>`

If auth is not required or the client IP is in the allow list, omit the header.

### 1.2 User authentication endpoint

`POST /users/authenticate`

Body (JSON):
- `username` (required, string)
- `password` (required, string)

```bash
curl -s -X POST \
  -H "Content-Type: application/json" \
  -d '{"username":"tony@example.com","password":"stark"}' \
  http://localhost:8090/users/authenticate
```

Response:
- `200`: `{"source":"config","username":"tony@example.com","authenticated":true}`
- `200`: `{"source":"config","username":"tony@example.com","authenticated":false}` (invalid credentials)
- `503`: `{"error":"Users API backend is not configured"}`

### 1.3 Check user exists

`GET /users/{username}/exists`

Response:
- `200`: `{"source":"config","username":"tony@example.com","exists":true}`
- `200`: `{"source":"config","username":"tony@example.com","exists":false}`
- `503`: `{"error":"Users API backend is not configured"}`

### 1.4 List users

`GET /users`

Response:
- `200`: `{"source":"config","count":3,"users":["tony@example.com","pepper@example.com","happy@example.com"]}`
- `503`: `{"error":"Users API backend is not configured"}`

## 2) Message sending

### 2.1 Immediate send

`POST /client/send`

Sends a message immediately via SMTP and returns the session result.

**JSON body mode:**

Body (JSON/JSON5):
- Full case definition with envelopes, routing, etc.

```bash
curl -s -X POST \
  -H "Content-Type: application/json" \
  -d '{"envelopes":[{"mail":"tony@example.com","rcpt":["pepper@example.com"],"file":"message.eml"}]}' \
  http://localhost:8090/client/send
```

**Raw EML upload mode:**

Query params:
- `mail` (required, envelope sender)
- `rcpt` (required, comma-separated envelope recipients)
- `route` (optional, target server)
- `filename` (optional, filename hint)

Supported content types:
- `message/rfc822`
- `application/octet-stream`
- `multipart/form-data`

```bash
curl -s -X POST \
  -H "Content-Type: message/rfc822" \
  --data-binary @message.eml \
  "http://localhost:8090/client/send?mail=tony@example.com&rcpt=pepper@example.com"
```

Response:
- `200`: Session object with send results
- `400`: `"Missing required query parameters: mail and rcpt"` or `"Empty upload body"`
- `500`: `"Internal Server Error: <details>"`

### 2.2 Queued send

`POST /client/queue`

Queues a message for later delivery via the relay queue.

Query params:
- `mail` (required for raw upload, envelope sender)
- `rcpt` (required for raw upload, comma-separated envelope recipients)
- `protocol` (optional, e.g. `ESMTP`, `LMTP`)
- `mailbox` (optional, target folder for Dovecot LDA delivery)

Body:
- JSON/JSON5 case definition, or raw EML content

```bash
curl -s -X POST \
  -H "Content-Type: application/octet-stream" \
  --data-binary @message.eml \
  "http://localhost:8090/client/queue?mail=tony@example.com&rcpt=pepper@example.com,happy@example.com&protocol=ESMTP"
```

Response:
- `202`: `{"status":"QUEUED","queueSize":12,"session":{...}}`
- `400`: Validation error
- `500`: `"Internal Server Error: <details>"`

## 3) Queue management

### 3.1 List queue

`GET /client/queue/list`

Lists all items in the relay queue with pagination.

Query params:
- `page` (optional, 1-based page number, default `1`)
- `limit` (optional, items per page, default `50`, max `1000`)

Response:
- `200`: HTML table with queue items (Session UID, date, protocol, retry count, envelopes, recipients, files)

### 3.2 Delete queue items

`POST /client/queue/delete`

Deletes items from the queue by UID.

Body (JSON):
- `uid` (string, single UID to delete), or
- `uids` (array of strings, multiple UIDs to delete)

```bash
curl -s -X POST \
  -H "Content-Type: application/json" \
  -d '{"uid":"abc123"}' \
  http://localhost:8090/client/queue/delete
```

Response:
- `200`: `{"status":"OK","deletedCount":1,"queueSize":11}`
- `400`: `"Missing 'uid' or 'uids' parameter"`

### 3.3 Retry queue items

`POST /client/queue/retry`

Re-queues items for immediate retry with bumped retry count.

Body (JSON):
- `uid` (string, single UID to retry), or
- `uids` (array of strings, multiple UIDs to retry)

```bash
curl -s -X POST \
  -H "Content-Type: application/json" \
  -d '{"uids":["abc123","def456"]}' \
  http://localhost:8090/client/queue/retry
```

Response:
- `200`: `{"status":"OK","retriedCount":2,"queueSize":12}`
- `400`: `"Missing 'uid' or 'uids' parameter"`

### 3.4 Bounce queue items

`POST /client/queue/bounce`

Removes items from the queue (bounce = delete in current implementation).

Body (JSON):
- `uid` (string, single UID to bounce), or
- `uids` (array of strings, multiple UIDs to bounce)

Response:
- `200`: `{"status":"OK","bouncedCount":1,"queueSize":11}`
- `400`: `"Missing 'uid' or 'uids' parameter"`

## 4) Log search

`GET /logs`

Searches current and previous log files for matching lines.

Query params:
- `query` or `q` (required, search term)

```bash
curl -s "http://localhost:8090/logs?query=ERROR"
curl -s "http://localhost:8090/logs?q=session"
```

Response:
- `200`: Plain text with matching log lines
- `400`: Usage information if query is missing
- `500`: `"Internal Server Error: Could not determine log file location"`

## 5) Health check

`GET /health`

Response:
- `200`: `{"status":"UP"}`

This endpoint is always accessible without authentication.

## 6) Store API - Read operations

Base route: `/store/{domain}/{user}/...`

### 6.1 Directory listing

`GET /store/`

```bash
curl -s -H "Accept: application/json" http://localhost:8090/store/
```

Response:
- `200`: JSON array of directories/files, or HTML listing

### 6.2 Read message as JSON

`GET /store/{domain}/{user}/{folder}/{message.eml}`

```bash
curl -s -H "Accept: application/json" \
  http://localhost:8090/store/example.com/tony/new/message.eml
```

Response:
- `200`: `{"path":"...","name":"message.eml","size":1234,"content":"..."}`

### 6.3 Read message as raw EML

`GET /store/{domain}/{user}/{folder}/{message.eml}`

```bash
curl -s http://localhost:8090/store/example.com/tony/new/message.eml
```

Response:
- `200`: Raw EML content (`text/plain`)

## 7) Store API - Folder endpoints

Store API rules:
- All mutation routes require authentication.
- Path traversal is blocked (`..` rejected).
- Folder paths are relative to `{domain}/{user}` root.
- Folder paths may include nested segments, e.g. `Projects/2026`.
- Maildir leaf paths (`new`, `cur`) are supported where relevant.

### 7.1 Create folder

`POST /store/{domain}/{user}/folders`

Body (JSON):
- `name` (required, string)
- `parent` (optional, string, default `""`)

Behavior:
- Creates folder at `{parent}/{name}`.
- Creates `new/` and `cur/` subdirs.

Response:
- `200`: `{"r":1,"msg":"Folder created."}`
- `400`: `{"r":0,"msg":"Folder name is required."}`

### 7.2 Rename folder

`PATCH /store/{domain}/{user}/folders/{folder...}`

Body (JSON):
- `name` (required, string)

Response:
- `200`: `{"r":1,"msg":"Folder renamed."}`
- `404`: `{"r":0,"msg":"Folder not found."}`

### 7.3 Delete folder

`DELETE /store/{domain}/{user}/folders/{folder...}`

Behavior:
- Deletes folder recursively only if no regular files exist.

Response:
- `200`: `{"r":1,"msg":"Folder removed."}`
- `400`: `{"r":0,"msg":"Folder is not empty."}`
- `404`: `{"r":0,"msg":"Folder not found."}`

### 7.4 Copy folder

`POST /store/{domain}/{user}/folders/{folder...}/copy`

Body (JSON):
- `destinationParent` (optional string, default `""`)
- `newName` (optional string, default source folder name)

Response:
- `200`: `{"r":1,"msg":"Folder copied."}`
- `404`: `{"r":0,"msg":"Folder not found."}`

### 7.5 Move folder

`POST /store/{domain}/{user}/folders/{folder...}/move`

Body (JSON):
- `destinationParent` (optional string, default `""`)

Response:
- `200`: `{"r":1,"msg":"Folder moved."}`
- `404`: `{"r":0,"msg":"Folder not found."}`

### 7.6 Folder properties

`GET /store/{domain}/{user}/folders/{folder...}/properties`

Response:
- `200`: `{"r":1,"size":<bytes>,"unread":<count>,"total":<count>}`
- `404`: `{"r":0,"msg":"Folder not found."}`

## 8) Store API - Message endpoints

All message operations use:

`POST /store/{domain}/{user}/messages/{operation}`

Supported operations: `move`, `read-status`, `mark-all-read`, `delete-all`, `cleanup`

### 8.1 Move messages

`POST /store/{domain}/{user}/messages/move`

Body (JSON):
- `fromFolder` (string)
- `toFolder` (string)
- `messageIds` (array of filename IDs)

Response:
- `200`: `{"success":true,"moved":<int>}`

### 8.2 Read/unread status

`POST /store/{domain}/{user}/messages/read-status`

Body (JSON):
- `folder` (string)
- `action` (`"read"` or `"unread"`)
- `messageIds` (array)

Response:
- `200`: `{"moved":<int>}`

### 8.3 Mark all read

`POST /store/{domain}/{user}/messages/mark-all-read`

Body (JSON):
- `folder` (string)

Response:
- `200`: `{"success":true,"moved":<int>}`

### 8.4 Delete all

`POST /store/{domain}/{user}/messages/delete-all`

Body (JSON):
- `folder` (string)

Response:
- `200`: `{"success":true,"deleted":<int>}`

### 8.5 Cleanup by age

`POST /store/{domain}/{user}/messages/cleanup`

Body (JSON):
- `folder` (string)
- `months` (int, default `3`)

Response:
- `200`: `{"r":1,"msg":"Cleanup complete.","affected":<int>}`

## 9) Store API - Draft endpoints

### 9.1 Create draft

`POST /store/{domain}/{user}/drafts`

Body:
- Raw draft bytes (`message/rfc822`, `application/octet-stream`, etc.)

Response:
- `200`: `{"r":1,"draftId":"draft-<uuid>.eml","msg":"mail successfully saved."}`

### 9.2 Update draft

`PUT /store/{domain}/{user}/drafts/{draftId}`

Body:
- Raw draft bytes

Response:
- `200`: `{"r":1,"draftId":"<draftId>","msg":"mail successfully saved."}`

### 9.3 Delete draft

`DELETE /store/{domain}/{user}/drafts/{draftId}`

Response:
- `200`: `{"r":1,"msg":"mail successfully discarded."}`

### 9.4 Upload draft attachment

`POST /store/{domain}/{user}/drafts/{draftId}/attachments?filename=<name.ext>`

Body:
- Raw binary attachment bytes (recommended: `application/octet-stream`)

Query:
- `filename` (optional, backend normalizes and falls back if omitted)

Response:
- `200`: `{"r":1,"f":["att-<uuid>-<normalizedFilename>"]}`

### 9.5 Delete draft attachment

`DELETE /store/{domain}/{user}/drafts/{draftId}/attachments/{attachmentId}`

Response:
- `200`: `{"r":1}`

## 10) Maildir path requirements

- Use canonical keys only (no aliases).
- Message IDs should be filename IDs (`*.eml`).
- Folder paths for folder/draft endpoints must use Maildir naming:
  - INBOX: `inbox`
  - Standard folders: `.Drafts`, `.Sent`, `.Trash`, `.Junk`, `.Outbox`, etc.
  - Nested custom folders: `.Parent/.Child`

---

# Service Endpoint (Port 8080)

The service endpoint provides monitoring, metrics, diagnostics, and configuration management.

## 11) Landing page

`GET /`

Response:
- `200`: HTML page listing all available service endpoints

## 12) Health check

`GET /health`

Response:
- `200`: `{"status":"UP","uptime":"4d 2h 7m 5s","listeners":[...],"queue":{...},"scheduler":{...}}`

This endpoint is always accessible without authentication and includes Robin-specific statistics.

## 13) Metrics endpoints

### 13.1 Metrics UI

`GET /metrics`

Response:
- `200`: HTML page with Chart.js visualizations of application metrics

### 13.2 Prometheus metrics

`GET /metrics/prometheus`

Response:
- `200`: Metrics in Prometheus exposition format (`text/plain`)

```
# HELP jvm_memory_used_bytes The amount of used memory
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap",id="G1 Eden Space",} 2.490368E7
```

### 13.3 Graphite metrics

`GET /metrics/graphite`

Response:
- `200`: Metrics in Graphite plain text format (`text/plain`)

```
jvm_memory_used 55941680 1678886400
jvm_memory_max 2147483648 1678886400
```

## 14) Configuration endpoints

### 14.1 Configuration viewer

`GET /config`

Response:
- `200`: HTML page showing current `properties.json5` and `server.json5` with reload button

### 14.2 Reload configuration

`POST /config/reload`

Triggers immediate reload of configuration files (thread-safe).

Response:
- `200`: `{"status":"OK","message":"Configuration reloaded successfully"}`
- `500`: `{"status":"ERROR","message":"Failed to reload configuration: <details>"}`

### 14.3 Config sync status

`GET /config/sync/status`

Returns the last config-store sync attempt status.

Response:
- `200`: JSON object containing sync status, applied files, and skipped files.

## 15) System diagnostics

### 15.1 Environment variables

`GET /system/env` (or `/env` for base ServiceEndpoint)

Response:
- `200`: Plain text listing of environment variables

```
PATH=/usr/local/bin:/usr/bin:/bin
HOME=/home/user
JAVA_HOME=/usr/lib/jvm/java-21-openjdk
```

### 15.2 System properties

`GET /system/props` (or `/sysprops` for base ServiceEndpoint)

Response:
- `200`: Plain text listing of Java system properties

```
java.version=21.0.1
java.vendor=Eclipse Adoptium
os.name=Linux
```

### 15.3 Thread dump

`GET /system/threads` (or `/threads` for base ServiceEndpoint)

Response:
- `200`: Plain text thread dump (similar to `jstack` output)

### 15.4 Heap dump

`GET /system/heapdump` (or `/heapdump` for base ServiceEndpoint)

Triggers creation of a heap dump file.

Response:
- `200`: `"Heap dump created at: heapdump-1678886400000.hprof"`
- `500`: `"Could not create heap dump: <details>"`

---

# Error handling

## HTTP status codes

Success status codes:
- `/client/queue` => `202`
- `/client/send` => `200`
- `/users/*` => `200`
- `/store/*` => `200`
- `/health` => `200`
- `/config/reload` => `200`

Retryable errors:
- `500`, `503`, network timeout

Non-retryable errors:
- `400`, `401`, `403`, `404`, `405`

## Error response formats

Common error responses:
- `400`: `"<validation message>"` or `{"error":"<message>"}`
- `401`: `"Unauthorized"` (authentication required)
- `403`: `{"error":"Forbidden"}`
- `404`: `{"error":"Not Found"}` or `{"r":0,"msg":"Folder not found."}`
- `405`: `"Method Not Allowed"`
- `500`: `{"error":"Internal Server Error"}` or `"Internal Server Error: <details>"`
- `503`: `{"error":"Users API backend is not configured"}`

Notes:
- Store folder errors use `{"r":0,"msg":"..."}` format.
- Store message/draft errors commonly use `{"error":"..."}`.
- Client endpoints return plain text errors.

---

# Implementation architecture

## API Endpoint handlers

| Class | Path | Responsibility |
|-------|------|----------------|
| `ApiEndpoint` | `/`, `/health` | Landing page, health check |
| `ClientSendHandler` | `/client/send` | Immediate SMTP message sending |
| `ClientQueueHandler` | `/client/queue` | Enqueueing messages for relay |
| `QueueOperationsHandler` | `/client/queue/*` | Queue list, delete, retry, bounce |
| `UsersHandler` | `/users/*` | User listing, existence, authentication |
| `StoreHandler` | `/store/*` | Storage browsing and mutations |
| `LogsHandler` | `/logs` | Log file searching |

## Store Handler decomposition

| Class | Responsibility |
|-------|----------------|
| `StoreHandler` | Main entry point, routing, directory listing |
| `StoreFolderOperations` | Folder create, rename, delete, copy, move, properties |
| `StoreMessageOperations` | Message move, read-status, mark-all-read, delete-all, cleanup |
| `StoreDraftOperations` | Draft create, update, delete, attachments |
| `ApiEndpointUtils` | Shared utilities for JSON parsing, path handling |

## Service Endpoint handlers

| Class | Paths | Responsibility |
|-------|-------|----------------|
| `ServiceEndpoint` | `/`, `/health`, `/metrics/*`, `/env`, `/sysprops`, `/threads`, `/heapdump` | Base monitoring endpoints |
| `RobinMetricsEndpoint` | `/config/*`, `/system/*` | Robin-specific extensions with reorganized paths |

