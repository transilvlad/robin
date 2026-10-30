#!/usr/bin/env python3
"""Bounded, read-only Cyrus inventory. No server writes or public mail metadata."""

import argparse
from contextlib import contextmanager
from email import policy
from email.parser import BytesHeaderParser
from email.utils import parsedate_to_datetime
import hashlib
import imaplib
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import secrets
import socket
import ssl
import stat
import sys
import time


FORMAT = 1
MAX_LINE = 65536
MAX_FILE = 16 * 1024 * 1024
SCOPES = ("legacy-before-maintenance", "new-volume-clone")


class Refusal(Exception):
    """Only fixed, non-private reason codes belong in this exception."""


def require(condition, code="invalid-protocol"):
    if not condition:
        raise Refusal(code)


class Budget:
    def __init__(self, *, deadline=300, timeout=10, mailboxes=1000,
                 messages=10000, literal=32 * 1024 * 1024,
                 wire=512 * 1024 * 1024, output=MAX_FILE):
        for value in (deadline, timeout):
            require(isinstance(value, (int, float)) and math.isfinite(value)
                    and 0 < value <= 86400, "invalid-limit")
        for value, ceiling in ((mailboxes, 100000), (messages, 1000000),
                               (literal, 128 * 1024 * 1024),
                               (wire, 2 * 1024 * 1024 * 1024), (output, MAX_FILE)):
            require(type(value) is int and 0 < value <= ceiling, "invalid-limit")
        self.end = time.monotonic() + deadline
        self.timeout = timeout
        self.mailboxes, self.messages = mailboxes, messages
        self.literal, self.wire, self.output = literal, wire, output
        self.received = 0

    def remaining(self):
        left = self.end - time.monotonic()
        require(left > 0, "deadline")
        return min(left, self.timeout)


class BoundedTransport:
    """IMAP4 hooks: cap allocations, suppress imaplib diagnostics, avoid DNS.

    Own the receive buffer rather than buffered-file reads: a slow trickle must
    recheck the whole-run deadline on every recv, not just every IMAP command.
    """
    def __init__(self, *args, address, budget, **kwargs):
        self.address, self.budget = address, budget
        self.pending = bytearray()
        super().__init__(*args, **kwargs)
        self.debug = 0

    def _mesg(self, *args, **kwargs):
        pass

    def _log(self, *args, **kwargs):
        pass

    def _command(self, name, *args):
        require(name in {"CAPABILITY", "LOGIN", "LIST", "LSUB", "EXAMINE",
                         "UID", "GETACL", "GETQUOTAROOT"}, "write-command-refused")
        if name == "UID":
            require(args and args[0] in ("SEARCH", "FETCH"), "write-command-refused")
            if args[0] == "FETCH":
                require(len(args) == 3 and args[2] in (
                    "(UID FLAGS INTERNALDATE RFC822.SIZE)",
                    "(UID FLAGS INTERNALDATE RFC822.SIZE BODY.PEEK[])"), "write-command-refused")
        self.budget.remaining()
        return super()._command(name, *args)

    def _create_socket(self, timeout):
        ip = ipaddress.ip_address(self.address)
        sock = socket.socket(socket.AF_INET6 if ip.version == 6 else socket.AF_INET,
                             socket.SOCK_STREAM)
        try:
            sock.settimeout(self.budget.remaining())
            sock.connect((str(ip), self.port))
            return sock
        except BaseException:
            sock.close()
            raise

    def send(self, data):
        self.sock.settimeout(self.budget.remaining())
        return super().send(data)

    def _receive(self):
        self.sock.settimeout(self.budget.remaining())
        allowance = self.budget.wire - self.budget.received
        require(allowance > 0, "wire-limit")
        block = self.sock.recv(min(65536, allowance))
        require(bool(block), "truncated-response")
        self.budget.received += len(block)
        self.pending.extend(block)

    def read(self, size):
        require(type(size) is int and 0 <= size <= self.budget.literal, "literal-limit")
        require(size <= len(self.pending) + self.budget.wire - self.budget.received,
                "wire-limit")
        self.budget.remaining()
        while len(self.pending) < size:
            self._receive()
        result = bytes(self.pending[:size])
        del self.pending[:size]
        return result

    def readline(self):
        while True:
            self.budget.remaining()
            end = self.pending.find(b"\n")
            if end >= 0:
                require(end + 1 <= MAX_LINE, "line-limit")
                line = bytes(self.pending[:end + 1])
                del self.pending[:end + 1]
                require(line.endswith(b"\r\n"), "invalid-protocol")
                return line
            require(len(self.pending) < MAX_LINE, "line-limit")
            self._receive()


