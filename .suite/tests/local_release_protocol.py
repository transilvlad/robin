#!/usr/bin/env python3
"""Protocol-only client executed inside the isolated Cyrus container."""

import base64
import imaplib
import json
from pathlib import Path
import smtplib
import socket
import ssl
import sys


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sieve_response(stream):
    lines = []
    for _ in range(100):
        line = stream.readline(65537)
        require(0 < len(line) <= 65536, "Missing or oversized ManageSieve response")
        lines.append(line)
        if line.startswith((b"OK", b"NO", b"BYE")):
            require(line.startswith(b"OK"), "ManageSieve command rejected")
            return lines
    raise ValueError("ManageSieve response exceeded the line limit")


def message(subject, author="alice@example.test", extra=""):
    return (f"From: {author}\r\nTo: bob@example.test\r\nSubject: {subject}\r\n"
            f"Message-ID: <{subject}@example.test>\r\n{extra}\r\n"
            f"Local acceptance body: {subject}\r\n").encode("ascii")


def execute(stage):
    password = Path("/run/secrets/cyrus_admin_password").read_text().strip()
    context = ssl.create_default_context(cafile="/fixture/tls/fullchain.pem")
    # An internal Docker bridge has no default route. No external probe is sent.
    routes = Path("/proc/net/route").read_text().splitlines()[1:]
    require(not any(row.split()[1] == "00000000" for row in routes), "Fixture has a default route")
    checks = {}
    def check(name, actual, expected):
        checks[name] = {"actual": actual, "expected": expected}

    def connect(login=True):
        client = smtplib.SMTP("mta-backend", 587, timeout=20)
        client.ehlo()
        client.starttls(context=context)
        client.ehlo()
        if login:
            client.login("alice@example.test", password)
        return client

    def login(user):
        client = imaplib.IMAP4("127.0.0.1", 143, timeout=20)
        require(client.login(user, password)[0] == "OK", "Actual Cyrus login failed")
        return client

    if stage == "smtp":
        with connect(False) as client:
            check("authentication_required", client.mail("alice@example.test")[0], 530)
            try:
                client.login("alice@example.test", "invalid-local-password")
            except smtplib.SMTPAuthenticationError as error:
                check("invalid_credentials", error.smtp_code, 535)
            else:
                check("invalid_credentials", 235, 535)
        with smtplib.SMTP_SSL("mta-backend", 465, context=context, timeout=20) as client:
            client.login("alice@example.test", password)
        with connect() as client:
            for sender in ("bob@example.test", "receive-only@example.test", "sender@outside.invalid"):
                check("envelope_" + sender, client.mail(sender)[0], 550)
                client.rset()
            for name, body in (("forged_from", message("bad-from", "bob@example.test")),
                               ("forged_sender", message("bad-sender", extra="Sender: bob@example.test\r\n")),
                               ("duplicate_from", message("duplicate-from", extra="From: alice@example.test\r\n"))):
                require(client.mail("alice@example.test")[0] == 250, "Authorized envelope rejected")
                require(client.rcpt("bob@example.test")[0] == 250, "Local recipient rejected")
                check(name, client.data(body)[0], 550)
                client.rset()
            for subject, sender in (("smtp-primary", "alice@example.test"), ("smtp-alias", "alias@example.test")):
                require(not client.sendmail(sender, ["bob-alias@example.test"], message(subject, sender)),
                        "Local alias delivery rejected")
        with login("bob@example.test") as bob:
            require(bob.select("INBOX", readonly=True)[0] == "OK", "Local sink Inbox missing")
            for subject in ("smtp-primary", "smtp-alias"):
                require(bob.search(None, "HEADER", "Subject", subject)[1][0], "Accepted SMTP mail missing")
            for name, subject in (("forged_from", "bad-from"), ("forged_sender", "bad-sender"),
                                  ("duplicate_from", "duplicate-from")):
                check("sink_" + name, bool(bob.search(None, "HEADER", "Subject", subject)[1][0]), False)
    elif stage == "envelope-control":
        with connect() as client:
            for sender in ("alice@example.test", "alias@example.test"):
                check(sender, client.mail(sender)[0], 250)
                client.rset()
    elif stage in ("revoked", "outage"):
        with connect() as client:
            sender = "alias@example.test" if stage == "revoked" else "alice@example.test"
            expected = 550 if stage == "revoked" else 451
            check(stage, client.mail(sender)[0], expected)
            client.rset()
    elif stage == "queue":
        # These accepted inbound messages queue to an unavailable local-only
        # fixed relay. No MX/DNS route or external recipient is involved.
        with smtplib.SMTP("mta-backend", 25, timeout=20) as client:
            for subject in ("queue-pending-one", "queue-pending-two"):
                require(not client.sendmail("alice@example.test", ["bob@example.test"], message(subject)),
                        "Local pending-queue acceptance failed")
    elif stage == "sieve":
        with login("alice@example.test") as alice:
            require(alice.create("GateFiltered")[0] == "OK", "Sieve destination creation failed")
        with socket.create_connection(("127.0.0.1", 4190), timeout=20) as sock:
            with sock.makefile("rb") as stream:
                sieve_response(stream)
                token = base64.b64encode(b"\0alice@example.test\0" + password.encode())
                sock.sendall(b'AUTHENTICATE "PLAIN" "' + token + b'"\r\n')
                sieve_response(stream)
                script = (b'require ["fileinto"]; if header :is "Subject" "sieve-fileinto" '
                          b'{ fileinto "GateFiltered"; stop; }\n')
                sock.sendall(b'PUTSCRIPT "local-gate" {' + str(len(script)).encode() +
                             b'+}\r\n' + script + b'\r\n')
                sieve_response(stream)
                sock.sendall(b'SETACTIVE "local-gate"\r\n')
                sieve_response(stream)
        with smtplib.LMTP("127.0.0.1", 24, timeout=20) as lmtp:
            for subject in ("sieve-fileinto", "sieve-keep"):
                require(not lmtp.sendmail("bob@example.test", ["alice@example.test"], message(subject)),
                        "Sieve LMTP acceptance failed")
        with login("alice@example.test") as alice:
            require(alice.select("GateFiltered", readonly=True)[0] == "OK", "Sieve folder missing")
            require(alice.search(None, "ALL")[1] == [b"1"], "Sieve did not file exactly one message")
            require(alice.search(None, "HEADER", "Subject", "sieve-fileinto")[1] == [b"1"],
                    "Wrong Sieve message filed")
            require(alice.select("INBOX", readonly=True)[0] == "OK", "Sieve keep Inbox missing")
            require(alice.search(None, "HEADER", "Subject", "sieve-keep")[1][0], "Implicit keep failed")
            require(alice.search(None, "HEADER", "Subject", "sieve-fileinto")[1] == [b""],
                    "Fileinto incorrectly retained a duplicate in Inbox")
    else:
        raise ValueError("Unknown bounded protocol stage")
    print(json.dumps({"stage": stage, "passed": all(item["actual"] == item["expected"]
                                                  for item in checks.values()), "checks": checks}))


if __name__ == "__main__":
    execute(sys.argv[1])
