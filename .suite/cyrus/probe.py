#!/usr/bin/env python3
"""Read-only TLS/proxy/canonical-login gate. Credentials stay in a local file."""

import argparse
import base64
import http.client
import json
from pathlib import Path
import ssl
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlsplit
from urllib.request import HTTPRedirectHandler, HTTPSHandler, ProxyHandler, Request, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def same_origin(base, target):
    resolved = urljoin(base, target)
    left, right = urlsplit(base), urlsplit(resolved)
    if (right.scheme != "https" or right.username is not None or right.password is not None
            or right.fragment or (left.hostname, left.port or 443) != (right.hostname, right.port or 443)):
        raise ValueError("Untrusted advertised endpoint")
    return resolved


def rejected_tls(url, context):
    opener = build_opener(ProxyHandler({}), HTTPSHandler(context=context), NoRedirect())
    try:
        with opener.open(url, timeout=10):
            pass
    except URLError as error:
        if isinstance(error.reason, ssl.SSLCertVerificationError):
            return
        raise ValueError("Negative TLS probe failed for a non-certificate reason") from None
    raise ValueError("Invalid TLS peer was accepted")


def proxy_bounds(base, context):
    parsed = urlsplit(base)
    connection = http.client.HTTPSConnection(parsed.hostname, parsed.port or 443, timeout=10, context=context)
    try:
        # Headers only: nginx must reject the declared size before accepting a
        # body. No malformed/partial JMAP write reaches the upstream server.
        connection.putrequest("POST", "/jmap/")
        connection.putheader("Content-Length", str(31 * 1024 * 1024))
        connection.endheaders()
        response = connection.getresponse()
        response.read()
        if response.status != 413:
            raise ValueError("Proxy upload bound was not enforced")
    finally:
        connection.close()
    limited = 0
    connection = http.client.HTTPSConnection(parsed.hostname, parsed.port or 443, timeout=10, context=context)
    try:
        for _ in range(64):
            connection.request("GET", "/jmap/")
            response = connection.getresponse()
            response.read()
            if response.status not in (401, 429):
                raise ValueError("Unexpected anonymous rate probe response")
            limited += response.status == 429
    finally:
        connection.close()
    if limited == 0:
        raise ValueError("Proxy request limit was not observed")
    print(json.dumps({"upload_limit_413": True, "rate_limit_429": True}))


def probe(base, ca_file, credentials_file):
    parsed = urlsplit(base)
    if parsed.scheme != "https" or not parsed.hostname or parsed.path not in ("", "/") or parsed.query:
        raise ValueError("Expected a TLS origin without a path/query")
    base = same_origin(base, base).rstrip("/") + "/"
    credentials = json.loads(credentials_file.read_text())
    user, password = credentials["username"], credentials["password"]
    if not isinstance(user, str) or not isinstance(password, str) or not user or not password:
        raise ValueError("Invalid credentials file")
    if any(char in user + password for char in "\r\n\x00") or ":" in user:
        raise ValueError("Invalid Basic credentials")
    context = ssl.create_default_context(cafile=str(ca_file))
    opener = build_opener(ProxyHandler({}), HTTPSHandler(context=context), NoRedirect())

    def request(path, authenticated=False, payload=None, extra=None, wrong_password=False):
        headers = {"Accept": "application/json"}
        if authenticated:
            secret = "wrong-fixture-password" if wrong_password else password
            headers["Authorization"] = "Basic " + base64.b64encode(f"{user}:{secret}".encode()).decode()
        if extra:
            headers.update(extra)
        body = None
        if payload is not None:
            headers["Content-Type"] = "application/json"
            body = json.dumps(payload).encode()
        req = Request(same_origin(base, path), data=body, headers=headers)
        try:
            response = opener.open(req, timeout=30)
        except HTTPError as error:
            response = error
        with response:
            data = response.read(2 * 1024 * 1024 + 1)
            if len(data) > 2 * 1024 * 1024:
                raise ValueError("Oversized probe response")
            return response.code, response.headers, data

    status, headers, _ = request(".well-known/jmap")
    if status != 308 or headers.get("Location") != "/jmap/":
        raise ValueError("Discovery does not stay on this TLS origin")
    if request("jmap/")[0] != 401:
        raise ValueError("Unauthenticated access was not denied")
    if request("jmap/", True, wrong_password=True)[0] != 401:
        raise ValueError("Wrong password was not denied")
    if request("jmap/", extra={"Host": "host-injection.invalid"})[0] != 421:
        raise ValueError("Untrusted Host was not denied")
    if request("jmap/", extra={"Origin": "https://cross-origin.invalid"})[0] != 403:
        raise ValueError("Untrusted browser Origin was not denied")
    status, headers, body = request("jmap/", True, extra={"Origin": base.rstrip("/")})
    if status != 200 or "no-store" not in headers.get("Cache-Control", ""):
        raise ValueError("Authenticated TLS session/no-store failed")
    session = json.loads(body)
    if session.get("username", "").lower() != user.lower():
        raise ValueError("Session did not resolve to the canonical user")
    mail = "urn:ietf:params:jmap:mail"
    account = session.get("primaryAccounts", {}).get(mail)
    if not account or mail not in session.get("accounts", {}).get(account, {}).get("accountCapabilities", {}):
        raise ValueError("No primary Mail account")
    for field in ("apiUrl", "downloadUrl", "uploadUrl", "eventSourceUrl"):
        if field in session:
            same_origin(base, session[field])
    api = same_origin(base, session["apiUrl"])
    batch = {"using": ["urn:ietf:params:jmap:core", mail], "methodCalls": [
        ["Mailbox/get", {"accountId": account, "properties": ["id", "role", "myRights"]}, "folders"],
        ["Email/query", {"accountId": account, "limit": 1, "calculateTotal": True}, "messages"],
    ]}
    status, _, body = request(api, True, payload=batch)
    if status != 200:
        raise ValueError("Authenticated JMAP read failed")
    responses = json.loads(body).get("methodResponses", [])
    if len(responses) != 2 or [r[0] for r in responses] != ["Mailbox/get", "Email/query"]:
        raise ValueError("JMAP methods returned errors or incomplete results")
    if any(r[1].get("accountId") != account for r in responses):
        raise ValueError("Read response account mismatch")
    print(json.dumps({"tls_verified": True, "host_origin_auth_barriers": True,
                      "mailboxes": len(responses[0][1]["list"]), "emails": responses[1][1].get("total"),
                      "submission": "disabled in suite; explicit authenticated SMTP required"}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--ca-file", required=True, type=Path)
    parser.add_argument("--credentials-file", required=True, type=Path)
    parser.add_argument("--wrong-name-url")
    parser.add_argument("--proxy-bounds", action="store_true")
    args = parser.parse_args()
    try:
        if args.wrong_name_url:
            rejected_tls(args.base_url, ssl.create_default_context())
            rejected_tls(args.wrong_name_url, ssl.create_default_context(cafile=str(args.ca_file)))
        probe(args.base_url, args.ca_file, args.credentials_file)
        if args.proxy_bounds:
            proxy_bounds(args.base_url, ssl.create_default_context(cafile=str(args.ca_file)))
    except Exception:
        print("ERROR: TLS/JMAP probe failed; no credential, body, account or endpoint details logged", file=sys.stderr)
        sys.exit(1)