class PlainIMAP(BoundedTransport, imaplib.IMAP4):
    pass


class TLSIMAP(BoundedTransport, imaplib.IMAP4_SSL):
    def _create_socket(self, timeout):
        sock = BoundedTransport._create_socket(self, timeout)
        try:
            sock.settimeout(self.budget.remaining())
            return self.ssl_context.wrap_socket(sock, server_hostname=self.host)
        except BaseException:
            sock.close()
            raise


def parse_rows(data, literal_limit=128 * 1024 * 1024):
    """Parse imaplib's bytes/(header,literal)/trailer response representation.

    Literals stay opaque tokens. Never split mailbox names or FETCH metadata on
    spaces, decode literal bodies as protocol, or use regexes to parse lists.
    """
    require(isinstance(data, list))
    if data == [None]:
        return []
    rows, root, stack = [], [], []
    current = root
    continuing = False
    tokens = 0

    def add(value):
        nonlocal tokens
        tokens += 1
        require(tokens <= 200000, "token-limit")
        current.append(value)

    for part in data:
        literal = None
        if isinstance(part, tuple):
            require(len(part) == 2 and all(isinstance(x, bytes) for x in part))
            text, literal = part
            marker = re.search(rb"\{([0-9]{1,10})\}$", text)
            require(marker is not None)
            size = int(marker[1])
            require(size == len(literal) and size <= literal_limit, "truncated-literal")
            text = text[:marker.start()]
        else:
            require(isinstance(part, bytes))
            text = part
        require(len(text) <= MAX_LINE, "line-limit")
        if continuing:
            require(not text or text[0] in b" )")
        i = 0
        while i < len(text):
            char = text[i]
            if char == 32:
                i += 1
            elif char == 40:
                group = []
                add(group)
                stack.append(current)
                require(len(stack) <= 16, "nesting-limit")
                current = group
                i += 1
            elif char == 41:
                require(bool(stack))
                current = stack.pop()
                i += 1
                require(i == len(text) or text[i] in b" )")
            elif char == 34:
                quoted = bytearray()
                i += 1
                while i < len(text) and text[i] != 34:
                    value = text[i]
                    if value == 92:
                        i += 1
                        require(i < len(text) and text[i] in (34, 92))
                        value = text[i]
                    require(value >= 32 and value != 127)
                    quoted.append(value)
                    i += 1
                require(i < len(text))
                add(bytes(quoted))
                i += 1
                require(i == len(text) or text[i] in b" )")
            else:
                start = i
                while i < len(text) and text[i] not in b" ()":
                    require(32 < text[i] < 127 and text[i] not in b'"{}')
                    i += 1
                atom = text[start:i]
                require(bool(atom))
                add(None if atom.upper() == b"NIL" else atom)
                require(i == len(text) or text[i] in b" )")
        if literal is not None:
            add(literal)
            continuing = True
        else:
            require(not stack)
            rows.append(root)
            root, stack = [], []
            current = root
            continuing = False
    require(not continuing and not stack and not root, "truncated-response")
    return rows


def number(value, zero=False):
    require(isinstance(value, bytes) and re.fullmatch(rb"[0-9]{1,10}", value) is not None)
    result = int(value)
    require((0 if zero else 1) <= result <= 4294967295)
    return result


def name(value):
    require(isinstance(value, bytes) and 0 < len(value) <= 4096)
    require(not any(char < 32 or char == 127 for char in value), "unsafe-mailbox-name")
    return value


def quote(value):
    name(value)
    return b'"' + value.replace(b"\\", b"\\\\").replace(b'"', b'\\"') + b'"'


def symbols(values):
    require(isinstance(values, list))
    result = []
    for value in values:
        require(isinstance(value, bytes) and re.fullmatch(rb"[^\x00-\x20\x7f-\xff(){}\"]+", value))
        result.append(value.decode("ascii").lower())
    require(len(result) == len(set(result)))
    return sorted(result)


