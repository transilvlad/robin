#!/usr/bin/env python3
"""HTTP sink for the integration stack: records every request and answers with a scripted response.

Robin under test sends webhooks, bot callbacks and other HTTP calls here; cases then read back
what arrived. Control routes live under /_sink/ and are never recorded:

  GET    /_sink/health                    {"status": "UP"}
  GET    /_sink/requests[?path=/prefix]   {"count": n, "requests": [...]}, oldest first
  DELETE /_sink/requests[?path=/prefix]   forget recorded requests
  POST   /_sink/responses                 script a reply: {"path": "/hook", "status": 500,
                                          "body": "...", "contentType": "...", "delaySeconds": 0}
  DELETE /_sink/responses                 forget all scripted replies

Any other request is recorded. It gets the scripted reply whose path is the longest prefix of
the request path, or 200 {"status": "ok"} when none matches.
"""

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

MAX_BODY = 4 * 1024 * 1024

lock = threading.Lock()
recorded = []
scripted = {}


class SinkHandler(BaseHTTPRequestHandler):
    server_version = "robin-http-sink/1"

    def do_GET(self):
        self.handle_any()

    def do_HEAD(self):
        self.handle_any()

    def do_POST(self):
        self.handle_any()

    def do_PUT(self):
        self.handle_any()

    def do_PATCH(self):
        self.handle_any()

    def do_DELETE(self):
        self.handle_any()

    def handle_any(self):
        url = urlsplit(self.path)
        body = self.read_body()
        if body is None:
            return
        if url.path.startswith("/_sink/"):
            self.control(url, body)
        else:
            self.record(url, body)

    def read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length > MAX_BODY:
            self.send_json(413, {"error": "body too large"})
            return None
        return self.rfile.read(length) if length > 0 else b""

    def control(self, url, body):
        query = parse_qs(url.query)
        prefix = query.get("path", [""])[0]

        if url.path == "/_sink/health":
            self.send_json(200, {"status": "UP"})
        elif url.path == "/_sink/requests" and self.command == "GET":
            with lock:
                matches = [r for r in recorded if r["path"].startswith(prefix)]
            self.send_json(200, {"count": len(matches), "requests": matches})
        elif url.path == "/_sink/requests" and self.command == "DELETE":
            with lock:
                recorded[:] = [r for r in recorded if not r["path"].startswith(prefix)]
            self.send_json(200, {"status": "cleared"})
        elif url.path == "/_sink/responses" and self.command == "POST":
            try:
                spec = json.loads(body or b"{}")
                path = spec["path"]
                if not path.startswith("/") or path.startswith("/_sink/"):
                    raise ValueError("path must start with / and not be a control route")
                int(spec.get("status", 200))
                float(spec.get("delaySeconds", 0))
            except (ValueError, KeyError, TypeError) as e:
                self.send_json(400, {"error": "invalid response script: %s" % e})
                return
            with lock:
                scripted[path] = spec
            self.send_json(200, {"status": "scripted", "path": path})
        elif url.path == "/_sink/responses" and self.command == "DELETE":
            with lock:
                scripted.clear()
            self.send_json(200, {"status": "cleared"})
        else:
            self.send_json(404, {"error": "unknown control route"})

    def record(self, url, body):
        entry = {
            "time": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "method": self.command,
            "path": url.path,
            "query": url.query,
            "headers": {k: v for k, v in self.headers.items()},
            "size": len(body),
            "body": body.decode("utf-8", errors="replace"),
        }
        with lock:
            recorded.append(entry)
            match = max((p for p in scripted if url.path.startswith(p)), key=len, default=None)
            spec = dict(scripted[match]) if match else None

        if spec is None:
            self.send_json(200, {"status": "ok"})
            return

        delay = float(spec.get("delaySeconds", 0))
        if delay > 0:
            time.sleep(delay)
        payload = spec.get("body", "")
        if not isinstance(payload, str):
            payload = json.dumps(payload)
        self.send_raw(int(spec.get("status", 200)), payload.encode("utf-8"),
                      spec.get("contentType", "application/json"))

    def send_json(self, status, data):
        self.send_raw(status, json.dumps(data).encode("utf-8"), "application/json")

    def send_raw(self, status, payload, content_type):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(payload)

    def log_message(self, fmt, *args):
        print("%s %s" % (self.address_string(), fmt % args), flush=True)


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", 8080), SinkHandler)
    print("http-sink listening on :8080", flush=True)
    server.serve_forever()
