#!/usr/bin/env python3
"""Synthetic LMTP/IMAP upgrade inventory; contains no production credentials."""

import argparse
from email.message import EmailMessage
from email.policy import SMTP
import hashlib
import imaplib
import json
from pathlib import Path
import re
import smtplib
import sys


USERS = ("alice@example.test", "bob@example.test")
FOLDERS = ("INBOX", "Drafts", "Sent", "Trash", "Junk", "Upgrade QA")


def raw_message(user, number):
    mail = EmailMessage(policy=SMTP)
    mail["From"] = user
    mail["To"] = user
    mail["Date"] = "Mon, 26 Oct 2026 12:00:00 +0000"
    mail["Message-ID"] = f"<cyrus-upgrade-{user.split('@')[0]}-{number}@example.test>"
    mail["Subject"] = f"Synthetic Cyrus upgrade {number}"
    mail.set_content(f"Synthetic upgrade body {number}. Only local example.test mail.\n")
    mail.add_attachment(bytes(range(256)) * 4, maintype="application", subtype="octet-stream", filename="qa.bin")
    return mail.as_bytes()


def login(host, user):
    mailbox = imaplib.IMAP4(host, 143, timeout=30)
    mailbox.login(user, "synthetic-only-password")
    return mailbox


def seed(host):
    password = Path("/run/secrets/cyrus_admin_password").read_text().rstrip("\r\n")
    admin = imaplib.IMAP4(host, 143, timeout=30)
    try:
        admin.login("cyrus", password)
        for user in USERS + ("disabled@example.test",):
            for folder in (FOLDERS if user in USERS else ("INBOX",)):
                local, domain = user.split("@")
                name = f"user/{local}" + ("" if folder == "INBOX" else "/" + folder) + "@" + domain
                status, response = admin.create('"' + name + '"')
                if status != "OK" and b"already exists" not in b" ".join(response).lower():
                    raise ValueError("Could not create synthetic mailbox")
    finally:
        admin.logout()
    for user in USERS:
        with smtplib.LMTP(host, 24, timeout=30) as lmtp:
            for number in range(12):
                if lmtp.sendmail(user, [user], raw_message(user, number)):
                    raise ValueError("Synthetic LMTP delivery failed")
        mailbox = login(host, user)
        try:
            for index, folder in enumerate(FOLDERS):
                mailbox.subscribe('"' + folder + '"')
                if folder != "INBOX":
                    if mailbox.append('"' + folder + '"', "(\\Seen)", None, raw_message(user, 20 + index))[0] != "OK":
                        raise ValueError("Could not append synthetic folder mail")
            mailbox.select("INBOX")
            mailbox.uid("STORE", "1:3", "+FLAGS.SILENT", "(\\Seen \\Flagged $upgradeqa)")
            mailbox.uid("STORE", "4", "+FLAGS.SILENT", "(\\Answered)")
        finally:
            mailbox.logout()
    print("PASS seeded two synthetic users, LMTP, folders, MIME/attachments and flags")


def inventory(host):
    result = {}
    for user in USERS:
        mailbox = login(host, user)
        try:
            folders = {}
            for folder in FOLDERS:
                if mailbox.select('"' + folder + '"', readonly=True)[0] != "OK":
                    raise ValueError("Synthetic folder unavailable")
                validity = mailbox.response("UIDVALIDITY")[1][0].decode()
                response, values = mailbox.uid("SEARCH", None, "ALL")
                if response != "OK":
                    raise ValueError("Synthetic search failed")
                messages = {}
                for uid in values[0].split():
                    response, values = mailbox.uid("FETCH", uid, "(UID FLAGS INTERNALDATE BODY.PEEK[])")
                    if response != "OK":
                        raise ValueError("Read-only fetch failed")
                    item = next(part for part in values if isinstance(part, tuple))
                    flags = sorted(flag.decode() for flag in imaplib.ParseFlags(item[0]) if flag != b"\\Recent")
                    internaldate = re.search(br'INTERNALDATE "([^"]+)"', item[0]).group(1).decode()
                    messages[uid.decode()] = {"sha256": hashlib.sha256(item[1]).hexdigest(),
                                               "flags": flags, "internaldate": internaldate}
                folders[folder] = {"uidvalidity": validity, "messages": messages}
            # Canonical account isolation must survive every staged upgrade.
            other = "bob" if user == USERS[0] else "alice"
            for other_folder in (f'"Other Users/{other}"', f'"Other Users/{other}@example.test"', f'"user/{other}@example.test"'):
                if mailbox.select(other_folder, readonly=True)[0] == "OK":
                    raise ValueError("Cross-user mailbox access was allowed")
            result[user] = folders
        finally:
            mailbox.logout()
    for user in ("disabled@example.test", "alias@example.test", USERS[0]):
        mailbox = imaplib.IMAP4(host, 143, timeout=30)
        try:
            try:
                mailbox.login(user, "wrong-password" if user in USERS else "synthetic-only-password")
            except imaplib.IMAP4.error:
                continue
            raise ValueError("Disabled/alias/wrong-password login succeeded")
        finally:
            mailbox.logout()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True)
    parser.add_argument("--seed", action="store_true")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--baseline", type=Path)
    args = parser.parse_args()
    if args.seed:
        seed(args.host)
    data = inventory(args.host)
    if args.baseline and data != json.loads(args.baseline.read_text()):
        raise ValueError("UIDVALIDITY/UID/flags/date/raw bytes changed")
    if args.output:
        args.output.write_text(json.dumps(data, sort_keys=True, indent=2) + "\n")
    print("PASS canonical authentication, two-user isolation, IMAP UID/flags/date/raw inventory")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("ERROR synthetic mail upgrade check failed; no credentials or mail logged", file=sys.stderr)
        sys.exit(1)