def listing(data, maximum):
    result = {}
    for row in parse_rows(data):
        require(len(result) < maximum, "mailbox-limit")
        require(len(row) == 3)
        flags, delimiter, mailbox = row
        mailbox = name(mailbox)
        require(mailbox.hex() not in result)
        require(delimiter is None or isinstance(delimiter, bytes) and len(delimiter) == 1)
        attributes = symbols(flags)
        require("\\nonexistent" not in attributes, "unavailable-mailbox")
        result[mailbox.hex()] = {"attributes": attributes,
                                 "delimiter": None if delimiter is None else delimiter.hex()}
    return result


def ok(reply):
    require(isinstance(reply, tuple) and len(reply) == 2 and reply[0] == "OK",
            "imap-command-failed")
    return reply[1]


def search(client, maximum):
    rows = parse_rows(ok(client.uid("SEARCH", None, "ALL")))
    require(len(rows) == 1)
    require(len(rows[0]) <= maximum, "message-limit")
    uids = [number(item) for item in rows[0]]
    require(len(set(uids)) == len(uids))
    return sorted(uids)


def examine(client, mailbox):
    count = ok(client.select(quote(mailbox), readonly=True))
    require(len(count) == 1)
    result = {"exists": number(count[0], zero=True)}
    for key in ("UIDVALIDITY", "UIDNEXT"):
        kind, data = client.response(key)
        require(kind == key and isinstance(data, list) and len(data) == 1,
                "missing-mailbox-counters")
        result[key.lower()] = number(data[0])
    return result


def fetch(client, uid, budget, body=False):
    fields = "(UID FLAGS INTERNALDATE RFC822.SIZE" + (" BODY.PEEK[])" if body else ")")
    rows = parse_rows(ok(client.uid("FETCH", str(uid), fields)), budget.literal)
    require(len(rows) == 1 and len(rows[0]) == 2)
    sequence, values = rows[0]
    number(sequence)
    require(isinstance(values, list) and len(values) % 2 == 0)
    record = {}
    for key, value in zip(values[::2], values[1::2]):
        require(isinstance(key, bytes))
        key = key.upper()
        require(key not in record)
        record[key] = value
    require(set(record) == {b"UID", b"FLAGS", b"INTERNALDATE", b"RFC822.SIZE"}
            | ({b"BODY[]"} if body else set()))
    require(number(record[b"UID"]) == uid, "uid-mismatch")
    size = number(record[b"RFC822.SIZE"], zero=True)
    require(size <= budget.literal, "literal-limit")
    date = record[b"INTERNALDATE"]
    require(isinstance(date, bytes) and re.fullmatch(
        rb"[ 0-3][0-9]-[A-Za-z]{3}-[0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} [+-][0-9]{4}", date))
    try:
        parsed = parsedate_to_datetime(date.decode("ascii").lstrip())
        require(parsed.tzinfo is not None)
        # Force timezone validation as well as date parsing.
        parsed.utcoffset()
    except (ValueError, TypeError, OverflowError):
        raise Refusal("invalid-internaldate") from None
    result = {"uid": uid, "flags": symbols(record[b"FLAGS"]),
              "internaldate": date.decode("ascii"), "size": size}
    if body:
        raw = record[b"BODY[]"]
        require(isinstance(raw, bytes) and len(raw) == size, "truncated-message")
        headers = BytesHeaderParser(policy=policy.default).parsebytes(raw)
        result.update(sha256=hashlib.sha256(raw).hexdigest(),
                      email={"header_count": len(headers),
                             "defects": sorted({type(item).__name__ for item in headers.defects})})
    return result


