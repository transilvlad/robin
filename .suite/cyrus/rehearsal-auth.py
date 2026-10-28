#!/usr/bin/env python3
"""Synthetic example.test canonical directory; no real Robin DB/API access."""

from http.server import BaseHTTPRequestHandler, HTTPServer
import json


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def respond(self, code, data):
        body = json.dumps(data).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.respond(200 if self.path == "/health" else 404, {})

    def do_POST(self):
        if self.path != "/users/authenticate":
            self.respond(404, {})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 4096:
                raise ValueError()
            request = json.loads(self.rfile.read(length))
            allowed = (request.get("username") in ("alice@example.test", "bob@example.test")
                       and request.get("password") == "synthetic-only-password")
            self.respond(200, {"authenticated": allowed})
        except (ValueError, TypeError):
            self.respond(400, {})


HTTPServer(("0.0.0.0", 8090), Handler).serve_forever()
