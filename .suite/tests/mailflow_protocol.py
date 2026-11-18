"""In-network probes for the isolated production mail-flow fixture."""

import base64
from email.message import EmailMessage
from email.policy import SMTP
from email.utils import formatdate
import imaplib
import json
from pathlib import Path
import smtplib
import ssl
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener

from test_dkim_offline import verify_fixture_dkim


ROOT = Path("/fixture")
PASSWORD = (ROOT / "password").read_text().strip()
CONTEXT = ssl.create_default_context(cafile=str(ROOT / "tls" / "fullchain.pem"))


def operational(port, path, authenticated=True):
    headers = {}
    if authenticated:
        token = base64.b64encode(f"fixture:{PASSWORD}".encode()).decode()
        headers["Authorization"] = "Basic " + token
    opener = build_opener(ProxyHandler({}))
    return opener.open(Request(f"http://mta-backend:{port}{path}", headers=headers), timeout=10)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def smtp(port=587, authenticated=True):
    if port == 465:
        client = smtplib.SMTP_SSL("mta-backend", port, context=CONTEXT, timeout=40)
    else:
        client = smtplib.SMTP("mta-backend", port, timeout=40)
        client.ehlo()
        require("auth" not in client.esmtp_features, "AUTH was advertised before TLS")
        client.starttls(context=CONTEXT)
    client.ehlo()
    if authenticated:
        client.login("alice@example.test", PASSWORD)
    return client


def message(subject, sender="alias@example.test", recipient="bob@example.test", body="A harmless local acceptance message."):
    mail = EmailMessage()
    mail["From"], mail["To"], mail["Subject"] = sender, recipient, subject
    mail["Date"] = formatdate(localtime=False)
    mail["Message-ID"] = f"<{subject}@example.test>"
    mail.set_content(body)
    return mail.as_bytes(policy=SMTP)


def inbox(user, subject, expected=True):
    deadline = time.monotonic() + 15
    while True:
        with imaplib.IMAP4_SSL("imap-backend", 993, ssl_context=CONTEXT, timeout=20) as client:
            client.login(user, PASSWORD)
            require(client.select("INBOX")[0] == "OK", "INBOX was unavailable")
            status, found = client.search(None, "SUBJECT", f'"{subject}"')
            require(status == "OK", "IMAP search failed")
            ids = found[0].split()
            if ids:
                require(expected and len(ids) == 1, "Unexpected or duplicate delivered message")
                status, content = client.fetch(ids[0], "(BODY.PEEK[])")
                require(status == "OK", "Message could not be read")
                return next(item[1] for item in content if isinstance(item, tuple))
        if not expected:
            return None
        require(time.monotonic() < deadline, "Accepted message did not reach its mailbox")
        time.sleep(0.2)


def refused(subject, code, sender="alias@example.test", recipient="bob@example.test", body="A harmless local acceptance message."):
    with smtp() as client:
        try:
            client.sendmail(sender, [recipient], message(subject, sender, recipient, body))
        except smtplib.SMTPDataError as error:
            require(error.smtp_code == code, f"Unexpected DATA rejection: {error.smtp_code}")
        else:
            raise AssertionError("Rejected message received SMTP success")
    inbox("bob@example.test", subject, expected=False)


def queued_signature():
    queued = list(Path("/payloads/queue").glob("qeml-*"))
    require(len(queued) == 1, "Accepted outbound mail lacks exactly one durable body")
    verify_fixture_dkim(queued[0].read_bytes(), ROOT / "signing.pub", "example.test", "default")


def missing_key_scan():
    request = Request("http://spam-backend:11333/checkv2", data=message("signer-readiness"),
                      headers={"From": "alias@example.test", "Rcpt": "receiver@outside.invalid",
                               "User": "alice@example.test", "Flags": "milter"})
    opener = build_opener(ProxyHandler({}))
    deadline = time.monotonic() + 30
    while True:
        try:
            with opener.open(request, timeout=10) as response:
                result = json.load(response)
            break
        except URLError as error:
            if isinstance(error, HTTPError) or time.monotonic() >= deadline:
                raise
            time.sleep(0.2)
    require("action" in result, "The restarted scanner did not complete a real scan")
    require(not result.get("dkim-signature")
            and not any(name.lower() == "dkim-signature"
                        for name in result.get("milter", {}).get("add_headers", {})),
            "The removed fixture key still produced a signature")