def observations(client, mailbox, capabilities, budget):
    result = {}
    if b"ACL" not in capabilities:
        result["acl"] = {"status": "unsupported"}
    else:
        rows = parse_rows(ok(client.getacl(quote(mailbox))), budget.literal)
        require(len(rows) == 1 and len(rows[0]) % 2 == 1 and rows[0][0] == mailbox)
        entries = {}
        for identity, rights in zip(rows[0][1::2], rows[0][2::2]):
            name(identity)
            require(isinstance(rights, bytes) and re.fullmatch(rb"[A-Za-z0-9]*", rights))
            require(identity.hex() not in entries and len(set(rights)) == len(rights))
            entries[identity.hex()] = "".join(sorted(rights.decode("ascii")))
        result["acl"] = {"status": "supported", "entries": entries}
    if b"QUOTA" not in capabilities:
        result["quota"] = {"status": "unsupported"}
    else:
        data = ok(client.getquotaroot(quote(mailbox)))
        require(isinstance(data, list) and len(data) == 2)
        roots = parse_rows(data[0], budget.literal)
        quotas = parse_rows(data[1], budget.literal)
        require(len(roots) == 1 and roots[0] and roots[0][0] == mailbox)
        root_names = roots[0][1:]
        require(all(isinstance(item, bytes) for item in root_names))
        require(len(root_names) == len(set(root_names)))
        entries = {}
        for row in quotas:
            require(len(row) == 2 and row[0] in root_names and isinstance(row[1], list))
            root, resources = row
            require(root.hex() not in entries and len(resources) % 3 == 0)
            resource_map = {}
            for i in range(0, len(resources), 3):
                resource, usage, limit = resources[i:i + 3]
                require(isinstance(resource, bytes) and re.fullmatch(rb"[A-Za-z0-9-]+", resource))
                resource = resource.decode("ascii").upper()
                require(resource not in resource_map)
                resource_map[resource] = [number(usage, zero=True), number(limit, zero=True)]
            entries[root.hex()] = resource_map
        require(set(entries) == {item.hex() for item in root_names}, "missing-quota-root")
        result["quota"] = {"status": "supported", "roots": entries}
    return result


def inventory(client, budget):
    # Refresh after authentication: extension availability may change on LOGIN.
    # Cyrus can attach CAPABILITY to the tagged LOGIN completion; drain that
    # cached observation so it is not mistaken for a second fresh response.
    client.response("CAPABILITY")
    cap_rows = parse_rows(ok(client.capability()))
    require(len(cap_rows) == 1 and all(isinstance(item, bytes) for item in cap_rows[0]))
    capabilities = {item.upper() for item in cap_rows[0]}
    require(b"IMAP4REV1" in capabilities or b"IMAP4REV2" in capabilities)
    mailboxes = listing(ok(client.list('""', '"*"')), budget.mailboxes)
    require(bool(mailboxes), "empty-inventory")
    subscriptions = listing(ok(client.lsub('""', '"*"')), budget.mailboxes)
    records, total = {}, 0
    for key, info in sorted(mailboxes.items()):
        budget.remaining()
        mailbox = bytes.fromhex(key)
        selectable = "\\noselect" not in info["attributes"]
        observed = (observations(client, mailbox, capabilities, budget) if selectable else
                    {"acl": {"status": "not-selectable"}, "quota": {"status": "not-selectable"}})
        record = dict(info, observations=observed, state=None, messages=[])
        if selectable:
            state = examine(client, mailbox)
            uids = search(client, budget.messages - total)
            require(len(uids) == state["exists"], "mailbox-changed")
            require(not uids or max(uids) < state["uidnext"], "invalid-uidnext")
            total += len(uids)
            for uid in uids:
                metadata = fetch(client, uid, budget)
                message = fetch(client, uid, budget, body=True)
                require(metadata == {k: message[k] for k in metadata}, "mailbox-changed")
                record["messages"].append(message)
            # Re-EXAMINE and refetch metadata to detect ordinary concurrent writes.
            require(examine(client, mailbox) == state, "mailbox-changed")
            require(search(client, budget.messages) == uids, "mailbox-changed")
            for message in record["messages"]:
                metadata = fetch(client, message["uid"], budget)
                require(metadata == {k: message[k] for k in metadata}, "mailbox-changed")
            record["state"] = state
        records[key] = record
    require(listing(ok(client.list('""', '"*"')), budget.mailboxes) == mailboxes,
            "mailbox-list-changed")
    require(listing(ok(client.lsub('""', '"*"')), budget.mailboxes) == subscriptions,
            "subscriptions-changed")
    for key, record in records.items():
        if "\\noselect" in record["attributes"]:
            continue
        require(observations(client, bytes.fromhex(key), capabilities, budget)
                == record["observations"], "observations-changed")
    require(any(record["state"] is not None for record in records.values()),
            "empty-selectable-inventory")
    budget.remaining()
    return {"mailboxes": records, "subscriptions": subscriptions,
            "counts": {"mailboxes": len(records), "messages": total}}


