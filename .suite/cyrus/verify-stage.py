#!/usr/bin/env python3
"""Small no-mail-write intermediate daemon gate; logs only pass/count/version."""

import imaplib
import os
from pathlib import Path
import socket
import subprocess
import sys
from urllib.error import HTTPError
from urllib.request import urlopen

STEP = "marker"


def verify():
    global STEP
    release = os.environ["CYRUS_RELEASE"]
    if release not in ("3.8.8", "3.10.4", "3.12.4"):
        raise ValueError("Unreviewed release")
    data = Path("/var/lib/cyrus")
    if (data / ".robin-jmap-upgraded").read_text().strip() != release:
        raise ValueError("Upgrade marker does not match running image")
    STEP = "config-lint"
    subprocess.run(["runuser", "-u", "cyrus", "--", "cyr_info", "-C", "/etc/imapd.conf",
                    "-M", "/etc/cyrus-jmap.conf", "conf-lint"], check=True)
    password = Path("/run/secrets/cyrus_admin_password").read_text().rstrip("\r\n")
    if len(password) < 32:
        raise ValueError("Invalid admin secret")
    STEP = "admin-authentication"
    mailbox = imaplib.IMAP4("127.0.0.1", 143, timeout=30)
    try:
        mailbox.login("cyrus", password)
        STEP = "mailbox-inventory"
        result, mailboxes = mailbox.list()
        if result != "OK" or not mailboxes or mailboxes == [None]:
            raise ValueError("No existing mailbox inventory")
        if mailbox.noop()[0] != "OK":
            raise ValueError("IMAP NOOP failed")
        count = len(mailboxes)
    finally:
        mailbox.logout()
    STEP = "lmtp-sieve"
    for port, prefix in ((24, b"220 "), (4190, b'"IMPLEMENTATION"')):
        with socket.create_connection(("127.0.0.1", port), timeout=10) as stream:
            if not stream.recv(4096).startswith(prefix):
                raise ValueError("Missing LMTP/ManageSieve greeting")
    STEP = "http-authentication-barrier"
    try:
        with urlopen("http://127.0.0.1:8008/jmap/", timeout=10):
            raise ValueError("Unauthenticated JMAP was not rejected")
    except HTTPError as error:
        if error.code != 401:
            raise ValueError("Unexpected JMAP authentication status") from None
    (data / ".robin-jmap-verified").write_text(release + "\n")
    print(f"PASS Cyrus {release}: authenticated IMAP inventory {count}, LMTP/Sieve, JMAP auth barrier; no mail writes")


if __name__ == "__main__":
    try:
        verify()
    except Exception as error:
        print(f"ERROR: intermediate daemon verification failed at {STEP} ({type(error).__name__}); no version advance", file=sys.stderr)
        sys.exit(1)