def positive():
    with smtp(25, authenticated=False) as client:
        require(client.mail("remote@outside.invalid")[0] == 250, "Inbound sender refused")
        require(client.rcpt("receiver@outside.invalid")[0] >= 500, "Unauthenticated relay accepted")
    with smtp(authenticated=False) as client:
        require(client.mail("alice@example.test")[0] == 530, "Unauthenticated submission accepted")
    with smtp() as client:
        require(client.mail("bob@example.test")[0] >= 500, "Unauthorized envelope sender accepted")
        client.rset()
        try:
            client.sendmail("alice@example.test", ["bob@example.test"],
                            message("forged-header", "bob@example.test"))
        except smtplib.SMTPDataError as error:
            require(error.smtp_code >= 500, "Forged header did not receive a permanent refusal")
        else:
            raise AssertionError("Forged From header accepted")
    for port in (587, 465):
        subject = f"signed-local-{port}"
        with smtp(port) as client:
            require(not client.sendmail("alias@example.test", ["bob@example.test"], message(subject)),
                    "Authenticated alias submission failed")
        received = inbox("bob@example.test", subject)
        verify_fixture_dkim(received, ROOT / "signing.pub", "example.test", "default")
    for alias in ("receive-only@example.test", "auto@example.test"):
        subject = "inbound-" + alias.split("@")[0]
        with smtp(25, authenticated=False) as client:
            require(not client.sendmail("remote@outside.invalid", [alias],
                                       message(subject, "remote@outside.invalid", alias)),
                    "Inbound alias delivery failed")
        inbox("auto@example.test", subject)
    with smtp() as client:
        require(not client.sendmail("alias@example.test", ["receiver@outside.invalid"],
                                   message("signed-queued", recipient="receiver@outside.invalid")),
                "Signed outbound mail was not accepted into the queue")
    queued_signature()
    for port in (8090, 8080):
        with operational(port, "/health", authenticated=False) as response:
            health = json.load(response)
            require(health["status"] == "UP" and set(health) <= {"status", "uptime"},
                    "Unauthenticated liveness exposed operational details")
    for port, path in ((8090, "/client/queue/list"), (8080, "/config/status"), (8080, "/metrics/prometheus")):
        try:
            operational(port, path, authenticated=False)
        except HTTPError as error:
            require(error.code == 401, "Unexpected private API refusal")
            error.close()
        else:
            raise AssertionError("Private management endpoint was unauthenticated")
        with operational(port, path) as response:
            require(response.status == 200 and bool(response.read()), "Authenticated operational endpoint failed")


stage = sys.argv[1]
result = {"stage": stage, "status": "accepted"}
if stage == "configuration":
    with operational(8080, "/config/status") as response:
        result["runtime"] = json.load(response)
elif stage == "positive":
    positive()
elif stage == "activation":
    with smtp() as client:
        require(client.mail("bob@example.test")[0] >= 500, "Stale local policy disabled sender authorization")
        client.rset()
        require(not client.sendmail("alias@example.test", ["bob@example.test"], message("reactivated-policy")),
                "Mail failed after authoritative configuration activation")
    verify_fixture_dkim(inbox("bob@example.test", "reactivated-policy"),
                        ROOT / "signing.pub", "example.test", "default")
    queued_signature()
elif stage == "scanner-outage":
    refused("scanner-unavailable", 451)
elif stage == "malware":
    refused("synthetic-malware", 554, body="ROBIN_SCANNER_FIXTURE_MARKER")
elif stage == "missing-signing-key":
    missing_key_scan()
    refused("missing-signing-key", 451, recipient="receiver@outside.invalid")
elif stage == "revoked":
    with smtp() as client:
        require(client.mail("alias@example.test")[0] >= 500, "Revoked sender grant remained usable")
elif stage == "disabled-domain":
    with smtp(25, authenticated=False) as client:
        require(client.mail("remote@outside.invalid")[0] == 250, "Inbound sender refused")
        require(client.rcpt("alice@example.test")[0] >= 500, "Disabled managed domain accepted mail")
else:
    raise ValueError("Unknown mail-flow stage")
print(json.dumps(result))