def private_stat(info, directory=False):
    require(os.name == "posix", "posix-permissions-required")
    require((stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode))
            and info.st_uid == os.geteuid() and not info.st_mode & 0o077,
            "private-permissions-required")
    if not directory:
        require(info.st_nlink == 1, "linked-private-file")


@contextmanager
def private_directory(path):
    require(os.name == "posix", "posix-permissions-required")
    path = Path(path).absolute()
    require(".." not in path.parts, "unsafe-private-path")
    # A protected immediate directory plus non-writable, non-symlink ancestors
    # prevents other users replacing a pathname between checks and open.
    for ancestor in (path, *path.parents):
        info = ancestor.lstat()
        require(stat.S_ISDIR(info.st_mode), "unsafe-private-path")
        require(not info.st_mode & 0o022 or
                info.st_uid == 0 and info.st_mode & stat.S_ISVTX,
                "unsafe-private-path")
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        private_stat(os.fstat(fd), directory=True)
        yield fd
    finally:
        os.close(fd)


def private_read(path, maximum=MAX_FILE):
    path = Path(path)
    with private_directory(path.parent) as directory:
        fd = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        with os.fdopen(fd, "rb") as stream:
            info = os.fstat(stream.fileno())
            private_stat(info)
            require(info.st_size <= maximum, "private-file-limit")
            raw = stream.read(maximum + 1)
            require(len(raw) <= maximum, "private-file-limit")
            return raw


def absent(directory, filename):
    try:
        os.stat(filename, dir_fd=directory, follow_symlinks=False)
    except FileNotFoundError:
        return
    raise Refusal("manifest-exists")


def private_write(path, document, budget):
    path = Path(path)
    with private_directory(path.parent) as directory:
        absent(directory, path.name)
        temporary = ".inventory-" + secrets.token_hex(16)
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                     0o600, dir_fd=directory)
        try:
            with os.fdopen(fd, "wb") as stream:
                os.fchmod(stream.fileno(), 0o600)
                written = 0
                for chunk in json.JSONEncoder(sort_keys=True, separators=(",", ":"),
                                              allow_nan=False).iterencode(document):
                    budget.remaining()
                    chunk = chunk.encode("ascii")
                    written += len(chunk)
                    require(written <= budget.output, "manifest-limit")
                    stream.write(chunk)
                stream.flush()
                os.fsync(stream.fileno())
            budget.remaining()
            # link is an atomic no-replace publication, unlike POSIX rename.
            os.link(temporary, path.name, src_dir_fd=directory, dst_dir_fd=directory,
                    follow_symlinks=False)
            os.unlink(temporary, dir_fd=directory)
            os.fsync(directory)
        finally:
            try:
                os.unlink(temporary, dir_fd=directory)
            except FileNotFoundError:
                pass


def json_document(raw):
    def unique(items):
        result = {}
        for key, value in items:
            require(key not in result, "duplicate-json-key")
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=unique,
                      parse_constant=lambda _: require(False, "invalid-json"))


def credentials(username, path):
    require(isinstance(username, str) and 0 < len(username) <= 1024
            and all(32 <= ord(char) < 127 for char in username), "invalid-admin-username")
    raw = private_read(path, 8194)
    # Accept the existing raw secret with at most one final LF/CRLF, not JSON.
    # Preserve spaces; multiple trailing lines and embedded controls are refused.
    raw = raw[:-2] if raw.endswith(b"\r\n") else raw[:-1] if raw.endswith(b"\n") else raw
    require(32 <= len(raw) <= 8192 and all(32 <= char < 127 for char in raw),
            "invalid-password-file")
    return {"username": username, "password": raw.decode("ascii")}


def target(host, address, scope, plain=False, loopback=False):
    require(isinstance(host, str) and len(host) <= 253 and re.fullmatch(
        r"[A-Za-z0-9](?:[A-Za-z0-9.:-]*[A-Za-z0-9])?", host), "invalid-host")
    require(scope in SCOPES, "invalid-scope")
    ip = ipaddress.ip_address(address)
    require(not ip.is_unspecified and not ip.is_multicast, "invalid-address")
    require(not (plain and loopback), "invalid-plain-mode")
    if loopback:
        require(ip in ipaddress.ip_network("127.0.0.0/8") or ip == ipaddress.ip_address("::1"),
                "plaintext-refused")
    elif plain:
        networks = ("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
                    "::1/128", "fc00::/7")
        require(scope == "new-volume-clone" and any(ip in ipaddress.ip_network(net)
                for net in networks), "plaintext-refused")
    return str(ip)


def capture(args):
    budget = Budget(deadline=args.deadline, timeout=args.socket_timeout,
                    mailboxes=args.max_mailboxes, messages=args.max_messages,
                    literal=args.max_literal_bytes, wire=args.max_wire_bytes,
                    output=args.max_output_bytes)
    address = target(args.host, args.connect_address, args.scope,
                     args.plain_private_rehearsal, args.plain_loopback)
    require(1 <= args.port <= 65535, "invalid-port")
    require(re.fullmatch(r"[a-z0-9][a-z0-9.-]{0,63}", args.stage), "invalid-stage")
    require(not (args.plain_private_rehearsal or args.plain_loopback) or args.ca_file is None,
            "plaintext-ca-refused")
    with private_directory(args.manifest.parent) as directory:
        absent(directory, args.manifest.name)
    secret = credentials(args.username, args.password_file)
    client = None
    try:
        options = dict(host=args.host, port=args.port, address=address, budget=budget,
                       timeout=budget.remaining())
        if args.plain_private_rehearsal or args.plain_loopback:
            client = PlainIMAP(**options)
        else:
            context = ssl.create_default_context(cafile=args.ca_file)
            context.minimum_version = ssl.TLSVersion.TLSv1_2
            require(context.check_hostname and context.verify_mode == ssl.CERT_REQUIRED,
                    "tls-verification-required")
            client = TLSIMAP(ssl_context=context, **options)
        ok(client.login(secret["username"], secret["password"]))
        secret.clear()
        document = inventory(client, budget)
        document.update(format=FORMAT, complete=True, scope=args.scope, stage=args.stage,
                        endpoint={"host": args.host, "address": address, "port": args.port,
                                  "transport": "plain-loopback" if args.plain_loopback else
                                  "plain-private-rehearsal" if args.plain_private_rehearsal
                                  else "verified-tls"})
        validate_manifest(document)
        private_write(args.manifest, document, budget)
        print("PASS inventory: mailboxes=%d messages=%d" %
              (document["counts"]["mailboxes"], document["counts"]["messages"]))
    finally:
        secret.clear()
        if client is not None:
            # No CLOSE, LOGOUT, retries, flag changes, or expunge path.
            client.shutdown()


def keys(value, expected):
    require(isinstance(value, dict) and set(value) == set(expected.split()), "invalid-manifest")


def integer(value, minimum=0):
    require(type(value) is int and minimum <= value <= 4294967295, "invalid-manifest")


def hex_string(value, empty=False):
    require(isinstance(value, str) and len(value) <= 8192
            and re.fullmatch(r"(?:[0-9a-f]{2})*", value) is not None
            and (empty or bool(value)), "invalid-manifest")
    return bytes.fromhex(value)


def string_list(value):
    require(isinstance(value, list) and all(isinstance(item, str) for item in value)
            and value == sorted(set(value)), "invalid-manifest")


def validate_listing(value):
    keys(value, "attributes delimiter")
    string_list(value["attributes"])
    require(all(item.isascii() and re.fullmatch(r'[^\x00-\x20\x7f(){}"]+', item)
                and item == item.lower() for item in value["attributes"]), "invalid-manifest")
    require("\\nonexistent" not in value["attributes"], "invalid-manifest")
    if value["delimiter"] is not None:
        require(len(hex_string(value["delimiter"])) == 1, "invalid-manifest")


def validate_manifest(doc):
    keys(doc, "format complete scope stage endpoint mailboxes subscriptions counts")
    require(type(doc["format"]) is int and doc["format"] == FORMAT and doc["complete"] is True,
            "incomplete-manifest")
    require(doc["scope"] in SCOPES and isinstance(doc["stage"], str)
            and re.fullmatch(r"[a-z0-9][a-z0-9.-]{0,63}", doc["stage"]), "invalid-manifest")
    endpoint = doc["endpoint"]
    keys(endpoint, "host address port transport")
    require(endpoint["transport"] in ("plain-loopback", "plain-private-rehearsal", "verified-tls"),
            "invalid-manifest")
    integer(endpoint["port"], 1)
    require(endpoint["port"] <= 65535, "invalid-manifest")
    target(endpoint["host"], endpoint["address"], doc["scope"],
           endpoint["transport"] == "plain-private-rehearsal", endpoint["transport"] == "plain-loopback")
    require(isinstance(doc["mailboxes"], dict) and bool(doc["mailboxes"])
            and len(doc["mailboxes"]) <= 100000, "invalid-manifest")
    require(isinstance(doc["subscriptions"], dict) and len(doc["subscriptions"]) <= 100000,
            "invalid-manifest")
    for mailbox, entry in doc["subscriptions"].items():
        name(hex_string(mailbox))
        validate_listing(entry)
    total = 0
    for mailbox, record in doc["mailboxes"].items():
        name(hex_string(mailbox))
        keys(record, "attributes delimiter observations state messages")
        validate_listing({key: record[key] for key in ("attributes", "delimiter")})
        observations_record = record["observations"]
        keys(observations_record, "acl quota")
        for feature, value_key in (("acl", "entries"), ("quota", "roots")):
            observation = observations_record[feature]
            require(isinstance(observation, dict), "invalid-manifest")
            status = observation.get("status")
            require(status in ("supported", "unsupported", "not-selectable"), "invalid-manifest")
            require((status == "not-selectable") == ("\\noselect" in record["attributes"]),
                    "invalid-manifest")
            keys(observation, "status" if status != "supported" else "status " + value_key)
            if status != "supported":
                continue
            entries = observation[value_key]
            require(isinstance(entries, dict), "invalid-manifest")
            for key, value in entries.items():
                hex_string(key, empty=feature == "quota")
                if feature == "acl":
                    require(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9]*", value)
                            and value == "".join(sorted(set(value))), "invalid-manifest")
                else:
                    require(isinstance(value, dict), "invalid-manifest")
                    for resource, pair in value.items():
                        require(re.fullmatch(r"[A-Z0-9-]+", resource) and isinstance(pair, list)
                                and len(pair) == 2, "invalid-manifest")
                        for count in pair:
                            integer(count)
        messages = record["messages"]
        require(isinstance(messages, list), "invalid-manifest")
        if "\\noselect" in record["attributes"]:
            require(record["state"] is None and not messages, "invalid-manifest")
            continue
        state = record["state"]
        keys(state, "exists uidvalidity uidnext")
        integer(state["exists"])
        integer(state["uidvalidity"], 1)
        integer(state["uidnext"], 1)
        require(state["exists"] == len(messages), "incomplete-manifest")
        previous = 0
        for message in messages:
            keys(message, "uid flags internaldate size sha256 email")
            integer(message["uid"], previous + 1)
            previous = message["uid"]
            require(previous < state["uidnext"], "invalid-manifest")
            string_list(message["flags"])
            require(symbols([item.encode("ascii") for item in message["flags"]])
                    == message["flags"], "invalid-manifest")
            integer(message["size"])
            require(message["size"] <= 128 * 1024 * 1024, "invalid-manifest")
            require(isinstance(message["sha256"], str) and
                    re.fullmatch(r"[0-9a-f]{64}", message["sha256"]), "invalid-manifest")
            date = message["internaldate"]
            require(isinstance(date, str) and re.fullmatch(
                r"[ 0-3][0-9]-[A-Za-z]{3}-[0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} [+-][0-9]{4}", date),
                "invalid-manifest")
            require(parsedate_to_datetime(date.lstrip()).tzinfo is not None, "invalid-manifest")
            keys(message["email"], "header_count defects")
            integer(message["email"]["header_count"])
            string_list(message["email"]["defects"])
            require(all(re.fullmatch(r"[A-Za-z]+", item) for item in message["email"]["defects"]),
                    "invalid-manifest")
        total += len(messages)
        require(total <= 1000000, "invalid-manifest")
    keys(doc["counts"], "mailboxes messages")
    integer(doc["counts"]["mailboxes"], 1)
    integer(doc["counts"]["messages"])
    require(doc["counts"] == {"mailboxes": len(doc["mailboxes"]), "messages": total},
            "incomplete-manifest")
    require(any(record["state"] is not None for record in doc["mailboxes"].values()),
            "empty-selectable-inventory")


def compare(before, after, allow_uidnext_increase=False):
    validate_manifest(before)
    validate_manifest(after)
    require(after["scope"] == "new-volume-clone", "comparison-requires-clone")
    require(before["subscriptions"] == after["subscriptions"], "subscriptions-differ")
    require(before["mailboxes"].keys() == after["mailboxes"].keys(), "mailboxes-differ")
    increases = 0
    for key, old in before["mailboxes"].items():
        new = after["mailboxes"][key]
        for field in ("attributes", "delimiter", "observations", "messages"):
            require(old[field] == new[field], "inventory-differs")
        if old["state"] is None:
            require(new["state"] is None, "inventory-differs")
            continue
        require(new["state"] is not None, "inventory-differs")
        for field in ("exists", "uidvalidity"):
            require(old["state"][field] == new["state"][field], "inventory-differs")
        old_next, new_next = old["state"]["uidnext"], new["state"]["uidnext"]
        require(new_next == old_next or allow_uidnext_increase and new_next > old_next,
                "uidnext-differs")
        increases += new_next > old_next
    return increases


class QuietParser(argparse.ArgumentParser):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, allow_abbrev=False, **kwargs)

    def error(self, message):
        # argparse normally echoes offending arguments, which could be private.
        raise Refusal("invalid-arguments")


def main(argv=None):
    parser = QuietParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    probe = commands.add_parser("capture")
    probe.add_argument("--host", required=True)
    probe.add_argument("--connect-address", required=True)
    probe.add_argument("--port", required=True, type=int)
    probe.add_argument("--scope", required=True, choices=SCOPES)
    probe.add_argument("--stage", required=True)
    probe.add_argument("--username", required=True)
    probe.add_argument("--password-file", required=True, type=Path)
    probe.add_argument("--manifest", required=True, type=Path)
    probe.add_argument("--ca-file", type=Path)
    plain = probe.add_mutually_exclusive_group()
    plain.add_argument("--plain-private-rehearsal", action="store_true")
    plain.add_argument("--plain-loopback", action="store_true")
    probe.add_argument("--deadline", type=float, default=300)
    probe.add_argument("--socket-timeout", type=float, default=10)
    probe.add_argument("--max-mailboxes", type=int, default=1000)
    probe.add_argument("--max-messages", type=int, default=10000)
    probe.add_argument("--max-literal-bytes", type=int, default=32 * 1024 * 1024)
    probe.add_argument("--max-wire-bytes", type=int, default=512 * 1024 * 1024)
    probe.add_argument("--max-output-bytes", type=int, default=MAX_FILE)
    diff = commands.add_parser("compare")
    diff.add_argument("--before", required=True, type=Path)
    diff.add_argument("--after", required=True, type=Path)
    diff.add_argument("--allow-uidnext-increase", action="store_true")
    try:
        args = parser.parse_args(argv)
        if args.action == "capture":
            capture(args)
        else:
            require(args.before.absolute() != args.after.absolute(), "same-manifest")
            before = json_document(private_read(args.before))
            after = json_document(private_read(args.after))
            increases = compare(before, after, args.allow_uidnext_increase)
            print("PASS comparison: mailboxes=%d messages=%d uidnext_increases=%d" %
                  (after["counts"]["mailboxes"], after["counts"]["messages"], increases))
        return 0
    except Refusal as error:
        print("FAIL inventory: " + str(error), file=sys.stderr)
    except (OSError, ValueError, TypeError, KeyError, UnicodeError, RecursionError,
            imaplib.IMAP4.error, EOFError):
        print("FAIL inventory: local-or-protocol-error", file=sys.stderr)
    except KeyboardInterrupt:
        print("FAIL inventory: interrupted", file=sys.stderr)
    except Exception:
        # Last-resort CLI privacy boundary: never render library exception text.
        print("FAIL inventory: unexpected-local-or-protocol-error", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
